package br.com.adpilot.analytics;

import br.com.adpilot.integrations.nuvemshop.NuvemshopClient;
import br.com.adpilot.integrations.nuvemshop.NuvemshopException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Operator-initiated comparison of gross order fields, NOT a payment ledger.
 * No transactions, refunds, item costs, attribution, or DB writes.
 */
@Service
public class OrderValueAuditService {
    private static final int MAX_PAGES = 20;

    private final NuvemshopClient shop;
    private final OrderValueAuditRepository repository;

    public OrderValueAuditService(NuvemshopClient shop, OrderValueAuditRepository repository) {
        this.shop = shop;
        this.repository = repository;
    }

    /** Only amounts aggregated across comparable eligible pairs leave the JVM. */
    public record CurrencyTotals(String currency, int comparedOrders,
            BigDecimal upstreamGrossTotal, BigDecimal localGrossTotal,
            BigDecimal signedGrossDifference) {}

    public record Report(
            boolean upstreamPaginationReconciled,
            boolean financiallyVerified,
            boolean ordersMatchedToCampaigns,
            int pagesFetched,
            int upstreamRowsFetched,
            int uniqueUpstreamOrderIds,
            Long upstreamReportedTotal,
            int duplicateUpstreamIds,
            int orderIdsMissingLocally,
            int upstreamEligiblePaidOrders,
            int localEligiblePaidOrdersAmongMatches,
            int mutuallyEligiblePaidOrders,
            int comparableEligiblePaidOrders,
            int orderStatusDifferences,
            int paymentStatusDifferences,
            int eligibilityDifferences,
            int currencyDifferences,
            int amountDifferences,
            int upstreamInvalidAmount,
            int localInvalidAmount,
            int upstreamInvalidCurrency,
            int localInvalidCurrency,
            int upstreamCancelledOrders,
            int upstreamRefundStatusOrders,
            int upstreamPaidUnexpectedStatus,
            List<CurrencyTotals> comparableGrossByCurrency,
            List<String> warnings) {}

    private static final class Accumulated {
        int count;
        BigDecimal upstream = BigDecimal.ZERO;
        BigDecimal local = BigDecimal.ZERO;

        void add(BigDecimal original, BigDecimal stored) {
            count++;
            upstream = upstream.add(original);
            local = local.add(stored);
        }
    }

    public Report audit(int pageSize, int maxPages) {
        if (pageSize < 1 || pageSize > 100 || maxPages < 1 || maxPages > MAX_PAGES) {
            throw new NuvemshopException(HttpStatus.BAD_REQUEST, "INVALID_PAGINATION",
                    "pageSize deve estar entre 1 e 100, maxPages entre 1 e 20.");
        }
        Set<String> seen = new HashSet<>();
        TreeMap<String,Accumulated> groups = new TreeMap<>();
        Long expected = null;
        boolean totalsPresent = true, totalsConsistent = true, exhausted = false;
        int pages = 0, fetched = 0, duplicates = 0, missing = 0;
        int upstreamEligible = 0, localEligible = 0, mutuallyEligible = 0, comparable = 0;
        int orderStateDrift = 0, paymentStateDrift = 0, eligibilityDrift = 0;
        int currencyDrift = 0, amountDrift = 0;
        int upstreamBadAmount = 0, localBadAmount = 0, upstreamBadCurrency = 0, localBadCurrency = 0;
        int cancelled = 0, refundLike = 0, unexpectedPaidState = 0;

        for (int currentPage = 1; currentPage <= maxPages; currentPage++) {
            var page = shop.orderValueAuditOrders(currentPage, pageSize);
            pages++;
            if (page.data() == null || page.data().size() > pageSize
                    || (page.data().isEmpty() && page.hasNext())) {
                throw new NuvemshopException(HttpStatus.BAD_GATEWAY, "NUVEMSHOP_INVALID_PAGINATION",
                        "A listagem retornou paginacao inconsistente.");
            }
            fetched = Math.addExact(fetched, page.data().size());
            if (page.totalCount() == null) totalsPresent = false;
            else if (page.totalCount() < 0) totalsConsistent = false;
            else if (expected == null) expected = page.totalCount();
            else if (!expected.equals(page.totalCount())) totalsConsistent = false;

            var rows = new ArrayList<Map<String,Object>>();
            var ids = new ArrayList<String>();
            for (var row : page.data()) {
                if (row == null || row.get("id") == null
                        || !row.get("id").toString().matches("[0-9]{1,30}"))
                    throw invalidData();
                String id = row.get("id").toString();
                if (!seen.add(id)) {
                    duplicates++;
                    continue;
                }
                rows.add(row);
                ids.add(id);
            }

            // One parameterized query per upstream page; do not expose order IDs.
            var locals = repository.find(ids);
            for (int i = 0; i < rows.size(); i++) {
                var row = rows.get(i);
                String orderStatus = safeStatus(row.get("status"));
                String paymentStatus = safeStatus(row.get("payment_status"));
                if (orderStatus == null || paymentStatus == null) throw invalidData();
                var local = locals.get(ids.get(i));

                boolean eligible = "paid".equals(paymentStatus)
                        && ("open".equals(orderStatus) || "closed".equals(orderStatus));
                if (eligible) upstreamEligible++;
                if ("cancelled".equals(orderStatus)) cancelled++;
                if ("refunded".equals(paymentStatus) || "partially_refunded".equals(paymentStatus))
                    refundLike++;
                if ("paid".equals(paymentStatus) && !eligible && !"cancelled".equals(orderStatus))
                    unexpectedPaidState++;

                BigDecimal originalTotal = money(row.get("total"));
                String originalCurrency = currency(row.get("currency"));
                if (originalTotal == null) upstreamBadAmount++;
                if (originalCurrency == null) upstreamBadCurrency++;
                if (local == null) {
                    missing++;
                    continue;
                }
                if (local.eligiblePaidSale()) localEligible++;
                if (!orderStatus.equals(local.orderStatus())) orderStateDrift++;
                if (!paymentStatus.equals(local.paymentStatus())) paymentStateDrift++;
                if (eligible != local.eligiblePaidSale()) eligibilityDrift++;
                if (eligible && local.eligiblePaidSale()) mutuallyEligible++;

                BigDecimal storedTotal = money(local.total());
                String storedCurrency = currency(local.currency());
                if (storedTotal == null) localBadAmount++;
                if (storedCurrency == null) localBadCurrency++;
                if (originalCurrency != null && storedCurrency != null
                        && !originalCurrency.equals(storedCurrency)) currencyDrift++;
                if (originalTotal != null && storedTotal != null
                        && originalCurrency != null && originalCurrency.equals(storedCurrency)
                        && originalTotal.compareTo(storedTotal) != 0) amountDrift++;

                // Compare gross totals ONLY for same-state eligible pairs in the
                // same currency with both amounts valid. No mixed-currency sums.
                if (eligible && local.eligiblePaidSale()
                        && orderStatus.equals(local.orderStatus())
                        && paymentStatus.equals(local.paymentStatus())
                        && originalCurrency != null && originalCurrency.equals(storedCurrency)
                        && originalTotal != null && storedTotal != null) {
                    comparable++;
                    groups.computeIfAbsent(originalCurrency, c -> new Accumulated())
                            .add(originalTotal, storedTotal);
                }
            }
            if (!page.hasNext()) {
                exhausted = true;
                break;
            }
        }

        boolean reconciled = exhausted && totalsPresent && totalsConsistent
                && expected != null && expected == seen.size() && duplicates == 0;
        var perCurrency = new ArrayList<CurrencyTotals>();
        for (var e : groups.entrySet()) {
            var x = e.getValue();
            perCurrency.add(new CurrencyTotals(e.getKey(), x.count,
                    x.upstream, x.local, x.upstream.subtract(x.local)));
        }

        var warnings = new ArrayList<String>();
        warnings.add("GROSS_ORDER_VALUES_NOT_PAYMENT_RECEIPTS");
        warnings.add("REFUNDS_AND_TRANSACTIONS_NOT_VERIFIED");
        warnings.add("UPSTREAM_LIST_NOT_POINT_IN_TIME");
        warnings.add("SNAPSHOT_STATUS_MAY_BE_STALE");
        warnings.add("NO_ORDER_TO_META_CAMPAIGN_ASSOCIATION");
        if (!reconciled) warnings.add("UPSTREAM_PAGINATION_NOT_RECONCILED");
        if (missing > 0) warnings.add("ORDER_IDS_MISSING_LOCALLY");
        if (orderStateDrift > 0 || paymentStateDrift > 0)
            warnings.add("ORDER_OR_PAYMENT_STATUS_DIFFERS");
        if (eligibilityDrift > 0) warnings.add("ELIGIBILITY_DIFFERS");
        if (currencyDrift > 0) warnings.add("CURRENCY_DIFFERS");
        if (amountDrift > 0) warnings.add("GROSS_ORDER_TOTAL_DIFFERS");
        if (upstreamBadAmount > 0 || localBadAmount > 0
                || upstreamBadCurrency > 0 || localBadCurrency > 0)
            warnings.add("INVALID_OR_MISSING_MONETARY_FIELDS");
        if (unexpectedPaidState > 0) warnings.add("PAID_ORDER_STATE_NOT_CLASSIFIABLE");
        return new Report(reconciled, false, false, pages, fetched, seen.size(),
                totalsPresent && totalsConsistent ? expected : null, duplicates,
                missing, upstreamEligible, localEligible, mutuallyEligible,
                comparable, orderStateDrift, paymentStateDrift, eligibilityDrift,
                currencyDrift, amountDrift, upstreamBadAmount, localBadAmount,
                upstreamBadCurrency, localBadCurrency, cancelled, refundLike,
                unexpectedPaidState, List.copyOf(perCurrency), List.copyOf(warnings));
    }

    private static NuvemshopException invalidData() {
        return new NuvemshopException(HttpStatus.BAD_GATEWAY, "NUVEMSHOP_INVALID_ORDER_DATA",
                "A listagem retornou campos de pedido invalidos.");
    }

    private static String safeStatus(Object raw) {
        return raw instanceof String s && s.matches("[a-z_]{1,32}") ? s : null;
    }

    private static String currency(Object raw) {
        if (!(raw instanceof String s)) return null;
        String candidate = s.strip();
        return candidate.matches("[A-Z]{3}") ? candidate : null;
    }

    private static BigDecimal money(Object raw) {
        if (!(raw instanceof Number || raw instanceof String)) return null;
        try {
            String input = raw.toString();
            if (input.length() > 40) return null;
            BigDecimal amount = new BigDecimal(input).setScale(2, RoundingMode.UNNECESSARY);
            if (amount.signum() < 0 || amount.precision() > 18) return null;
            return amount;
        } catch (RuntimeException e) {
            return null;
        }
    }
}
