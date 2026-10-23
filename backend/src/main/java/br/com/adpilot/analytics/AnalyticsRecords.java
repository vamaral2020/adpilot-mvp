package br.com.adpilot.analytics;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** Strict, minimal snapshots: no contacts, IP addresses, event IDs or tokens. */
public final class AnalyticsRecords {
    private AnalyticsRecords() {}

    public record Order(String id, String number, String status, String paymentStatus,
            Instant paidAt, String paidDateSource, Instant createdAt,
            Instant updatedAt, BigDecimal total, String currency, boolean eligibleSale) {}

    public record Campaign(String accountId, String id, LocalDate observedDay,
            String name, String objective, String status, String effectiveStatus,
            Instant observedAt) {}

    public record Meta(String accountId, String campaignId, LocalDate day,
            BigDecimal spend, long impressions, long clicks,
            BigDecimal purchases, BigDecimal attributedRevenue, String currency,
            BigDecimal linkClicks, BigDecimal landingPageViews,
            BigDecimal postEngagements, BigDecimal addToCarts) {
        public Meta(String accountId, String campaignId, LocalDate day,
                BigDecimal spend, long impressions, long clicks,
                BigDecimal purchases, BigDecimal attributedRevenue, String currency) {
            this(accountId,campaignId,day,spend,impressions,clicks,purchases,
                    attributedRevenue,currency,null,null,null,null);
        }
    }
}
