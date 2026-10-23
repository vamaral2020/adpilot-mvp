package br.com.adpilot.integrations.meta;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Strictly allowlisted structural projection of Meta creative metadata.
 *
 * Entries are configurations, NOT unique media files or actual placements.
 * A null count is "not provided"; only an explicit empty list is zero.
 * Never return image hashes, video IDs, URLs, link destinations, copy, raw
 * ad_formats values, or any object_story_spec/asset_feed_spec payload.
 */
public record MetaCreativeComposition(
        boolean dynamicAssetFeed,
        Integer imageAssetCount,
        Integer videoAssetCount,
        Integer carouselVariantCount,
        Integer carouselCardCount,
        List<String> declaredFormats,
        boolean carouselStructureDetected,
        boolean catalogReferenceDetected,
        boolean mixedImageVideoAssets,
        List<String> notes) {

    private static final Set<String> SAFE_FORMATS = Set.of(
            "SINGLE_IMAGE", "SINGLE_VIDEO", "CAROUSEL", "COLLECTION");

    public static MetaCreativeComposition inspect(Object rawCreative) {
        if (!(rawCreative instanceof Map<?,?> creative)) {
            return new MetaCreativeComposition(false,null,null,null,null,List.of(),
                    false,false,false,List.of("CREATIVE_METADATA_NOT_AVAILABLE"));
        }

        Map<?,?> feed = creative.get("asset_feed_spec") instanceof Map<?,?> value
                ? value : null;
        boolean dynamic = feed != null && !feed.isEmpty();
        Integer images = count(feed,"images");
        Integer videos = count(feed,"videos");
        Integer carousels = count(feed,"carousels");

        var notes = new ArrayList<String>();
        var formats = new LinkedHashSet<String>();
        if (dynamic) {
            safeFormats(feed.get("ad_formats"),formats,notes);
            if (images==null || videos==null || carousels==null)
                notes.add("SOME_ASSET_COUNTS_NOT_REPORTED");
            notes.add("DYNAMIC_DELIVERY_NOT_VERIFIABLE");
        }

        Integer cards = carouselCards(feed,notes);
        boolean carouselStructure = carousels!=null && carousels>0;
        if (cards!=null && cards>=2) carouselStructure=true;

        Map<?,?> story = creative.get("object_story_spec") instanceof Map<?,?> m
                ? m : null;
        if (story!=null) {
            Integer storyCards=storyCardCount(story);
            if (storyCards!=null && storyCards>=2) carouselStructure=true;
            if (cards==null) cards=storyCards;
        }
        if (formats.contains("CAROUSEL")) carouselStructure=true;

        String objectType = creative.get("object_type") instanceof String value
                ? value : "";
        boolean catalog = "PRODUCT_SET".equals(objectType)
                || "DYNAMIC_PRODUCT_AD".equals(objectType)
                || present(creative.get("product_set_id"))
                || story!=null && (present(story.get("product_data"))
                        || present(story.get("template_data")));
        if (catalog) notes.add("CATALOG_REFERENCE_NOT_DELIVERY_PROOF");
        if (carouselStructure) notes.add("CAROUSEL_STRUCTURE_NOT_DELIVERY_PROOF");

        return new MetaCreativeComposition(dynamic,images,videos,carousels,cards,
                List.copyOf(formats),carouselStructure,catalog,
                images!=null && images>0 && videos!=null && videos>0,
                List.copyOf(notes));
    }

    private static Integer count(Map<?,?> root,String key) {
        return root!=null && root.get(key) instanceof List<?> list ? list.size() : null;
    }

    private static boolean present(Object value) {
        if (value==null) return false;
        if (value instanceof String s) return !s.isBlank();
        if (value instanceof Map<?,?> map) return !map.isEmpty();
        if (value instanceof List<?> list) return !list.isEmpty();
        return false;
    }

    private static void safeFormats(Object input,LinkedHashSet<String> output,
                                    List<String> notes) {
        if (input==null) return;
        if (!(input instanceof List<?> rows)) {
            notes.add("AD_FORMATS_UNRECOGNIZED");
            return;
        }
        boolean invalid = false;
        for (Object row:rows) {
            // Raw values are never reflected, even if Meta introduces a new
            // enum with an unexpected string.
            if (row instanceof String value && SAFE_FORMATS.contains(value))
                output.add(value);
            else invalid=true;
        }
        if (invalid) notes.add("AD_FORMATS_UNRECOGNIZED");
    }

    private static Integer carouselCards(Map<?,?> feed,List<String> notes) {
        if (feed==null || !(feed.get("carousels") instanceof List<?> entries))
            return null;
        if (entries.isEmpty()) return 0;
        int total = 0;
        for (Object row:entries) {
            if (!(row instanceof Map<?,?> variant)
                    || !(variant.get("child_attachments") instanceof List<?> cards)) {
                notes.add("CAROUSEL_CARD_COUNT_NOT_REPORTED");
                return null;
            }
            total=Math.addExact(total,cards.size());
        }
        return total;
    }

    private static Integer storyCardCount(Map<?,?> story) {
        Object link=story.get("link_data");
        if (!(link instanceof Map<?,?> data)) link=story.get("template_data");
        return link instanceof Map<?,?> data
                && data.get("child_attachments") instanceof List<?> cards
                ? cards.size() : null;
    }
}
