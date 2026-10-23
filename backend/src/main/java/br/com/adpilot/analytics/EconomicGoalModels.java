package br.com.adpilot.analytics;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Manually entered, versioned assumptions, never inferred from a Meta campaign. */
public final class EconomicGoalModels {
    private EconomicGoalModels() {}

    public record Input(String productLabel, Long priceCents, Long productCostCents,
            Integer taxBasisPoints, Integer paymentBasisPoints, Long paymentFixedCents,
            Long shippingSubsidyCents, Long packagingCents, Long otherCostsCents,
            Integer desiredProfitBasisPoints) {}

    public record Goal(UUID id, String productLabel, long priceCents,
            long productCostCents, int taxBasisPoints, int paymentBasisPoints,
            long paymentFixedCents, long shippingSubsidyCents, long packagingCents,
            long otherCostsCents, int desiredProfitBasisPoints,
            long targetCpaCents, int targetRoasHundredths, Instant createdAt) {}

    public record LinkRequest(UUID goalId, Boolean singleProductConfirmed) {}

    public record Campaign(String accountId, String campaignId, String campaignName,
            String currency, UUID linkedGoalId, String linkedProductLabel,
            Long linkedCpaCents, Integer linkedRoasHundredths, Instant linkedAt) {}

    public record Catalog(List<Goal> goals, List<Campaign> campaigns) {}

    public record Result(String status, String accountId, String campaignId, UUID goalId) {}
}
