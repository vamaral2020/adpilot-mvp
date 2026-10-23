package br.com.adpilot.integrations.meta;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;

/**
 * Optional action counts for non-sales campaign objectives.
 * Null means that Meta did not report a matching action in this daily row,
 * not that the event definitively did not happen.
 * Aliases represent the same event and must never be added together.
 */
public record MetaOutcomeMetrics(BigDecimal linkClicks, BigDecimal landingPageViews,
        BigDecimal postEngagements, BigDecimal addToCarts) {

    public static MetaOutcomeMetrics fromRow(Map<String, Object> insight) {
        Object actions=insight.get("actions");
        return new MetaOutcomeMetrics(
                preferred(actions, "link_click"),
                preferred(actions, "landing_page_view", "omni_landing_page_view"),
                preferred(actions, "post_engagement"),
                preferred(actions, "add_to_cart", "omni_add_to_cart",
                        "offsite_conversion.fb_pixel_add_to_cart"));
    }

    private static BigDecimal preferred(Object raw, String... alternatives) {
        if (!(raw instanceof List<?> actions)) return null;
        for (String type : alternatives) {
            for (Object item : actions) {
                if (item instanceof Map<?, ?> action && type.equals(action.get("action_type"))) {
                    try {
                        if (action.get("value")==null) throw new NumberFormatException();
                        BigDecimal value=new BigDecimal(action.get("value").toString());
                        if (value.signum()<0 || value.precision()>18) throw new NumberFormatException();
                        return value;
                    } catch (NumberFormatException exception) {
                        throw new MetaApiException(HttpStatus.BAD_GATEWAY,"META_INVALID_METRICS",
                                "A Meta retornou valores numericos invalidos nas metricas.");
                    }
                }
            }
        }
        return null;
    }
}
