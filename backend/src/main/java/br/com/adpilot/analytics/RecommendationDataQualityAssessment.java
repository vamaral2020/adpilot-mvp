package br.com.adpilot.analytics;

import br.com.adpilot.analytics.AnalyticsHistoryModels.MetaWindow;
import br.com.adpilot.analytics.RecommendationModels.DataQuality;
import br.com.adpilot.analytics.RecommendationModels.DailyCampaign;
import br.com.adpilot.analytics.RecommendationModels.Request;
import br.com.adpilot.analytics.RecommendationModels.SyncState;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Conservative quality gate based on successfully completed Meta API windows.
 * A recorded empty day is not a measured zero. Even a complete query window
 * does NOT make its attribution final or prove any Nuvemshop sale.
 */
@Component
public class RecommendationDataQualityAssessment {
    static final Duration MAX_SYNC_AGE = Duration.ofHours(24);
    static final Duration MAX_CLOCK_SKEW = Duration.ofMinutes(5);

    public DataQuality assess(Request request, List<DailyCampaign> rows,
            List<MetaWindow> windows, SyncState sync, Instant now) {
        int requested = Math.toIntExact(ChronoUnit.DAYS.between(
                request.since(), request.until()) + 1);
        var accountIds = new HashSet<String>();
        Instant lastReceipt = null;
        for (var w : windows) {
            if (w.accountId() != null) accountIds.add(w.accountId());
            if (w.completedAt() != null && (lastReceipt == null ||
                    w.completedAt().isAfter(lastReceipt))) lastReceipt = w.completedAt();
        }
        var dataAccounts = new HashSet<String>();
        var rowDays = new HashSet<LocalDate>();
        for (var row : rows) {
            if (row.accountId() != null) dataAccounts.add(row.accountId());
            if (row.day() != null && !row.day().isBefore(request.since())
                    && !row.day().isAfter(request.until())) rowDays.add(row.day());
        }

        boolean ambiguous = accountIds.size() > 1 || dataAccounts.size() > 1;
        String account = accountIds.size() == 1 ? accountIds.iterator().next() : null;
        boolean mismatch = account != null && !dataAccounts.isEmpty()
                && !dataAccounts.equals(Set.of(account));
        Set<LocalDate> collected = new HashSet<>();
        if (!ambiguous && !mismatch && account != null) {
            for (var window : windows) {
                if (!account.equals(window.accountId()) || window.from() == null
                        || window.through() == null || window.from().isAfter(window.through()))
                    continue;
                LocalDate from = window.from().isBefore(request.since())
                        ? request.since() : window.from();
                LocalDate through = window.through().isAfter(request.until())
                        ? request.until() : window.through();
                for (var day = from; !day.isAfter(through); day = day.plusDays(1))
                    collected.add(day);
            }
        }
        var uncollected = new ArrayList<LocalDate>();
        var noRows = new ArrayList<LocalDate>();
        for (LocalDate day = request.since(); !day.isAfter(request.until()); day = day.plusDays(1)) {
            if (!collected.contains(day)) uncollected.add(day);
            else if (!rowDays.contains(day)) noRows.add(day);
        }

        boolean fresh = "SUCCEEDED".equals(sync.lastStatus())
                && recent(sync.lastSuccess(), now)
                && recent(lastReceipt, now);
        boolean currentDay = !request.until().isBefore(
                now.atZone(ZoneOffset.UTC).toLocalDate());
        String status = ambiguous ? "MULTIPLE_ACCOUNTS"
                : mismatch ? "ACCOUNT_MISMATCH"
                : windows.isEmpty() ? "NO_COLLECTION_RECEIPT"
                : !uncollected.isEmpty() ? "PARTIAL_WINDOWS"
                : currentDay ? "CURRENT_DAY_INCOMPLETE"
                : "WINDOWS_OBSERVED";
        // Fresh and fully observed only permits cautious *investigation* rules.
        // Financial completeness and account attribution are never certified.
        boolean eligible = status.equals("WINDOWS_OBSERVED") && fresh;
        return new DataQuality(status,requested,collected.size(),rowDays.size(),noRows.size(),
                List.copyOf(uncollected),List.copyOf(noRows),lastReceipt,fresh,eligible);
    }

    private static boolean recent(Instant value, Instant now) {
        if (value == null) return false;
        Duration age = Duration.between(value,now);
        return age.compareTo(MAX_SYNC_AGE) <= 0 && age.compareTo(MAX_CLOCK_SKEW.negated()) >= 0;
    }
}
