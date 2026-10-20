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

        Map<LocalDate, DayAccumulator> days = new HashMap<>();
        Set<String> orderIds = new HashSet<>();
        BigDecimal paidRevenue = BigDecimal.ZERO;
        long paidOrders = 0;
        String storeCurrency = null;
        boolean ordersComplete = false;
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
                if (!"paid".equals(order.get("payment_status"))) continue;

                Object rawPaidAt = order.get("paid_at");
                if (!(rawPaidAt instanceof String value) || value.isBlank()) {
                    throw invalidOrders("Pedido pago sem data de pagamento.");
                }
                LocalDate paidDay = utcDay(value);
                if (paidDay.isBefore(since) || paidDay.isAfter(until)) continue;

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
                DayAccumulator accumulator = days.computeIfAbsent(paidDay, ignored -> new DayAccumulator());
                accumulator.orders++;
                accumulator.revenue = accumulator.revenue.add(amount);
            }
            if (!page.hasNext()) {
                // Missing/duplicate records must not masquerade as complete.
                ordersComplete = expectedCount == null || orderIds.size() >= expectedCount;
                break;
            }
        }

        var warnings = new ArrayList<String>();
        // Nuvemshop paid_at instants are normalized to UTC; Meta Insights use ad-account days.
        warnings.add("REPORTING_TIMEZONE_MAY_DIFFER");
        if (!ordersComplete) warnings.add("ORDERS_INCOMPLETE");

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
                ordersComplete && metaComplete, pages, fetched, count, total,
                ticket, storeCurrency, metaCurrency,
                spend, metaRevenue, attributedRoas, mer, daily, List.copyOf(warnings));
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

    private static LocalDate utcDay(String raw) {
        try {
            return OffsetDateTime.parse(raw, DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                    .atZoneSameInstant(ZoneOffset.UTC).toLocalDate();
        } catch (DateTimeParseException ignored) {
            try {
                return OffsetDateTime.parse(raw, OFFSET_BASIC)
                        .atZoneSameInstant(ZoneOffset.UTC).toLocalDate();
            } catch (DateTimeParseException e) {
                throw invalidOrders("Data de pagamento invalida.");
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
