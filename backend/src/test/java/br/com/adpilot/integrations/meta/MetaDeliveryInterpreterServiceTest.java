package br.com.adpilot.integrations.meta;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MetaDeliveryInterpreterServiceTest {
    private static final String CAMPAIGN="123456";
    private static final LocalDate SINCE=LocalDate.of(2026,10,1);
    private static final LocalDate UNTIL=LocalDate.of(2026,10,7);
    private final MetaAdsClient client=mock(MetaAdsClient.class);
    private final MetaDeliveryInterpreterService service=new MetaDeliveryInterpreterService(client);

    @BeforeEach void setup() {
        when(client.deliveryCampaign(CAMPAIGN)).thenReturn(Map.of(
                "id",CAMPAIGN,"objective","OUTCOME_SALES","effective_status","ACTIVE"));
        when(client.account()).thenReturn(Map.of("currency","BRL"));
        when(client.deliveryAdsets(CAMPAIGN,100,null)).thenReturn(new MetaPage(List.of(
                Map.of("id","111","campaign_id",CAMPAIGN,"name","Visitas",
                        "optimization_goal","LINK_CLICKS","effective_status","ACTIVE"),
                Map.of("id","222","campaign_id",CAMPAIGN,"name","Compras",
                        "optimization_goal","OFFSITE_CONVERSIONS",
                        "promoted_event","PURCHASE","effective_status","ACTIVE")),null));
        when(client.deliveryInsights(CAMPAIGN,"adset",SINCE,UNTIL,100,null))
                .thenReturn(new MetaPage(List.of(Map.of(
                        "campaign_id",CAMPAIGN,"adset_id","111","spend","90.00",
                        "impressions","3000","clicks","300","inline_link_clicks","100",
                        "actions",List.of(
                                Map.of("action_type","purchase","value","3"),
                                Map.of("action_type","omni_purchase","value","3"),
                                Map.of("action_type","landing_page_view","value","70"),
                                Map.of("action_type","add_to_cart","value","10")),
                        "action_values",List.of(
                                Map.of("action_type","purchase","value","300.00"),
                                Map.of("action_type","omni_purchase","value","300.00")),
                        "attribution_setting","7d_click_1d_view")),null));
        when(client.deliveryInsights(CAMPAIGN,"ad",SINCE,UNTIL,100,null))
                .thenReturn(new MetaPage(List.of(Map.of(
                        "campaign_id",CAMPAIGN,"adset_id","111","ad_id","555",
                        "spend","10.00","impressions","1000","clicks","150")),null));
    }

    @Test void interpretsSalesGoalAndSeparatesAllClicksFromLinkClicksAndSales() {
        var result=service.inspect(CAMPAIGN,SINCE,UNTIL,100,3);
        assertTrue(result.complete());
        assertEquals("BRL",result.currency());
        assertEquals(2,result.adsets().size());
        assertEquals("REVIEW_SALES_OPTIMIZATION",result.adsets().getFirst().interpretation());
        assertEquals("PURCHASE_EVENT_DECLARED",result.adsets().get(1).interpretation());
        assertTrue(result.warnings().contains("SALES_CAMPAIGN_USING_TRAFFIC_OR_REACH_GOAL"));
        var e=result.adsetInsights().getFirst();
        assertEquals(300,e.allClicks());
        assertEquals(100L,e.linkClicks());
        assertEquals("3",e.attributedPurchases().toPlainString());
        assertEquals("70",e.landingPageViews().toPlainString());
        assertEquals("10",e.addToCarts().toPlainString());
        assertEquals("3.3333",e.attributedRoas().toPlainString());
        assertEquals("3.3333",e.linkCtrPercent().toPlainString());
        assertEquals("30.0000",e.attributedCpa().toPlainString());
        assertEquals("SMALL_ATTRIBUTED_PURCHASE_SAMPLE",e.evidenceQuality());
        var ad=result.adInsights().getFirst();
        assertEquals(150,ad.allClicks());
        assertNull(ad.linkClicks());
        assertNull(ad.attributedPurchases());
        assertNull(ad.attributedRoas());
        assertNull(ad.attributedCpa());
        assertTrue(result.warnings().contains("ATTRIBUTION_SETTING_UNKNOWN"));
        assertFalse(result.principles().isEmpty());
        assertFalse(result.toString().contains("PRIVATE"));
    }

    @Test void incompletePagesRemainExplicitAndCannotBeTreatedAsFinalRanking() {
        when(client.deliveryInsights(CAMPAIGN,"ad",SINCE,UNTIL,100,null))
                .thenReturn(new MetaPage(List.of(Map.of("campaign_id",CAMPAIGN,
                        "adset_id","111","ad_id","555","spend","10",
                        "impressions","100","clicks","3")),"opaque_page_2"));
        var result=service.inspect(CAMPAIGN,SINCE,UNTIL,100,1);
        assertFalse(result.complete());
        assertFalse(result.adInsightsCoverage().complete());
        assertTrue(result.warnings().contains("INCOMPLETE_PAGINATION_NO_FINAL_RANKING"));
        verify(client,never()).deliveryInsights(
                eq(CAMPAIGN),eq("ad"),eq(SINCE),eq(UNTIL),eq(100),eq("opaque_page_2"));
    }

    @Test void duplicatedOrForeignInsightRowsFailClosed() {
        when(client.deliveryInsights(CAMPAIGN,"adset",SINCE,UNTIL,100,null))
                .thenReturn(new MetaPage(List.of(
                        Map.of("campaign_id",CAMPAIGN,"adset_id","111"),
                        Map.of("campaign_id",CAMPAIGN,"adset_id","111")),null));
        assertEquals("META_DELIVERY_INVALID_DATA",
                assertThrows(MetaApiException.class,
                        ()->service.inspect(CAMPAIGN,SINCE,UNTIL,100,3)).errorCode());
        when(client.deliveryInsights(CAMPAIGN,"adset",SINCE,UNTIL,100,null))
                .thenReturn(new MetaPage(List.of(Map.of(
                        "campaign_id","999","adset_id","111")),null));
        assertEquals("META_DELIVERY_INVALID_DATA",
                assertThrows(MetaApiException.class,
                        ()->service.inspect(CAMPAIGN,SINCE,UNTIL,100,3)).errorCode());
    }

    @Test void rejectsInvalidInputBeforeAnyProviderCalls() {
        assertEquals("INVALID_CAMPAIGN_ID",assertThrows(MetaApiException.class,
                ()->service.inspect("1/x",SINCE,UNTIL,100,3)).errorCode());
        assertEquals("INVALID_DATE_RANGE",assertThrows(MetaApiException.class,
                ()->service.inspect(CAMPAIGN,SINCE,SINCE.plusDays(40),100,3)).errorCode());
        assertEquals("INVALID_PAGINATION",assertThrows(MetaApiException.class,
                ()->service.inspect(CAMPAIGN,SINCE,UNTIL,100,4)).errorCode());
        verify(client,never()).deliveryCampaign("1/x");
    }

    @Test void absentPurchaseActionsAreUnknownNotZero() {
        var result=service.inspect(CAMPAIGN,SINCE,UNTIL,100,3);
        var ad=result.adInsights().getFirst();
        assertEquals("PURCHASE_ACTION_UNAVAILABLE",ad.evidenceQuality());
        assertNull(ad.attributedPurchases());
    }
    @Test void todayIsReadOnlyMonitoringEvenWhenAllMetaPagesWereRead() {
        LocalDate today=LocalDate.now(java.time.ZoneOffset.UTC);
        when(client.deliveryInsights(CAMPAIGN,"adset",today,today,100,null))
                .thenReturn(new MetaPage(List.of(Map.of(
                        "campaign_id",CAMPAIGN,"adset_id","111",
                        "spend","1.00","impressions","100","clicks","4")),null));
        when(client.deliveryInsights(CAMPAIGN,"ad",today,today,100,null))
                .thenReturn(new MetaPage(List.of(Map.of(
                        "campaign_id",CAMPAIGN,"adset_id","111","ad_id","555",
                        "spend","1.00","impressions","100","clicks","4")),null));
        var result=service.inspect(CAMPAIGN,today,today,100,3);
        assertTrue(result.complete(),"Pagination may be complete without final daily numbers");
        assertTrue(result.partialCurrentDay());
        assertEquals("CURRENT_DAY_MONITORING",result.analysisMode());
        assertTrue(result.warnings().contains("PARTIAL_CURRENT_DAY"));
        assertTrue(result.warnings().contains(
                "INTRADAY_METRICS_ARE_PROVISIONAL_NO_BUDGET_DECISIONS"));
        assertEquals("PURCHASE_ACTION_UNAVAILABLE",
                result.adsetInsights().getFirst().evidenceQuality());
    }

    @Test void historicalPeriodDoesNotClaimCurrentDayAndTomorrowIsRejected() {
        var historical=service.inspect(CAMPAIGN,SINCE,UNTIL,100,3);
        assertFalse(historical.partialCurrentDay());
        assertEquals("HISTORICAL_CONTEXT",historical.analysisMode());
        assertFalse(historical.warnings().contains("PARTIAL_CURRENT_DAY"));
        LocalDate tomorrow=LocalDate.now(java.time.ZoneOffset.UTC).plusDays(1);
        assertEquals("INVALID_DATE_RANGE",assertThrows(MetaApiException.class,
                ()->service.inspect(CAMPAIGN,tomorrow,tomorrow,100,3)).errorCode());
        verify(client,never()).deliveryInsights(eq(CAMPAIGN),eq("ad"),
                eq(tomorrow),eq(tomorrow),anyInt(),any());
    }

}
