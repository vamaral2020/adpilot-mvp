package br.com.adpilot.integrations.meta;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Read-only interpreter of Meta delivery settings and attributed funnel metrics. */
@Service
public class MetaDeliveryInterpreterService {
    private final MetaAdsClient client;
    public MetaDeliveryInterpreterService(MetaAdsClient client) {this.client=client;}

    public record Adset(String id,String name,String optimizationGoal,String promotedEvent,
            String billingEvent,String bidStrategy,String destinationType,
            String effectiveStatus,String interpretation) {}
    public record Evidence(String level,String id,String adsetId,BigDecimal spend,
            long impressions,long allClicks,Long linkClicks,BigDecimal landingPageViews,
            BigDecimal addToCarts,BigDecimal attributedPurchases,
            BigDecimal attributedPurchaseValue,String attributionSetting,
            BigDecimal linkCtrPercent,BigDecimal costPerLinkClick,
            BigDecimal attributedCpa,BigDecimal attributedRoas,String evidenceQuality) {}
    public record Coverage(boolean complete,int pagesFetched,int rowsFetched) {}
    public record Report(String campaignId,String campaignObjective,String campaignStatus,
            String currency,LocalDate since,LocalDate until,boolean complete,
            Coverage adsetConfigurationCoverage,Coverage adsetInsightsCoverage,
            Coverage adInsightsCoverage,List<Adset> adsets,List<Evidence> adsetInsights,
            List<Evidence> adInsights,List<String> warnings,List<String> principles) {}
    private record Rows(List<Map<String,Object>> data,Coverage coverage) {}

    public Report inspect(String campaignId,LocalDate since,LocalDate until,
            int pageSize,int maxPages) {
        if(campaignId==null||!campaignId.matches("[0-9]{1,40}"))
            throw new MetaApiException(HttpStatus.BAD_REQUEST,"INVALID_CAMPAIGN_ID",
                    "Identificador de campanha invalido.");
        if(since==null||until==null||since.isAfter(until)
                ||ChronoUnit.DAYS.between(since,until)>30
                ||until.isAfter(LocalDate.now(ZoneOffset.UTC)))
            throw new MetaApiException(HttpStatus.BAD_REQUEST,"INVALID_DATE_RANGE",
                    "Informe ate 31 dias completos sem datas futuras.");
        if(pageSize<1||pageSize>100||maxPages<1||maxPages>3)
            throw new MetaApiException(HttpStatus.BAD_REQUEST,"INVALID_PAGINATION",
                    "pageSize 1..100, maxPages 1..3.");

        // Mandatory account ownership check before any child-object reads.
        Map<String,Object> campaign=client.deliveryCampaign(campaignId);
        String objective=known(campaign.get("objective"),
                Set.of("OUTCOME_SALES","OUTCOME_TRAFFIC","OUTCOME_ENGAGEMENT",
                        "OUTCOME_LEADS","OUTCOME_AWARENESS","OUTCOME_APP_PROMOTION"));
        String status=known(campaign.get("effective_status"),
                Set.of("ACTIVE","PAUSED","CAMPAIGN_PAUSED","ARCHIVED","DELETED",
                        "WITH_ISSUES","IN_PROCESS"));
        String currency=knownCurrency(client.account().get("currency"));
        Rows config=read(campaignId,"config",since,until,pageSize,maxPages);
        Rows sets=read(campaignId,"adset",since,until,pageSize,maxPages);
        Rows ads=read(campaignId,"ad",since,until,pageSize,maxPages);
        var configurations=new ArrayList<Adset>();
        for(var row:config.data()) {
            String goal=known(row.get("optimization_goal"),Set.of(
                    "OFFSITE_CONVERSIONS","VALUE","LINK_CLICKS","LANDING_PAGE_VIEWS",
                    "IMPRESSIONS","REACH","POST_ENGAGEMENT","THRUPLAY",
                    "LEAD_GENERATION","CONVERSATIONS","QUALITY_LEAD"));
            String event=known(row.get("promoted_event"),Set.of(
                    "PURCHASE","ADD_TO_CART","INITIATED_CHECKOUT","LEAD",
                    "COMPLETE_REGISTRATION"));
            configurations.add(new Adset(identifier(row.get("id")),
                    row.get("name") instanceof String name&&name.length()<=160?name:null,
                    goal,event,known(row.get("billing_event"),Set.of(
                            "IMPRESSIONS","CLICKS","LINK_CLICKS","PURCHASE","NONE")),
                    known(row.get("bid_strategy"),Set.of(
                            "LOWEST_COST_WITHOUT_CAP","LOWEST_COST_WITH_BID_CAP",
                            "COST_CAP","LOWEST_COST_WITH_MIN_ROAS")),
                    known(row.get("destination_type"),Set.of(
                            "WEBSITE","APP","MESSENGER","FACEBOOK","UNDEFINED")),
                    known(row.get("effective_status"),Set.of(
                            "ACTIVE","PAUSED","CAMPAIGN_PAUSED","ARCHIVED",
                            "DELETED","WITH_ISSUES","IN_PROCESS")),
                    interpretation(objective,goal,event)));
        }
        var adsetSignals=measure(sets,"adset");
        var adSignals=measure(ads,"ad");
        boolean complete=config.coverage().complete()
                &&sets.coverage().complete()&&ads.coverage().complete();
        var warnings=new ArrayList<String>(List.of(
                "META_ATTRIBUTION_NOT_CAUSAL_OR_VERIFIED_STORE_ORDERS",
                "TARGETING_AND_ADVANTAGE_AUDIENCE_NOT_INSPECTED",
                "LEARNING_PHASE_NOT_INSPECTED",
                "ACTIONS_MISSING_MEANS_UNKNOWN_NOT_ZERO",
                "ALL_CLICKS_DIFFER_FROM_LINK_CLICKS",
                "ATTRIBUTION_LAG_AND_SMALL_SAMPLES_POSSIBLE",
                "NO_AD_CHANGES_OR_BUDGET_AUTOMATION"));
        if(!complete) warnings.add("INCOMPLETE_PAGINATION_NO_FINAL_RANKING");
        if("UNKNOWN".equals(currency)) warnings.add("CURRENCY_UNKNOWN");
        if(configurations.stream().anyMatch(a->
                "REVIEW_SALES_OPTIMIZATION".equals(a.interpretation())))
            warnings.add("SALES_CAMPAIGN_USING_TRAFFIC_OR_REACH_GOAL");
        if(adsetSignals.stream().anyMatch(a->"UNKNOWN".equals(a.attributionSetting()))
                ||adSignals.stream().anyMatch(a->"UNKNOWN".equals(a.attributionSetting())))
            warnings.add("ATTRIBUTION_SETTING_UNKNOWN");
        return new Report(campaignId,objective,status,currency,since,until,complete,
                config.coverage(),sets.coverage(),ads.coverage(),List.copyOf(configurations),
                List.copyOf(adsetSignals),List.copyOf(adSignals),List.copyOf(warnings),
                List.of("CHECK_PURCHASE_EVENT_BEFORE_CHANGING_AUDIENCE",
                        "COMPARE_SIMILAR_OBJECTIVES_ATTRIBUTION_AND_PERIODS",
                        "CREATIVE_AND_LANDING_PAGE_TESTS_REQUIRE_A_HYPOTHESIS",
                        "NO_PAUSE_SCALE_OR_PROFIT_CLAIMS_FROM_ATTRIBUTED_ROAS"));
    }

    private Rows read(String campaignId,String level,LocalDate since,LocalDate until,
            int pageSize,int maxPages) {
        var data=new ArrayList<Map<String,Object>>();
        var cursors=new HashSet<String>();
        var seenIds=new HashSet<String>();
        String after=null;
        for(int p=1;p<=maxPages;p++) {
            MetaPage page="config".equals(level)
                    ?client.deliveryAdsets(campaignId,pageSize,after)
                    :client.deliveryInsights(campaignId,level,since,until,pageSize,after);
            if(page==null||page.data()==null||page.data().size()>pageSize
                    ||page.data().isEmpty()&&page.after()!=null) throw invalid();
            for(var row:page.data()) {
                if(row==null||!campaignId.equals(String.valueOf(row.get("campaign_id"))))
                    throw invalid();
                String key=switch(level) {
                    case "config" -> identifier(row.get("id"));
                    case "adset" -> identifier(row.get("adset_id"));
                    case "ad" -> identifier(row.get("ad_id"));
                    default -> throw invalid();
                };
                if(!seenIds.add(key)) throw invalid();
                data.add(row);
            }
            if(page.after()==null) return new Rows(List.copyOf(data),
                    new Coverage(true,p,data.size()));
            if(page.after().isBlank()||page.after().length()>2048
                    ||!cursors.add(page.after())) throw invalid();
            after=page.after();
        }
        return new Rows(List.copyOf(data),new Coverage(false,maxPages,data.size()));
    }

    private static List<Evidence> measure(Rows rows,String level) {
        var result=new ArrayList<Evidence>();
        for(var row:rows.data()) {
            String set=identifier(row.get("adset_id"));
            String id="ad".equals(level)?identifier(row.get("ad_id")):set;
            BigDecimal spend=decimal(row.get("spend"));
            long impressions=count(row.get("impressions"));
            long allClicks=count(row.get("clicks"));
            Long link=row.get("inline_link_clicks")==null?null:count(row.get("inline_link_clicks"));
            Map<String,BigDecimal> actionCounts=MetaPurchaseAuditService.extract(row.get("actions"));
            Map<String,BigDecimal> actionValues=MetaPurchaseAuditService.extract(
                    row.get("action_values"));
            BigDecimal purchases=preferredPurchase(actionCounts);
            BigDecimal revenue=preferredPurchase(actionValues);
            MetaOutcomeMetrics events=MetaOutcomeMetrics.fromRow(row);
            String attr=known(row.get("attribution_setting"),Set.of(
                    "1d_click","7d_click","1d_view","1d_click_1d_view",
                    "7d_click_1d_view","7d_click_1d_ev","1d_click_1d_ev"));
            result.add(new Evidence(level,id,set,spend,impressions,allClicks,link,
                    events.landingPageViews(),events.addToCarts(),purchases,revenue,attr,
                    link==null||impressions==0?null:
                            ratio(BigDecimal.valueOf(link).multiply(BigDecimal.valueOf(100)),
                                    BigDecimal.valueOf(impressions)),
                    link==null||link==0?null:ratio(spend,BigDecimal.valueOf(link)),
                    purchases==null||purchases.signum()==0?null:ratio(spend,purchases),
                    revenue==null||spend.signum()==0?null:ratio(revenue,spend),
                    purchases==null?"PURCHASE_ACTION_UNAVAILABLE"
                            :purchases.compareTo(BigDecimal.valueOf(5))<0
                            ?"SMALL_ATTRIBUTED_PURCHASE_SAMPLE":"ATTRIBUTED_SIGNALS_OBSERVED"));
        }
        return List.copyOf(result);
    }

    private static BigDecimal preferredPurchase(Map<String,BigDecimal> values) {
        for(String key:MetaPurchaseAuditService.PURCHASE_TYPES)
            if(values.containsKey(key)) return values.get(key);
        return null;
    }
    private static String interpretation(String objective,String goal,String event) {
        if(!"OUTCOME_SALES".equals(objective)) return "OBJECTIVE_NOT_SALES";
        if(Set.of("LINK_CLICKS","LANDING_PAGE_VIEWS","IMPRESSIONS",
                "REACH","POST_ENGAGEMENT","THRUPLAY").contains(goal))
            return "REVIEW_SALES_OPTIMIZATION";
        if("VALUE".equals(goal)) return "VALUE_OPTIMIZATION_DECLARED";
        if("OFFSITE_CONVERSIONS".equals(goal))
            return "PURCHASE".equals(event)?"PURCHASE_EVENT_DECLARED"
                    :"CONVERSION_EVENT_NOT_VERIFIED";
        return "OPTIMIZATION_UNKNOWN";
    }
    private static String known(Object value,Set<String> allow) {
        return value instanceof String s&&allow.contains(s)?s:"UNKNOWN";
    }
    private static String knownCurrency(Object value) {
        return value instanceof String s&&s.matches("[A-Z]{3}")?s:"UNKNOWN";
    }
    private static String identifier(Object value) {
        if(value==null||!value.toString().matches("[0-9]{1,40}")) throw invalid();
        return value.toString();
    }
    private static BigDecimal decimal(Object value) {
        try {
            if(value==null) throw new NumberFormatException();
            BigDecimal n=new BigDecimal(value.toString());
            if(n.signum()<0||n.precision()>18||n.scale()>6) throw new NumberFormatException();
            return n;
        } catch(NumberFormatException e) {throw invalid();}
    }
    private static long count(Object value) {
        try {return decimal(value).longValueExact();}
        catch(ArithmeticException e) {throw invalid();}
    }
    private static BigDecimal ratio(BigDecimal x,BigDecimal y) {
        return x.divide(y,4,RoundingMode.HALF_UP);
    }
    private static MetaApiException invalid() {
        return new MetaApiException(HttpStatus.BAD_GATEWAY,
                "META_DELIVERY_INVALID_DATA","Dados de entrega Meta inconsistentes.");
    }
}
