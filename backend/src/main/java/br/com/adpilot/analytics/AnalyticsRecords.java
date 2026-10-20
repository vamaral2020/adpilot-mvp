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

    public record Meta(String accountId, String campaignId, LocalDate day,
            BigDecimal spend, long impressions, long clicks,
            BigDecimal purchases, BigDecimal attributedRevenue, String currency) {}
}
