package br.com.adpilot.commerce;

import br.com.adpilot.integrations.meta.MetaAdsClient;
import br.com.adpilot.integrations.meta.MetaInsightsSummary;
import br.com.adpilot.integrations.meta.MetaInsightsSummaryService;
import br.com.adpilot.integrations.nuvemshop.NuvemshopClient;
import br.com.adpilot.integrations.nuvemshop.NuvemshopException;
import br.com.adpilot.integrations.nuvemshop.NuvemshopPage;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class CommerceSummaryService {
    private static final int MAX_PAGES = 100;
    private static final int MAX_DETAIL_LOOKUPS = 100;
    private static final DateTimeFormatter OFFSET_BASIC =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssZ");

    private final NuvemshopClient shop;
    private final MetaAdsClient metaClient;
    private final MetaInsightsSummaryService metaService;

    public CommerceSummaryService(NuvemshopClient shop, MetaAdsClient metaClient,
                                  MetaInsightsSummaryService metaService) {
        this.shop = shop;
        this.metaClient = metaClient;
        this.metaService = metaService;
    }

    public CommerceSummary summary(LocalDate since, LocalDate until, int pageSize, int maxPages) {
        if (since == null || until == null || since.isAfter(until)
                || ChronoUnit.DAYS.between(since, until) > 89
                || until.isAfter(LocalDate.now(ZoneOffset.UTC).plusDays(1))) {
            throw new NuvemshopException(HttpStatus.BAD_REQUEST, "INVALID_DATE_RANGE",
                    "Informe um periodo valido de ate 90 dias, sem datas futuras.");
        }
        if (pageSize < 1 || pageSize > 100 || maxPages < 1 || maxPages > MAX_PAGES) {
            throw new NuvemshopException(HttpStatus.BAD_REQUEST, "INVALID_PAGINATION",
                    "pageSize deve estar entre 1 e 100 e maxPages entre 1 e 100.");
        }

        // Resolve missing payment dates only for eligible, non-cancelled sales.
        // Individual order GET can return paid_at even when list GET returns null.
        List<Map<String, Object>> undated = new ArrayList<>();
        List<DatedOrder> dated = new ArrayList<>();
        int cancelledIgnored = 0;
        int recoveredDates = 0;
        int detailsFetched = 0;
        int mismatchedDetails = 0;
        boolean detailLimitReached = false;
        Map<LocalDate, DayAccumulator> days = new HashMap<>();
        Set<String> orderIds = new HashSet<>();
        BigDecimal paidRevenue = BigDecimal.ZERO;
        long paidOrders = 0;
        String storeCurrency = null;
        boolean ordersPagesComplete = false;
        int paidOrdersWithoutDate = 0;
        int pages = 0;
        int fetched = 0;
        Long expectedCount = null;

        for (int pageNumber = 1; pageNumber <= maxPages; pageNumber++) {
            NuvemshopPage page = shop.orders(pageNumber, pageSize);
            pages++;
            if (page.data() == null || page.data().isEmpty() && page.hasNext()) {
                throw invalidOrders("A Nuvemshop retornou paginacao de pedidos inconsistente.");
            }
            fetched = Math.addExact(fetched, page.data().size());
            if (page.totalCount() != null) expectedCount = page.totalCount();

            for (Map<String, Object> order : page.data()) {
                if (order == null) throw invalidOrders("Pedido sem identificador.");
                String id = identifier(order.get("id"));
                if (!orderIds.add(id)) continue; // Prevent double counting across moving pages.
                // Cancelled orders are not completed sales, even if payment_status=paid.
                if ("cancelled".equals(order.get("status"))) {
                    cancelledIgnored++;
                    continue;
                }
                if (!"paid".equals(order.get("payment_status"))) continue;

                Object rawPaidAt = order.get("paid_at");
                LocalDate paidDay = rawPaidAt instanceof String value && !value.isBlank()
                        ? utcDayOrNull(value) : null;
                if (paidDay == null) {
                    undated.add(order);
                } else {
                    dated.add(new DatedOrder(order, paidDay));
                }
            }
            if (!page.hasNext()) {
                // Missing/duplicate records must not masquerade as complete.
                ordersPagesComplete = expectedCount == null || orderIds.size() >= expectedCount;
                break;
            }
        }

        // At most 100 additional upstream GET requests per summary, no unbounded fan-out.
        // No created_at substitution and no payments with mismatching identifiers/statuses.
        for (Map<String, Object> original : undated) {
            if (detailsFetched >= MAX_DETAIL_LOOKUPS) {
                detailLimitReached = true;
                paidOrdersWithoutDate++;
                continue;
            }
            detailsFetched++;
            long id;
            try {
                id = Long.parseLong(identifier(original.get("id")));
            } catch (NumberFormatException e) {
                paidOrdersWithoutDate++;
                mismatchedDetails++;
                continue;
            }
            // A 429/permission failure is surfaced by the client, not silently
            // converted to a complete zero-revenue report.
            Map<String, Object> detail = shop.paidOrderDetails(id);
            if (detail == null || !identifier(original.get("id")).equals(
                    detail.get("id") == null ? "" : detail.get("id").toString())
                    || original.get("number") != null
                        && !original.get("number").toString().equals(
                                detail.get("number") == null ? "" : detail.get("number").toString())) {
                paidOrdersWithoutDate++;
                mismatchedDetails++;
                continue;
            }
            if ("cancelled".equals(detail.get("status"))) {
                cancelledIgnored++;
                // The status changed between list and detail. No trustworthy snapshot.
                mismatchedDetails++;
                continue;
            }
            if (!"paid".equals(detail.get("payment_status"))
                    || !original.get("status").equals(detail.get("status"))
                    || !matchingMonetary(original.get("total"), detail.get("total"))
                    || !java.util.Objects.equals(original.get("currency"), detail.get("currency"))) {
                paidOrdersWithoutDate++;
                mismatchedDetails++;
                continue;
            }
            Object rawPaidAt = detail.get("paid_at");
            LocalDate paidDay = rawPaidAt instanceof String v && !v.isBlank()
                    ? utcDayOrNull(v) : null;
            if (paidDay == null) {
                paidOrdersWithoutDate++;
            } else {
                recoveredDates++;
                dated.add(new DatedOrder(original, paidDay));
            }
        }

        for (DatedOrder item : dated) {
            if (item.day.isBefore(since) || item.day.isAfter(until)) continue;
            Map<String, Object> order = item.row;
            String currency = order.get("currency") instanceof String c ? c : "";
            if (!currency.matches("[A-Z]{3}")) {
                throw invalidOrders("Moeda ausente ou invalida em pedido pago.");
            }
            if (storeCurrency != null && !storeCurrency.equals(currency)) {
                throw invalidOrders("Pedidos pagos com moedas diferentes; conversao indisponivel.");
            }
            storeCurrency = currency;
            BigDecimal amount = monetary(order.get("total"));
            paidOrders++;
            paidRevenue = paidRevenue.add(amount);
            DayAccumulator accumulator = days.computeIfAbsent(item.day, ignored -> new DayAccumulator());
            accumulator.orders++;
            accumulator.revenue = accumulator.revenue.add(amount);
        }

        var warnings = new ArrayList<String>();
        // Nuvemshop paid_at instants are normalized to UTC; Meta Insights use ad-account days.
        warnings.add("REPORTING_TIMEZONE_MAY_DIFFER");
        if (!ordersPagesComplete) warnings.add("ORDERS_INCOMPLETE");
        if (paidOrdersWithoutDate > 0) warnings.add("PAID_AT_MISSING_OR_INVALID");
        if (recoveredDates > 0) warnings.add("PAID_AT_RECOVERED_FROM_DETAIL");
        if (detailLimitReached) warnings.add("ORDER_DETAIL_LOOKUP_LIMIT");
        if (mismatchedDetails > 0) warnings.add("ORDER_DETAIL_INCONSISTENT");
        boolean ordersComplete = ordersPagesComplete && paidOrdersWithoutDate == 0
                && mismatchedDetails == 0;

        // Keep the existing Meta aggregation unchanged, including its own pagination limits.
        MetaInsightsSummary meta = metaService.summary(since, until, 100, 20);
        String metaCurrency = metaClient.account().get("currency") instanceof String c
                && c.matches("[A-Z]{3}") ? c : null;
        boolean metaComplete = meta.complete();
        if (!metaComplete) warnings.add("META_INCOMPLETE");

        BigDecimal spend = metaComplete ? meta.totals().spend() : null;
        BigDecimal metaRevenue = metaComplete ? meta.totals().purchaseValue() : null;
        BigDecimal attributedRoas = metaComplete ? meta.totals().roas() : null;
        if (storeCurrency != null && metaCurrency != null && !storeCurrency.equals(metaCurrency)) {
            warnings.add("CURRENCY_MISMATCH");
        }
        if (storeCurrency == null || metaCurrency == null) {
            warnings.add("CURRENCY_NOT_ESTABLISHED");
        }

        BigDecimal total = ordersComplete ? paidRevenue.setScale(2, RoundingMode.HALF_UP) : null;
        Long count = ordersComplete ? paidOrders : null;
        BigDecimal ticket = ordersComplete && paidOrders > 0
                ? paidRevenue.divide(BigDecimal.valueOf(paidOrders), 2, RoundingMode.HALF_UP) : null;

        boolean comparable = ordersComplete && metaComplete && spend != null
                && spend.signum() > 0 && storeCurrency != null
                && storeCurrency.equals(metaCurrency);
        BigDecimal mer = comparable
                ? paidRevenue.divide(spend, 4, RoundingMode.HALF_UP) : null;

        List<CommerceSummary.DailySales> daily = List.of();
        if (ordersComplete) {
            var collected = new ArrayList<CommerceSummary.DailySales>();
            for (LocalDate d = since; !d.isAfter(until); d = d.plusDays(1)) {
                var v = days.get(d);
                collected.add(new CommerceSummary.DailySales(d, v == null ? 0 : v.orders,
                        v == null ? BigDecimal.ZERO.setScale(2) : v.revenue.setScale(2, RoundingMode.HALF_UP)));
            }
            daily = List.copyOf(collected);
        }

        return new CommerceSummary(since, until, ordersComplete, metaComplete,
                ordersComplete && metaComplete, pages, fetched,
                ordersPagesComplete, paidOrdersWithoutDate,
                cancelledIgnored, recoveredDates, detailsFetched, mismatchedDetails,
                ordersPagesComplete ? paidOrders : null,
                ordersPagesComplete ? paidRevenue.setScale(2, RoundingMode.HALF_UP) : null,
                count, total,
                ticket, storeCurrency, metaCurrency,
                spend, metaRevenue, attributedRoas, mer, daily, List.copyOf(warnings));
    }

    private record DatedOrder(Map<String, Object> row, LocalDate day) {}

    private static boolean matchingMonetary(Object listed, Object detailed) {
        try {
            return listed != null && detailed != null &&
                    new BigDecimal(listed.toString()).compareTo(
                            new BigDecimal(detailed.toString())) == 0;
        } catch (NumberFormatException ex) {
            return false;
        }
    }

    private static String identifier(Object raw) {
        if (raw == null) throw invalidOrders("Pedido sem identificador.");
        String id = raw.toString();
        if (!id.matches("[0-9]{1,30}")) throw invalidOrders("Identificador invalido de pedido.");
        return id;
    }

    private static BigDecimal monetary(Object raw) {
        try {
            if (raw == null) throw new NumberFormatException("null");
            BigDecimal value = new BigDecimal(raw.toString());
            if (value.signum() < 0 || value.scale() > 4) throw new NumberFormatException("invalid");
            return value;
        } catch (NumberFormatException e) {
            throw invalidOrders("Total monetario de pedido invalido.");
        }
    }

    /**
     * Invalid or missing paid_at is treated as an unclassifiable paid order,
     * not an exception and never as a created_at-based estimate.
     */
    private static LocalDate utcDayOrNull(String raw) {
        try {
            return OffsetDateTime.parse(raw, DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                    .atZoneSameInstant(ZoneOffset.UTC).toLocalDate();
        } catch (DateTimeParseException ignored) {
            try {
                return OffsetDateTime.parse(raw, OFFSET_BASIC)
                        .atZoneSameInstant(ZoneOffset.UTC).toLocalDate();
            } catch (DateTimeParseException e) {
                return null;
            }
        }
    }

    private static NuvemshopException invalidOrders(String reason) {
        return new NuvemshopException(HttpStatus.BAD_GATEWAY,
                "NUVEMSHOP_INVALID_ORDER_DATA", reason);
    }

    private static final class DayAccumulator {
        long orders;
        BigDecimal revenue = BigDecimal.ZERO;
    }
}
