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
    public record SourceState(String provider, String resource, String lastStatus,
            Instant lastSuccess, Instant cursorAt) {}
    public record History(LocalDate since, LocalDate until, List<MetaDay> meta,
            List<StoreDay> store, List<SourceState> sources, boolean complete,
            List<String> warnings) {}
}
