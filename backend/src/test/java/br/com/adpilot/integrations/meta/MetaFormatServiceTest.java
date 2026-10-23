package br.com.adpilot.integrations.meta;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MetaFormatServiceTest {
    private final MetaAdsClient client=mock(MetaAdsClient.class);
    private final MetaFormatService service=new MetaFormatService(client);

    @Test void classifiesFormatsWithoutLeakingCreativeLinksOrTexts() {
        when(client.adsets("123",100,null)).thenReturn(new MetaPage(List.of(
                Map.of("id","1","campaign_id","123","name","Adset teste",
                        "optimization_goal","LANDING_PAGE_VIEWS",
                        "destination_type","WEBSITE","effective_status","ACTIVE")),null));
        when(client.ads("123",100,null)).thenReturn(new MetaPage(List.of(
                Map.of("id","11","name","Foto","campaign_id","123","adset_id","1",
                        "creative",Map.of("object_story_spec",Map.of("link_data",Map.of(
                                "image_hash","hash", "link","https://private.invalid")))),
                Map.of("id","12","name","Carrossel","campaign_id","123","adset_id","1",
                        "creative",Map.of("object_story_spec",Map.of("link_data",Map.of(
                                "child_attachments",List.of(
                                        Map.of("link","https://private.invalid/1"),
                                        Map.of("link","https://private.invalid/2")))))),
                Map.of("id","13","name","Vídeo","campaign_id","123","adset_id","1",
                        "creative",Map.of("object_story_spec",Map.of("video_data",Map.of(
                                "video_id","991","message","PRIVATE"))))),null));
        var result=service.report("123");
        assertTrue(result.complete());
        assertTrue(result.formatsFullyIdentified());
        assertEquals(1,result.adsets().size());
        assertEquals("LANDING_PAGE_VIEWS",result.adsets().getFirst().optimizationGoal());
        assertEquals(List.of("IMAGE","CAROUSEL","VIDEO"),
                result.ads().stream().map(MetaFormatService.AdInfo::format).toList());
        assertFalse(result.toString().contains("private.invalid"));
        assertFalse(result.toString().contains("PRIVATE"));
        assertFalse(result.toString().contains("991"));
    }

    @Test void dynamicCatalogAndMissingSpecsAreExplicitlyNotAssumedStatic() {
        assertEquals("CATALOG",MetaFormatService.classify(Map.of("object_type","PRODUCT_SET")));
        assertEquals("DYNAMIC_ASSETS",MetaFormatService.classify(Map.of(
                "asset_feed_spec",Map.of("images",List.of(Map.of("hash","123"))))));
        assertEquals("UNKNOWN",MetaFormatService.classify(Map.of()));
        assertEquals("UNKNOWN",MetaFormatService.classify(null));
    }

    @Test void missingProviderContinuationMarksResultsIncomplete() {
        when(client.adsets("123",100,null)).thenReturn(new MetaPage(List.of(),"next"));
        assertEquals("META_INVALID_PAGINATION",assertThrows(MetaApiException.class,
                ()->service.report("123")).errorCode());
    }

    @Test void filtersUnexpectedCampaignIdsBeforePublishing() {
        when(client.adsets("123",100,null)).thenReturn(new MetaPage(List.of(
                Map.of("id","1","campaign_id","999")),null));
        assertEquals("META_INVALID_CREATIVE_DATA",assertThrows(MetaApiException.class,
                ()->service.report("123")).errorCode());
    }

    @Test void rejectsNonNumericCampaignWithoutProviderCalls() {
        assertEquals("INVALID_CAMPAIGN_ID",assertThrows(MetaApiException.class,
                ()->service.report("x/ads")).errorCode());
        verifyNoInteractions(client);
    }
}
