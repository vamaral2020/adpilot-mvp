package br.com.adpilot.integrations.meta;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class MetaFunnelDiagnosticsServiceTest {
    private final MetaDeliveryInterpreterService interpreter=mock(MetaDeliveryInterpreterService.class);
    private final MetaFunnelDiagnosticsService service=new MetaFunnelDiagnosticsService(interpreter);
    private static final LocalDate FROM=LocalDate.of(2026,10,1);
    private static final LocalDate UNTIL=LocalDate.of(2026,10,7);

    private static BigDecimal d(String s){return s==null?null:new BigDecimal(s);}

    private static MetaDeliveryInterpreterService.Evidence evidence(
            String level,String id,String set,String spend,Long link,
            String visits,String carts,String checkouts,String purchase) {
        return new MetaDeliveryInterpreterService.Evidence(
                level,id,set,d(spend),1000L,100L,link,
                d(visits),d(carts),d(checkouts),d(purchase),null,
                "UNKNOWN",null,null,null,null,
                purchase==null?"PURCHASE_ACTION_UNAVAILABLE":"ATTRIBUTED_SIGNALS_OBSERVED");
    }

    private static MetaDeliveryInterpreterService.Report source(
            String objective,boolean complete,boolean partial,
            List<MetaDeliveryInterpreterService.Evidence> sets,
            List<MetaDeliveryInterpreterService.Evidence> ads) {
        var cover=new MetaDeliveryInterpreterService.Coverage(complete,1,sets.size());
        var adCover=new MetaDeliveryInterpreterService.Coverage(complete,1,ads.size());
        return new MetaDeliveryInterpreterService.Report(
                "123",objective,"ACTIVE","BRL",FROM,UNTIL,
                partial?"CURRENT_DAY_MONITORING":"HISTORICAL_CONTEXT",
                partial,complete,cover,cover,adCover,
                List.of(),sets,ads,List.of(),List.of());
    }

    @Test void ratiosUseAggregatedEventsAndSpendWithoutImplyingUserCohorts(){
        var one=evidence("adset","111","111","40.00",100L,"80","10","4","2");
        var result=service.analyze(source("OUTCOME_SALES",true,false,List.of(one),
                List.of(evidence("ad","222","111","40",100L,"80","10","4","2"))));
        var funnel=result.campaign();
        assertEquals(d("40.00"),funnel.spend());
        assertEquals(100L,funnel.linkClicks());
        assertEquals(d("80.0000"),funnel.visitsPerLinkClickPercent());
        assertEquals(d("12.5000"),funnel.cartsPerVisitPercent());
        assertEquals(d("40.0000"),funnel.checkoutsPerCartPercent());
        assertEquals(d("50.0000"),funnel.purchasesPerCheckoutPercent());
        assertEquals(d("0.5000"),funnel.costPerLandingPageView());
        assertEquals(d("4.0000"),funnel.costPerAddToCart());
        assertEquals(d("10.0000"),funnel.costPerInitiatedCheckout());
        assertEquals(d("20.0000"),funnel.attributedCpa());
        assertEquals(1,result.adsets().size());
        assertEquals(1,result.ads().size());
        assertEquals("111",result.ads().getFirst().adsetId());
        assertTrue(result.warnings().contains("FUNNEL_RATIOS_NOT_CAUSAL_CONVERSION_RATES"));
        assertFalse(result.toString().contains("PRIVATE"));
    }

    @Test void missingEventInOneSetPoisonsAggregateButPreservesObservedRows(){
        var a=evidence("adset","111","111","10",50L,"40","4","2","1");
        var b=evidence("adset","222","222","20",null,"20",null,null,null);
        var result=service.analyze(source("OUTCOME_SALES",true,false,List.of(a,b),List.of()));
        assertEquals(d("30"),result.campaign().spend());
        assertNull(result.campaign().linkClicks());
        assertNull(result.campaign().addToCarts());
        assertNull(result.campaign().initiatedCheckouts());
        assertNull(result.campaign().attributedPurchases());
        assertNull(result.campaign().cartsPerVisitPercent());
        assertNull(result.campaign().attributedCpa());
        assertEquals(d("4"),result.adsets().getFirst().addToCarts());
        assertNull(result.adsets().get(1).addToCarts());
        assertTrue(result.warnings().contains("PURCHASE_ACTION_UNAVAILABLE_NOT_ZERO"));
        assertTrue(result.findings().stream().anyMatch(f->
                f.ruleCode().equals("PURCHASE_ACTION_NOT_REPORTED")));
    }

    @Test void explicitZeroIsNotMissingAndZeroDenominatorDoesNotProduceInfinity(){
        var a=evidence("adset","111","111","5",0L,"0","0","0","0");
        var result=service.analyze(source("OUTCOME_SALES",true,false,List.of(a),List.of()));
        assertEquals(BigDecimal.ZERO,result.campaign().attributedPurchases());
        assertEquals(BigDecimal.ZERO,result.campaign().initiatedCheckouts());
        assertNull(result.campaign().visitsPerLinkClickPercent());
        assertNull(result.campaign().cartsPerVisitPercent());
        assertNull(result.campaign().purchasesPerCheckoutPercent());
        assertNull(result.campaign().attributedCpa());
        assertFalse(result.findings().stream().anyMatch(f->
                f.ruleCode().equals("PURCHASE_ACTION_NOT_REPORTED")));
    }

    @Test void currentDayIsProvisionalEvenWithCompletePagination(){
        var r=service.analyze(source("OUTCOME_SALES",true,true,
                List.of(evidence("adset","111","111","84",232L,"208","7",null,null)),
                List.of()));
        assertTrue(r.complete());
        assertTrue(r.partialCurrentDay());
        assertEquals("CURRENT_DAY_MONITORING",r.analysisMode());
        assertTrue(r.warnings().contains("FUNNEL_CURRENT_DAY_PROVISIONAL"));
        assertTrue(r.findings().stream().anyMatch(f->
                f.ruleCode().equals("PROVISIONAL_CURRENT_DAY")
                        &&f.confidence().equals("INSUFFICIENT")));
    }

    @Test void incompletePaginationDoesNotIssuePerformanceVerdicts(){
        var r=service.analyze(source("OUTCOME_SALES",false,false,
                List.of(evidence("adset","111","111","80",100L,"150","1",null,null)),
                List.of()));
        assertFalse(r.complete());
        assertEquals(1,r.findings().size());
        assertEquals("FUNNEL_INCOMPLETE_EVIDENCE",r.findings().getFirst().ruleCode());
        assertTrue(r.warnings().contains("FUNNEL_PAGINATION_INCOMPLETE"));
    }

    @Test void trafficObjectiveDoesNotUseSalesPerformanceThreshold(){
        var r=service.analyze(source("OUTCOME_TRAFFIC",true,false,
                List.of(evidence("adset","111","111","76",853L,"675","1",null,null)),
                List.of()));
        assertTrue(r.findings().stream().anyMatch(f->
                f.ruleCode().equals("TRAFFIC_OPTIMIZES_VISITS_NOT_PURCHASES")));
        assertFalse(r.findings().stream().anyMatch(f->
                f.ruleCode().equals("SPARSE_CART_ACTIONS")));
        assertEquals(d("76.0000"),r.campaign().costPerAddToCart());
    }

    @Test void eventCountCanIncreaseWithoutAssumingFraudOrBrokenSite(){
        var r=service.analyze(source("OUTCOME_SALES",true,false,
                List.of(evidence("adset","111","111","10",15L,"20","5","6","2")),
                List.of()));
        assertTrue(r.campaign().warnings().contains("DOWNSTREAM_EVENTS_EXCEED_UPSTREAM"));
        assertTrue(r.findings().stream().anyMatch(f->
                f.ruleCode().equals("NON_MONOTONIC_EVENT_COUNTS")));
    }

    @Test void emptyInsightIsUnknownNotZeroAndNoComparisonsAreIssued(){
        var r=service.analyze(source("OUTCOME_SALES",true,false,List.of(),List.of()));
        assertNull(r.campaign().spend());
        assertNull(r.campaign().landingPageViews());
        assertNull(r.campaign().attributedPurchases());
        assertEquals("FUNNEL_INCOMPLETE_EVIDENCE",r.findings().getFirst().ruleCode());
        assertTrue(r.warnings().contains("NO_ADSET_INSIGHT_ROWS"));
    }

    @Test void endpointDelegatesToExistingBoundedOwnershipCheckedDelivery(){
        var raw=source("OUTCOME_SALES",true,false,List.of(),List.of());
        when(interpreter.inspect("123",FROM,UNTIL,100,3)).thenReturn(raw);
        var response=service.inspect("123",FROM,UNTIL,100,3);
        assertEquals("123",response.campaignId());
        verify(interpreter,times(1)).inspect("123",FROM,UNTIL,100,3);
        verifyNoMoreInteractions(interpreter);
    }
    @Test void aggregatedSparseDeliveryNeverTurnsMissingSpendIntoZeroOrLosesConversions(){
        var conversionOnly=new MetaDeliveryInterpreterService.Evidence(
                "adset","111","111",null,null,null,null,
                null,null,new BigDecimal("3"),new BigDecimal("2"),null,
                "UNKNOWN",null,null,null,null,"DELIVERY_METRICS_UNAVAILABLE");
        var r=service.analyze(source("OUTCOME_SALES",true,false,
                List.of(conversionOnly),List.of()));
        assertNull(r.campaign().spend());
        assertNull(r.campaign().impressions());
        assertNull(r.campaign().allClicks());
        assertEquals(new BigDecimal("2"),r.campaign().attributedPurchases());
        assertEquals(new BigDecimal("3"),r.campaign().initiatedCheckouts());
        assertNull(r.campaign().attributedCpa());
        assertNull(r.campaign().costPerInitiatedCheckout());
        assertTrue(r.campaign().warnings().contains("SPEND_UNAVAILABLE_NOT_ZERO"));
        assertTrue(r.warnings().contains("DELIVERY_METRICS_UNAVAILABLE_NOT_ZERO"));
    }

    @Test void oneMissingSpendInTwoSetsMakesCampaignSpendUnknownButKeepsValidLocalValue(){
        var a=evidence("adset","111","111","10",30L,"20","5","2","1");
        var b=evidence("adset","222","222",null,30L,"15","2","1","1");
        var r=service.analyze(source("OUTCOME_SALES",true,false,List.of(a,b),List.of()));
        assertNull(r.campaign().spend());
        assertEquals(2000L,r.campaign().impressions());
        assertEquals(new BigDecimal("2"),r.campaign().attributedPurchases());
        assertNull(r.campaign().attributedCpa());
        assertEquals(new BigDecimal("10"),r.adsets().getFirst().spend());
        assertNull(r.adsets().get(1).spend());
    }

    @Test void zeroPeriodSpendWithDelayedAttributedPurchaseHasUnknownCpa() {
        var row=evidence("adset","111","111","0",0L,"0","0","0","2");
        var r=service.analyze(source("OUTCOME_SALES",true,false,List.of(row),List.of()));
        assertEquals(BigDecimal.ZERO,r.campaign().spend());
        assertEquals(new BigDecimal("2"),r.campaign().attributedPurchases());
        assertNull(r.campaign().attributedCpa());
        assertTrue(r.warnings().contains("ZERO_PERIOD_SPEND_WITH_ATTRIBUTED_PURCHASES"));
    }

}
