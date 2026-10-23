package br.com.adpilot.integrations.meta;

import static org.junit.jupiter.api.Assertions.*;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MetaOutcomeMetricsTest {
    private static Map<String,Object> action(String type,String count) {
        return Map.of("action_type",type,"value",count);
    }

    @Test void neverAddsEquivalentActionTaxonomiesTogether() {
        var result=MetaOutcomeMetrics.fromRow(Map.of("actions",List.of(
                action("link_click","63"),
                action("landing_page_view","54"),
                action("omni_landing_page_view","54"),
                action("post_engagement","69"),
                action("add_to_cart","1"),
                action("omni_add_to_cart","1"))));
        assertEquals(new BigDecimal("63"),result.linkClicks());
        assertEquals(new BigDecimal("54"),result.landingPageViews());
        assertEquals(new BigDecimal("69"),result.postEngagements());
        assertEquals(BigDecimal.ONE,result.addToCarts());
    }

    @Test void aliasIsOnlyUsedWhenCanonicalActionIsAbsent() {
        var result=MetaOutcomeMetrics.fromRow(Map.of("actions",List.of(
                action("omni_landing_page_view","4"),
                action("offsite_conversion.fb_pixel_add_to_cart","2"))));
        assertNull(result.linkClicks());
        assertEquals(new BigDecimal("4"),result.landingPageViews());
        assertEquals(new BigDecimal("2"),result.addToCarts());
        assertNull(result.postEngagements());
    }

    @Test void missingActionIsUnknownRatherThanProvenZero() {
        var result=MetaOutcomeMetrics.fromRow(Map.of("spend","12.30"));
        assertNull(result.linkClicks());
        assertNull(result.landingPageViews());
        assertNull(result.postEngagements());
    }

    @Test void rejectsInvalidProviderCounts() {
        assertEquals("META_INVALID_METRICS",assertThrows(MetaApiException.class,
                () -> MetaOutcomeMetrics.fromRow(Map.of("actions",List.of(
                        action("link_click","-2"))))).errorCode());
        assertEquals("META_INVALID_METRICS",assertThrows(MetaApiException.class,
                () -> MetaOutcomeMetrics.fromRow(Map.of("actions",List.of(
                        action("post_engagement","not-a-number"))))).errorCode());
    }
}
