package br.com.adpilot.analytics;

import br.com.adpilot.integrations.nuvemshop.NuvemshopClient;
import br.com.adpilot.integrations.nuvemshop.NuvemshopException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Bounded live Nuvemshop read compared by order ID to the local snapshot table.
 * No amount/transaction check, no cursor writes, and no Meta attribution.
 */
@Service
public class OrderSnapshotDiagnosticService {
    private static final int MAX_PAGES = 20;
    private static final DateTimeFormatter BASIC_OFFSET =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssZ");
    private final NuvemshopClient shop;
    private final OrderSnapshotDiagnosticRepository repository;

    public OrderSnapshotDiagnosticService(NuvemshopClient shop,
            OrderSnapshotDiagnosticRepository repository) {
        this.shop = shop;
        this.repository = repository;
    }

    /** Internal only, never included in the public report. */
    public record Snapshot(String id, String paymentStatus, Instant paidAt) {}

    public record Report(
            boolean upstreamPaginationReconciled,
            boolean financiallyVerified,
            boolean ordersMatchedToCampaigns,
            int pagesFetched,
            int upstreamRowsFetched,
            int uniqueUpstreamOrderIds,
            Long upstreamReportedTotal,
            int duplicateUpstreamIds,
            int paidOrdersChecked,
            int paidOrderIdsFoundLocally,
            int paidOrderIdsMissingLocally,
            int locallyNotPaid,
            int upstreamPaidAtMissing,
            int upstreamPaidAtInvalid,
            int localPaidAtMissing,
            int paidAtEqual,
            int paidAtDifferent,
            List<String> warnings) {}

    public Report inspect(int pageSize, int maxPages) {
        if (pageSize < 1 || pageSize > 100 || maxPages < 1 || maxPages > MAX_PAGES)
            throw new NuvemshopException(HttpStatus.BAD_REQUEST, "INVALID_PAGINATION",
                    "pageSize deve estar entre 1 e 100, maxPages entre 1 e 20.");

        Set<String> seen = new HashSet<>();
        Long expectedTotal = null;
        boolean allTotalsPresent = true;
        boolean totalsConsistent = true;
        boolean exhausted = false;
        int pages = 0, fetched = 0, duplicates = 0;
        int paidChecked = 0, present = 0, absent = 0, localNotPaid = 0;
        int upstreamMissingDate = 0, upstreamInvalidDate = 0;
        int localMissingDate = 0, equalDates = 0, differentDates = 0;

        for (int p = 1; p <= maxPages; p++) {
            var page = shop.paymentAuditOrders(p, pageSize);
            pages++;
            if (page.data() == null || (page.data().isEmpty() && page.hasNext()))
                throw new NuvemshopException(HttpStatus.BAD_GATEWAY,
                        "NUVEMSHOP_INVALID_PAGINATION",
                        "A listagem retornou paginacao inconsistente.");

            fetched = Math.addExact(fetched, page.data().size());
            if (page.totalCount() == null) allTotalsPresent = false;
            else if (page.totalCount() < 0) totalsConsistent = false;
            else if (expectedTotal == null) expectedTotal = page.totalCount();
            else if (!expectedTotal.equals(page.totalCount())) totalsConsistent = false;

            var paidRows = new ArrayList<Map<String, Object>>();
            var paidIds = new ArrayList<String>();
            for (var row : page.data()) {
                if (row == null || row.get("id") == null
                        || !row.get("id").toString().matches("[0-9]{1,30}"))
                    throw new NuvemshopException(HttpStatus.BAD_GATEWAY,
                            "NUVEMSHOP_INVALID_ORDER_DATA",
                            "A listagem retornou identificador de pedido invalido.");
                String id = row.get("id").toString();
                if (!seen.add(id)) {
                    duplicates++;
                    continue;
                }
                if ("paid".equals(row.get("payment_status"))) {
                    paidRows.add(row);
                    paidIds.add(id);
                }
            }

            // One parameterized, workspace-scoped query per page, never an N+1 scan.
            var local = repository.find(paidIds);
            for (int i = 0; i < paidRows.size(); i++) {
                paidChecked++;
                var row = paidRows.get(i);
                var snapshot = local.get(paidIds.get(i));
                if (snapshot == null) {
                    absent++;
                } else {
                    present++;
                    if (!"paid".equals(snapshot.paymentStatus())) localNotPaid++;
                    if (snapshot.paidAt() == null) localMissingDate++;
                }

                Object rawDate = row.get("paid_at");
                if (rawDate == null || rawDate instanceof String s && s.isBlank()) {
                    upstreamMissingDate++;
                    continue;
                }
                Instant upstreamDate = parseDate(rawDate);
                if (upstreamDate == null) {
                    upstreamInvalidDate++;
                    continue;
                }
                // A list without paid_at can coexist with DETAIL-resolved local paid_at.
                // Never flag that case as a date disagreement.
                if (snapshot != null && snapshot.paidAt() != null) {
                    if (upstreamDate.equals(snapshot.paidAt())) equalDates++;
                    else differentDates++;
                }
            }
            if (!page.hasNext()) {
                exhausted = true;
                break;
            }
        }

        boolean paginationReconciled = exhausted && allTotalsPresent && totalsConsistent
                && expectedTotal != null && seen.size() == expectedTotal && duplicates == 0;
        var warnings = new ArrayList<String>();
        warnings.add("LIVE_ORDER_LIST_NOT_POINT_IN_TIME");
        warnings.add("SNAPSHOTS_ARE_UPDATED_AT_NOT_PAYMENT_EVENT_LEDGER");
        warnings.add("NO_AMOUNT_REFUND_OR_TRANSACTION_VERIFICATION");
        warnings.add("NO_ORDER_TO_META_CAMPAIGN_ASSOCIATION");
        if (!paginationReconciled) warnings.add("UPSTREAM_PAGINATION_NOT_RECONCILED");
        if (absent > 0) warnings.add("PAID_ORDER_IDS_MISSING_FROM_LOCAL_SNAPSHOTS");
        if (localNotPaid > 0) warnings.add("LOCAL_PAYMENT_STATUS_DIFFERS");
        if (upstreamMissingDate > 0) warnings.add("UPSTREAM_LIST_PAID_AT_MISSING");
        if (upstreamInvalidDate > 0) warnings.add("UPSTREAM_LIST_PAID_AT_INVALID");
        if (localMissingDate > 0) warnings.add("LOCAL_PAID_AT_MISSING");
        if (differentDates > 0) warnings.add("PAID_AT_DISAGREEMENT");
        return new Report(paginationReconciled, false, false, pages, fetched, seen.size(),
                totalsConsistent ? expectedTotal : null, duplicates,
                paidChecked, present, absent, localNotPaid,
                upstreamMissingDate, upstreamInvalidDate, localMissingDate,
                equalDates, differentDates, List.copyOf(warnings));
    }

    private static Instant parseDate(Object value) {
        if (!(value instanceof String text)) return null;
        try {
            return OffsetDateTime.parse(text, DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                    .toInstant();
        } catch (DateTimeParseException ignored) {
            try {
                return OffsetDateTime.parse(text, BASIC_OFFSET).toInstant();
            } catch (DateTimeParseException invalid) {
                return null;
            }
        }
    }
}
