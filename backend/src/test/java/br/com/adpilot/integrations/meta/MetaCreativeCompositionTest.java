package br.com.adpilot.integrations.meta;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MetaCreativeCompositionTest {
    @Test void dynamicMixedAssetsAndCarouselsReturnCountsWithoutLeakingContent() {
        var creative=Map.of(
                "id","secret-creative-id",
                "product_set_id","confidential-product-set",
                "asset_feed_spec",Map.of(
                        "images",List.of(Map.of("hash","PRIVATE-HASH"),
                                Map.of("url","https://private.invalid/image")),
                        "videos",List.of(Map.of("video_id","SECRET-VIDEO")),
                        "carousels",List.of(
                                Map.of("child_attachments",List.of(
                                        Map.of("url","https://private.invalid/card1"),
                                        Map.of("url","https://private.invalid/card2"))),
                                Map.of("child_attachments",List.of(
                                        Map.of("url","https://private.invalid/card3"),
                                        Map.of("url","https://private.invalid/card4"),
                                        Map.of("url","https://private.invalid/card5")))),
                        "ad_formats",List.of("SINGLE_IMAGE","CAROUSEL","PRIVATE-FORMAT"),
                        "bodies",List.of(Map.of("text","PRIVATE-COPY"))));
        var summary=MetaCreativeComposition.inspect(creative);

        assertTrue(summary.dynamicAssetFeed());
        assertEquals(2,summary.imageAssetCount());
        assertEquals(1,summary.videoAssetCount());
        assertEquals(2,summary.carouselVariantCount());
        assertEquals(5,summary.carouselCardCount());
        assertEquals(List.of("SINGLE_IMAGE","CAROUSEL"),summary.declaredFormats());
        assertTrue(summary.mixedImageVideoAssets());
        assertTrue(summary.carouselStructureDetected());
        assertTrue(summary.catalogReferenceDetected());
        assertTrue(summary.notes().contains("AD_FORMATS_UNRECOGNIZED"));
        assertTrue(summary.notes().contains("CATALOG_REFERENCE_NOT_DELIVERY_PROOF"));
        assertTrue(summary.notes().contains("DYNAMIC_DELIVERY_NOT_VERIFIABLE"));
        assertFalse(summary.toString().contains("PRIVATE"));
        assertFalse(summary.toString().contains("confidential"));
        assertFalse(summary.toString().contains("private.invalid"));
        assertFalse(summary.toString().contains("SECRET"));
    }

    @Test void missingAssetListNeverGetsMisrepresentedAsZero() {
        var result=MetaCreativeComposition.inspect(Map.of("asset_feed_spec",
                Map.of("images",List.of(Map.of("hash","a")),
                        "ad_formats",List.of("SINGLE_IMAGE"))));
        assertEquals(1,result.imageAssetCount());
        assertNull(result.videoAssetCount());
        assertNull(result.carouselVariantCount());
        assertNull(result.carouselCardCount());
        assertTrue(result.notes().contains("SOME_ASSET_COUNTS_NOT_REPORTED"));
        assertFalse(result.mixedImageVideoAssets());
    }

    @Test void explicitEmptyListsAreObservedZero() {
        var result=MetaCreativeComposition.inspect(Map.of("asset_feed_spec",
                Map.of("images",List.of(),"videos",List.of(),"carousels",List.of())));
        assertEquals(0,result.imageAssetCount());
        assertEquals(0,result.videoAssetCount());
        assertEquals(0,result.carouselVariantCount());
        assertEquals(0,result.carouselCardCount());
        assertFalse(result.carouselStructureDetected());
        assertFalse(result.mixedImageVideoAssets());
    }

    @Test void staticCarouselHasStructuralCardsButNoInventedDynamicAssetCounts() {
        var result=MetaCreativeComposition.inspect(Map.of("object_story_spec",
                Map.of("link_data",Map.of("child_attachments",List.of(
                        Map.of("link","https://private.invalid/a"),
                        Map.of("link","https://private.invalid/b"))))));
        assertFalse(result.dynamicAssetFeed());
        assertEquals(2,result.carouselCardCount());
        assertNull(result.carouselVariantCount());
        assertNull(result.imageAssetCount());
        assertNull(result.videoAssetCount());
        assertTrue(result.carouselStructureDetected());
        assertFalse(result.toString().contains("private.invalid"));
    }

    @Test void catalogTemplateIsOnlyACatalogSignalNotProofOfDelivery() {
        var result=MetaCreativeComposition.inspect(Map.of(
                "object_story_spec",Map.of("template_data",Map.of("link","https://private.invalid"))));
        assertTrue(result.catalogReferenceDetected());
        assertTrue(result.notes().contains("CATALOG_REFERENCE_NOT_DELIVERY_PROOF"));
        assertFalse(result.toString().contains("private.invalid"));
    }

    @Test void incompleteCarouselChildrenYieldUnknownNotZero() {
        var result=MetaCreativeComposition.inspect(Map.of("asset_feed_spec",
                Map.of("carousels",List.of(Map.of("name","not-an-attachment-list")))));
        assertEquals(1,result.carouselVariantCount());
        assertNull(result.carouselCardCount());
        assertTrue(result.carouselStructureDetected());
        assertTrue(result.notes().contains("CAROUSEL_CARD_COUNT_NOT_REPORTED"));
    }

    @Test void unrecognizedAndMissingCreativeMetadataNeverLeakRawValues() {
        var unknown=MetaCreativeComposition.inspect(null);
        assertNull(unknown.imageAssetCount());
        assertEquals(List.of("CREATIVE_METADATA_NOT_AVAILABLE"),unknown.notes());

        var malformed=MetaCreativeComposition.inspect(Map.of(
                "asset_feed_spec",Map.of("images","PRIVATE-RAW",
                        "ad_formats",List.of("SECRET-FORMAT","CAROUSEL"))));
        assertNull(malformed.imageAssetCount());
        assertEquals(List.of("CAROUSEL"),malformed.declaredFormats());
        assertTrue(malformed.carouselStructureDetected());
        assertFalse(malformed.toString().contains("PRIVATE-RAW"));
        assertFalse(malformed.toString().contains("SECRET-FORMAT"));
    }
}
