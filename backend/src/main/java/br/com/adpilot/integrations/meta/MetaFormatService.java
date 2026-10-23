package br.com.adpilot.integrations.meta;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Campaign-scoped and explicitly on-demand; never writes to Meta or AdPilot.
 * Returns only whitelisted ad-set properties and the classified creative type.
 * Raw object_story_spec / asset_feed_spec is discarded before returning.
 */
@Service
public class MetaFormatService {
    private static final int PAGE_SIZE=100;
    private static final int MAX_PAGES=5;
    private final MetaAdsClient client;

    public MetaFormatService(MetaAdsClient client) { this.client=client; }

    public record AdSetInfo(String id,String name,String optimizationGoal,
            String destinationType,String effectiveStatus) {}
    public record AdInfo(String id,String name,String adsetId,String effectiveStatus,
            String format,String formatEvidence) {}
    public record FormatReport(String campaignId,List<AdSetInfo> adsets,
            List<AdInfo> ads,boolean complete,boolean formatsFullyIdentified,
            int adsetPages,int adPages) {}
    private record Collected(List<Map<String,Object>> data,boolean complete,int pages) {}

    public FormatReport report(String campaignId) {
        if (campaignId==null || !campaignId.matches("[0-9]{1,40}"))
            throw new MetaApiException(HttpStatus.BAD_REQUEST,"INVALID_CAMPAIGN_ID",
                    "Informe o ID numerico de uma campanha.");
        if(!client.belongsToConfiguredAccount(campaignId))
            throw new MetaApiException(HttpStatus.NOT_FOUND,"CAMPAIGN_NOT_IN_ACCOUNT",
                    "Campanha não identificada na conta Meta configurada.");
        Collected sets=collect(campaignId,true);
        Collected ads=collect(campaignId,false);
        var setData=new ArrayList<AdSetInfo>();
        var adData=new ArrayList<AdInfo>();
        for(var row:sets.data()) {
            setData.add(new AdSetInfo(identifier(row.get("id")),
                    label(row.get("name"),160), label(row.get("optimization_goal"),80),
                    label(row.get("destination_type"),80),
                    label(row.get("effective_status"),40)));
        }
        for(var row:ads.data()) {
            var format=classify(row.get("creative"));
            adData.add(new AdInfo(identifier(row.get("id")),
                    label(row.get("name"),160),identifier(row.get("adset_id")),
                    label(row.get("effective_status"),40),format,
                    "META_CREATIVE_METADATA"));
        }
        boolean complete=sets.complete() && ads.complete();
        boolean classified=complete && adData.stream()
                .noneMatch(x->"UNKNOWN".equals(x.format()) || "LINK_OR_IMAGE".equals(x.format())
                        || "DYNAMIC_ASSETS".equals(x.format()));
        return new FormatReport(campaignId,List.copyOf(setData),List.copyOf(adData),
                complete,classified,sets.pages(),ads.pages());
    }

    private Collected collect(String campaign,boolean adsets) {
        var rows=new ArrayList<Map<String,Object>>();
        var seenIds=new HashSet<String>();
        Set<String> seenCursors=new HashSet<>();
        String after=null;
        for(int pageIndex=0;pageIndex<MAX_PAGES;pageIndex++) {
            MetaPage page=adsets?client.adsets(campaign,PAGE_SIZE,after)
                    :client.ads(campaign,PAGE_SIZE,after);
            if(page.data()==null || (page.data().isEmpty() && page.after()!=null))
                throw new MetaApiException(HttpStatus.BAD_GATEWAY,"META_INVALID_PAGINATION",
                        "Paginacao inconsistente da Meta.");
            for(var row:page.data()) {
                if(row==null) throw invalid();
                if(row.get("campaign_id")!=null
                        && !campaign.equals(String.valueOf(row.get("campaign_id"))))
                    throw invalid();
                String id=identifier(row.get("id"));
                if(!seenIds.add(id)) throw invalid();
                rows.add(row);
            }
            String next=page.after();
            if(next==null) return new Collected(rows,true,pageIndex+1);
            if(next.isBlank() || next.length()>2048 || !seenCursors.add(next))
                throw new MetaApiException(HttpStatus.BAD_GATEWAY,"META_INVALID_PAGINATION",
                        "Cursor invalido da Meta.");
            after=next;
        }
        return new Collected(rows,false,MAX_PAGES);
    }

    private static String identifier(Object value) {
        if(value==null || !value.toString().matches("[0-9]{1,40}")) throw invalid();
        return value.toString();
    }
    private static String label(Object value,int max) {
        if(value==null) return null;
        if(!(value instanceof String text) || text.length()>max) throw invalid();
        return text;
    }
    private static MetaApiException invalid() {
        return new MetaApiException(HttpStatus.BAD_GATEWAY,"META_INVALID_CREATIVE_DATA",
                "A Meta retornou metadados inesperados.");
    }

    /** Conservative classification: never guess a static format from mixed assets. */
    static String classify(Object creativeValue) {
        if(!(creativeValue instanceof Map<?,?> creative)) return "UNKNOWN";
        String type=creative.get("object_type") instanceof String value ? value : "";
        if(type.equals("PRODUCT_SET") || type.equals("DYNAMIC_PRODUCT_AD"))
            return "CATALOG";
        if(creative.get("asset_feed_spec") instanceof Map<?,?> assets && !assets.isEmpty())
            return "DYNAMIC_ASSETS";
        if(creative.get("object_story_spec") instanceof Map<?,?> story) {
            if(story.get("video_data") instanceof Map<?,?>) return "VIDEO";
            if(story.get("photo_data") instanceof Map<?,?>) return "IMAGE";
            if(story.get("link_data") instanceof Map<?,?> link) {
                if(link.get("child_attachments") instanceof List<?> children
                        && children.size()>1) return "CAROUSEL";
                if(link.containsKey("image_hash") || link.containsKey("picture"))
                    return "IMAGE";
                return "LINK_OR_IMAGE";
            }
        }
        return switch(type) {
            case "VIDEO" -> "VIDEO";
            case "PHOTO" -> "IMAGE";
            case "CAROUSEL" -> "CAROUSEL";
            default -> "UNKNOWN";
        };
    }
}
