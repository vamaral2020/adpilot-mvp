package br.com.adpilot.analytics;

import br.com.adpilot.integrations.nuvemshop.NuvemshopClient;
import br.com.adpilot.integrations.nuvemshop.NuvemshopException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Conservative, on-demand inspection of Order VALUE changes. These entries
 * are not Payment App Transactions and provide no settlement guarantee.
 * Selection is intentionally risk-weighted, not a statistical sample.
 */
@Service
public class OrderValueHistoryDiagnosticService {
    private static final int MAX_PAGES = 20;
    private static final int MAX_HISTORY_LOOKUPS = 50;
    private final NuvemshopClient client;

    public OrderValueHistoryDiagnosticService(NuvemshopClient client) {
        this.client = client;
    }

    private record Candidate(String id, String orderStatus, String paymentStatus) {
        int priority() {
            if ("refunded".equals(paymentStatus) || "partially_refunded".equals(paymentStatus))
                return 0;
            if ("cancelled".equals(orderStatus)) return 1;
            if ("paid".equals(paymentStatus)) return 2;
            return 3;
        }
    }

    public record Report(
            boolean upstreamPaginationReconciled,
            boolean entireOrderPopulationExamined,
            boolean financiallyVerified,
            boolean paymentSettlementVerified,
            boolean ordersMatchedToCampaigns,
            int pagesFetched,
            int upstreamOrdersFetched,
            int uniqueUpstreamOrders,
            Long upstreamReportedTotal,
            int duplicateOrderIds,
            int maxHistoryOrders,
            int historiesQueried,
            int selectedRefundStatusOrders,
            int selectedCancelledOrders,
            int selectedPaidOrders,
            int ordersWithEmptyValueHistory,
            int ordersWithPositivePaidMovement,
            int ordersWithNegativePaidMovement,
            int ordersWithInvalidValueHistory,
            int paidPositiveEvents,
            int paidNegativeEvents,
            int paidZeroEvents,
            int pendingEvents,
            int cancelledEvents,
            int unknownStatusEvents,
            int invalidPaidDiffEvents,
            int nonPaidNonzeroEvents,
            List<String> warnings) {}

    public Report inspect(int pageSize, int maxPages, int maxHistoryOrders) {
        if (pageSize < 1 || pageSize > 100 || maxPages < 1 || maxPages > MAX_PAGES
                || maxHistoryOrders < 1 || maxHistoryOrders > MAX_HISTORY_LOOKUPS)
            throw new NuvemshopException(HttpStatus.BAD_REQUEST, "INVALID_PAGINATION",
                    "pageSize 1..100, maxPages 1..20 e maxHistoryOrders 1..50.");

        var candidates = new ArrayList<Candidate>();
        Set<String> seen = new HashSet<>();
        Long expected = null;
        boolean totalsPresent = true, totalsConsistent = true, exhausted = false;
        int pages = 0, fetched = 0, duplicates = 0;

        // List orders without a date filter to avoid assuming that an
        // updated_at window covers the complete history of payment events.
        for (int p = 1; p <= maxPages; p++) {
            var page = client.orderValueAuditOrders(p,pageSize);
            pages++;
            if (page.data() == null || page.data().size() > pageSize
                    || page.data().isEmpty() && page.hasNext())
                throw invalidPagination();
            fetched = Math.addExact(fetched,page.data().size());
            if (page.totalCount() == null) totalsPresent = false;
            else if (page.totalCount() < 0) totalsConsistent = false;
            else if (expected == null) expected = page.totalCount();
            else if (!expected.equals(page.totalCount())) totalsConsistent = false;

            for (Map<String,Object> order : page.data()) {
                if (order == null || order.get("id") == null
                        || !order.get("id").toString().matches("[0-9]{1,30}"))
                    throw invalidData();
                String id = order.get("id").toString();
                String orderStatus = safeStatus(order.get("status"));
                String paymentStatus = safeStatus(order.get("payment_status"));
                if (orderStatus == null || paymentStatus == null) throw invalidData();
                if (!seen.add(id)) {
                    duplicates++;
                    continue;
                }
                candidates.add(new Candidate(id,orderStatus,paymentStatus));
            }
            if (!page.hasNext()) {
                exhausted = true;
                break;
            }
        }

        boolean paginationReconciled = exhausted && totalsPresent && totalsConsistent
                && expected != null && expected == seen.size() && duplicates == 0;

        // Explicit ordering, preferentially inspecting refunds and cancellations,
        // then paid orders, without exposing IDs or implying population-wide rates.
        candidates.sort(Comparator.comparingInt(Candidate::priority));
        int selected = Math.min(candidates.size(),maxHistoryOrders);
        int selectedRefund = 0, selectedCancelled = 0, selectedPaid = 0;
        int empty = 0, withPositive = 0, withNegative = 0, invalidOrders = 0;
        int positive = 0, negative = 0, zero = 0;
        int pending = 0, cancelled = 0, unknown = 0, invalidDiff = 0, nonPaidNonzero = 0;

        for (int i = 0; i < selected; i++) {
            var candidate = candidates.get(i);
            if (candidate.priority() == 0) selectedRefund++;
            if ("cancelled".equals(candidate.orderStatus())) selectedCancelled++;
            if ("paid".equals(candidate.paymentStatus())) selectedPaid++;

            // Fail closed for 401/403/404/429 and transport failures. 404 never
            // means that there were zero payments or refunds on an order.
            var history = client.orderValueHistory(Long.parseLong(candidate.id()));
            if (history == null) throw invalidData();
            if (history.isEmpty()) empty++;
            boolean sawPositive = false, sawNegative = false, invalid = false;
            for (var event : history) {
                if (event == null) throw invalidData();
                Object rawStatus = event.get("status");
                Object rawDelta = event.get("total_paid_diff");
                BigDecimal delta = signedAmount(rawDelta);
                if (delta == null) {
                    invalidDiff++;
                    invalid = true;
                }
                if ("PAID".equals(rawStatus)) {
                    if (delta != null) {
                        if (delta.signum() > 0) {
                            positive++;
                            sawPositive = true;
                        } else if (delta.signum() < 0) {
                            negative++;
                            sawNegative = true;
                        } else zero++;
                    }
                } else if ("PENDING".equals(rawStatus) || "CANCELLED".equals(rawStatus)) {
                    if ("PENDING".equals(rawStatus)) pending++;
                    else cancelled++;
                    if (delta != null && delta.signum() != 0) {
                        nonPaidNonzero++;
                        invalid = true;
                    }
                } else {
                    unknown++;
                    invalid = true;
                }
            }
            if (sawPositive) withPositive++;
            if (sawNegative) withNegative++;
            if (invalid) invalidOrders++;
        }

        var warnings = new ArrayList<String>();
        warnings.add("ORDER_VALUE_HISTORY_NOT_PAYMENT_TRANSACTION");
        warnings.add("NO_PAYMENT_PROVIDER_SETTLEMENT_OR_FEE_DATA");
        warnings.add("EMPTY_HISTORY_DOES_NOT_PROVE_NO_REFUNDS");
        warnings.add("RISK_WEIGHTED_SELECTION_NOT_REPRESENTATIVE");
        warnings.add("NO_ORDER_TO_META_CAMPAIGN_ASSOCIATION");
        if (!paginationReconciled) warnings.add("UPSTREAM_PAGINATION_NOT_RECONCILED");
        if (selected < seen.size()) warnings.add("HISTORY_LOOKUP_LIMITED_SAMPLE");
        if (invalidOrders > 0) warnings.add("HISTORY_VALUES_UNINTERPRETABLE");
        if (empty > 0) warnings.add("EMPTY_ORDER_VALUE_HISTORIES_OBSERVED");
        if (negative > 0) warnings.add("NEGATIVE_PAID_MOVEMENTS_OBSERVED");
        return new Report(paginationReconciled,
                paginationReconciled && selected == seen.size(),
                false,false,false,
                pages,fetched,seen.size(),
                totalsConsistent && totalsPresent ? expected : null,duplicates,
                maxHistoryOrders,selected,selectedRefund,selectedCancelled,selectedPaid,
                empty,withPositive,withNegative,invalidOrders,
                positive,negative,zero,pending,cancelled,unknown,invalidDiff,
                nonPaidNonzero,List.copyOf(warnings));
    }

    private static String safeStatus(Object value) {
        return value instanceof String s && s.matches("[a-z_]{1,32}") ? s : null;
    }

    private static BigDecimal signedAmount(Object raw) {
        if (!(raw instanceof String || raw instanceof Number)) return null;
        String text = raw.toString();
        // No currency conversion or decimals emitted. Use only the sign.
        if (!text.matches("-?[0-9]{1,18}(?:\\.[0-9]{1,4})?")) return null;
        try {
            return new BigDecimal(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static NuvemshopException invalidPagination() {
        return new NuvemshopException(HttpStatus.BAD_GATEWAY,"NUVEMSHOP_INVALID_PAGINATION",
                "Paginacao inconsistente para historico de valores.");
    }

    private static NuvemshopException invalidData() {
        return new NuvemshopException(HttpStatus.BAD_GATEWAY,"NUVEMSHOP_INVALID_ORDER_DATA",
                "Dados de pedido ou historico invalidos.");
    }
}
