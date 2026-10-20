package br.com.adpilot.integrations.meta;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;

/**
 * Valores monetarios na moeda da conta Meta. CTR em percentual (0 a 100);
 * CPC e ROAS nulos quando o denominador for zero. Atribuicoes da Meta
 * nao equivalem a vendas liquidadas na plataforma de e-commerce.
 */
public record MetaInsightsMetrics(BigDecimal spend, long impressions, long clicks,
        BigDecimal ctr, BigDecimal cpc, BigDecimal purchases,
        BigDecimal purchaseValue, BigDecimal roas) {

    /** Single Meta Insight row, using the exact same taxonomy precedence as summary. */
    public static MetaInsightsMetrics fromRow(Map<String, Object> row) {
        var accumulator = new Accumulator();
        accumulator.add(row);
        return accumulator.snapshot();
    }

    static final class Accumulator {
        private BigDecimal spend = BigDecimal.ZERO;
        private long impressions;
        private long clicks;
        private BigDecimal purchases = BigDecimal.ZERO;
        private BigDecimal purchaseValue = BigDecimal.ZERO;

        void add(Map<String, Object> row) {
            try {
                spend = spend.add(decimal(row.get("spend")));
                impressions = Math.addExact(impressions, count(row.get("impressions")));
                clicks = Math.addExact(clicks, count(row.get("clicks")));
                purchases = purchases.add(actionValue(row.get("actions"),
                        "purchase", "omni_purchase",
                        "offsite_conversion.fb_pixel_purchase", "onsite_web_purchase"));
                purchaseValue = purchaseValue.add(actionValue(row.get("action_values"),
                        "purchase", "omni_purchase",
                        "offsite_conversion.fb_pixel_purchase", "onsite_web_purchase"));
            } catch (NumberFormatException | ArithmeticException ex) {
                throw new MetaApiException(HttpStatus.BAD_GATEWAY, "META_INVALID_METRICS",
                        "A Meta retornou valores numericos invalidos nas metricas.");
            }
        }

        MetaInsightsMetrics snapshot() {
            return new MetaInsightsMetrics(spend, impressions, clicks,
                    impressions == 0 ? null
                            : BigDecimal.valueOf(clicks).multiply(BigDecimal.valueOf(100))
                                .divide(BigDecimal.valueOf(impressions), 4, RoundingMode.HALF_UP),
                    clicks == 0 ? null
                            : spend.divide(BigDecimal.valueOf(clicks), 4, RoundingMode.HALF_UP),
                    purchases, purchaseValue,
                    spend.signum() == 0 ? null
                            : purchaseValue.divide(spend, 4, RoundingMode.HALF_UP));
        }
    }

    private static long count(Object value) {
        return value == null ? 0L : new BigDecimal(value.toString()).longValueExact();
    }

    private static BigDecimal decimal(Object value) {
        return value == null ? BigDecimal.ZERO : new BigDecimal(value.toString());
    }

    /**
     * As acoes reportam frequentemente o mesmo evento em varias taxonomias.
     * Usamos apenas a primeira metrica encontrada (priorizando "purchase")
     * para nao somar purchase + omni_purchase + pixel_purchase.
     */
    private static BigDecimal actionValue(Object raw, String... preferredTypes) {
        if (!(raw instanceof List<?> actions)) return BigDecimal.ZERO;
        for (String type : preferredTypes) {
            for (Object rawAction : actions) {
                if (rawAction instanceof Map<?, ?> action
                        && type.equals(action.get("action_type"))) {
                    return decimal(action.get("value"));
                }
            }
        }
        return BigDecimal.ZERO;
    }
}
