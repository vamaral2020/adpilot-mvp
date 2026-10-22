package br.com.adpilot.analytics;

import br.com.adpilot.analytics.AnalyticsHistoryModels.Coverage;
import br.com.adpilot.analytics.AnalyticsHistoryModels.MetaCoverage;
import br.com.adpilot.analytics.AnalyticsHistoryModels.MetaDay;
import br.com.adpilot.analytics.AnalyticsHistoryModels.MetaWindow;
import br.com.adpilot.analytics.AnalyticsHistoryModels.ShopWindowSummary;
import br.com.adpilot.analytics.AnalyticsHistoryModels.StoreCoverage;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Explicitly separates days covered by successful Meta API fetches from
 * days with returned rows. Empty but covered days are NOT asserted as zero
 * spend, zero purchases or final attribution results.
 *
 * Store order collection is filtered on updated_at. It cannot establish
 * paid_at-day coverage, even if successive update windows are contiguous.
 */
@Component
public class AnalyticsCoverageAssessment {
    public Coverage assess(LocalDate since, LocalDate until, List<MetaDay> rows,
            List<MetaWindow> fetched, ShopWindowSummary shop) {
        int days = Math.toIntExact(ChronoUnit.DAYS.between(since, until) + 1);
        Set<String> accounts = new HashSet<>();
        Instant lastMetaSuccess = null;
        for (var window : fetched) {
            if (window.accountId() != null) accounts.add(window.accountId());
            if (window.completedAt() != null &&
                    (lastMetaSuccess == null || window.completedAt().isAfter(lastMetaSuccess)))
                lastMetaSuccess = window.completedAt();
        }
        // Multiple Meta account IDs could produce conflicting joined historical
        // aggregates; do not combine their fetch windows into a false guarantee.
        boolean accountAmbiguous = accounts.size() > 1;
        String accountId = accounts.size() == 1 ? accounts.iterator().next() : null;
        var observed = new HashSet<LocalDate>();
        if (!accountAmbiguous && accountId != null) {
            for (var w : fetched) {
                if (!accountId.equals(w.accountId()) || w.from() == null || w.through() == null
                        || w.from().isAfter(w.through())) continue;
                LocalDate first = w.from().isAfter(since) ? w.from() : since;
                LocalDate last = w.through().isBefore(until) ? w.through() : until;
                for (LocalDate day=first;!day.isAfter(last);day=day.plusDays(1))
                    observed.add(day);
            }
        }
        Set<LocalDate> daysWithRows = new HashSet<>();
        for (var row : rows) {
            if (row.date() != null && !row.date().isBefore(since)
                    && !row.date().isAfter(until)) daysWithRows.add(row.date());
        }
        var unobserved = new ArrayList<LocalDate>();
        var withoutRows = new ArrayList<LocalDate>();
        for (LocalDate date=since;!date.isAfter(until);date=date.plusDays(1)) {
            if (!observed.contains(date)) unobserved.add(date);
            else if (!daysWithRows.contains(date)) withoutRows.add(date);
        }

        String metaStatus = accountAmbiguous ? "MULTIPLE_ACCOUNTS"
                : fetched.isEmpty() ? "NO_COLLECTION_RECEIPT"
                : unobserved.isEmpty() ? "WINDOWS_OBSERVED" : "PARTIAL_WINDOWS";
        var meta = new MetaCoverage(metaStatus, accountId, days, observed.size(),
                daysWithRows.size(), withoutRows.size(),
                List.copyOf(unobserved),List.copyOf(withoutRows),lastMetaSuccess);
        var store = new StoreCoverage("UPDATED_AT_ONLY_NOT_PAID_DATE",
                shop == null ? 0L : shop.successfulUpdateWindows(),
                shop == null ? null : shop.lastSuccess());
        return new Coverage(meta,store);
    }
}
