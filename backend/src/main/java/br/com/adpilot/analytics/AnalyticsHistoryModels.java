package br.com.adpilot.analytics;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Contracts shared by the HTTP adapter, application service and data access. */
public final class AnalyticsHistoryModels {
    private AnalyticsHistoryModels() {}

    public record MetaDay(LocalDate date, String currency, BigDecimal spend,
            long impressions, long clicks, BigDecimal attributedPurchases,
            BigDecimal attributedRevenue) {}
    public record StoreDay(LocalDate date, String currency, long paidOrders,
            BigDecimal paidRevenue) {}
    /** Aggregated per Meta ad account and campaign, not linked to individual store orders. */
    public record CampaignResult(String accountId, String campaignId, String name,
            String currency, BigDecimal spend, long impressions, long clicks,
            BigDecimal attributedPurchases, BigDecimal attributedRevenue) {}
    public record SourceState(String provider, String resource, String lastStatus,
            Instant lastSuccess, Instant cursorAt) {}
    /** Explicit provider window receipt, not a guarantee of final attribution accuracy. */
    public record MetaWindow(String accountId, LocalDate from, LocalDate through,
            Instant completedAt) {}
    public record ShopWindowSummary(long successfulUpdateWindows, Instant lastSuccess) {}
    public record MetaCoverage(String status, String accountId, int requestedDays,
            int observedDays, int datesWithRows, int datesWithoutRows,
            List<LocalDate> unobservedDates, List<LocalDate> observedWithoutRows,
            Instant lastSuccess) {}
    public record StoreCoverage(String status, long successfulUpdateWindows,
            Instant lastSuccess) {}
    public record Coverage(MetaCoverage meta, StoreCoverage store) {}
    public record History(LocalDate since, LocalDate until, List<MetaDay> meta,
            List<StoreDay> store, List<CampaignResult> campaigns, List<SourceState> sources, boolean complete,
            List<String> warnings, Coverage coverage) {}
}
