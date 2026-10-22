package br.com.adpilot.analytics;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Minimal aggregate-only decision evidence. No user/order identifiers or ad writes. */
public final class RecommendationModels {
    private RecommendationModels() {}

    public record Request(LocalDate since, LocalDate until,
            BigDecimal targetRoas, BigDecimal targetCpa) {}

    public record DailyCampaign(String accountId, String campaignId, String campaignName,
            String currency, LocalDate day, BigDecimal spend, long impressions,
            long clicks, BigDecimal attributedPurchases, BigDecimal attributedRevenue) {}

    public record SyncState(String lastStatus, Instant lastSuccess) {}

    public record Metric(String name, BigDecimal value, String limitation) {}

    public record Advice(String ruleCode, int ruleVersion, String accountId,
            String campaignId, String campaignName, String currency,
            String priority, String confidence, String actionCode,
            String title, String justification, List<Metric> evidence,
            List<String> limitations) {}

    public record Report(LocalDate since, LocalDate until, BigDecimal targetRoas,
            BigDecimal targetCpa, boolean coverageVerified, Instant generatedAt,
            String metaSyncStatus, Instant lastMetaSyncSuccess,
            List<String> warnings, List<Advice> recommendations) {}

    public record Stored(UUID evaluationId, Report report) {}
}
