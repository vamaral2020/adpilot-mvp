package br.com.adpilot.analytics;

import static org.junit.jupiter.api.Assertions.*;
import br.com.adpilot.analytics.RecommendationModels.DailyCampaign;
import br.com.adpilot.analytics.RecommendationModels.Request;
import br.com.adpilot.analytics.RecommendationModels.SyncState;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class RecommendationRuleEngineTest {
    private final RecommendationRuleEngine engine = new RecommendationRuleEngine();
    private static final LocalDate START = LocalDate.of(2026,10,1);
    private static final Instant NOW = Instant.parse("2026-10-21T15:00:00Z");
    private static BigDecimal bd(String x) { return new BigDecimal(x); }
    private static DailyCampaign row(int offset,String spend,String purchases,String revenue,
            long impressions,long clicks) {
        return new DailyCampaign("123","52596120828588","Vendas 102 peças","BRL",
                START.plusDays(offset),bd(spend),impressions,clicks,bd(purchases),bd(revenue));
    }
    private RecommendationModels.Report run(Request req,List<DailyCampaign> rows) {
        return engine.evaluate(req,rows,new SyncState("SUCCEEDED",NOW),NOW);
    }
    private static boolean contains(RecommendationModels.Report report,String rule) {
        return report.recommendations().stream().anyMatch(r -> r.ruleCode().equals(rule));
    }

    @Test void actualThreePurchasesCannotTriggerBudgetScaling() {
        var req=new Request(START,START.plusDays(6),bd("3"),bd("50"));
        var rows=List.of(row(2,"0.55","0","0",20,1),
                row(3,"130.33","1","201.05",8239,330),
                row(4,"0","0","0",0,0),
                row(5,"0","2","497.00",0,0),
                row(6,"0","0","0",0,0));
        var report=run(req,rows);
        assertFalse(report.coverageVerified());
        assertTrue(contains(report,"LOW_SAMPLE"));
        assertEquals("INSUFFICIENT",report.recommendations().getFirst().confidence());
        assertTrue(report.recommendations().stream().noneMatch(r ->
                r.actionCode().contains("SCALE") || r.actionCode().contains("PAUSE")));
        assertTrue(report.warnings().contains("META_ATTRIBUTION_CAN_CHANGE"));
        var explanation=report.recommendations().getFirst().justification();
        assertTrue(explanation.contains("3 compras"),explanation);
        assertTrue(explanation.contains("5 dias"),explanation);
        assertFalse(explanation.contains("3.0000"),explanation);
        assertFalse(explanation.contains("compra(s)"),explanation);
        assertEquals(0,report.recommendations().getFirst().evidence().stream()
                .filter(v->v.name().equals("ATTRIBUTED_PURCHASES")).findFirst()
                .orElseThrow().value().compareTo(bd("3")));
    }

    @Test void fractionalMetaAttributionIsPresentedWithoutFakeIntegerRounding() {
        var req=new Request(START,START.plusDays(6),bd("3"),bd("50"));
        var report=run(req,List.of(row(0,"20","1.5000","100",100,10)));
        var explanation=report.recommendations().getFirst().justification();
        assertTrue(explanation.contains("1,5 compras"),explanation);
        assertTrue(explanation.contains("1 dia"),explanation);
        assertFalse(explanation.contains("1.5000"),explanation);
    }

    @Test void adequateSampleBelowFinancialGoalsGeneratesExplainedInvestigationOnly() {
        var req=new Request(START,START.plusDays(13),bd("3"),bd("50"));
        var rows=new ArrayList<DailyCampaign>();
        for(int i=0;i<14;i++) rows.add(row(i,"100","1","150",1000,100));
        var report=run(req,rows);
        assertTrue(contains(report,"ROAS_BELOW_TARGET"));
        assertTrue(contains(report,"CPA_ABOVE_TARGET"));
        assertFalse(contains(report,"LOW_SAMPLE"));
        assertTrue(report.recommendations().stream().allMatch(r ->
                r.confidence().equals("LOW") && r.actionCode().equals("INVESTIGATE")));
        assertFalse(report.coverageVerified());
        assertEquals(1, report.recommendations().getFirst().ruleVersion());
    }

    @Test void favorableTenPurchasesStillNeverRecommendsBudgetIncrease() {
        var req=new Request(START,START.plusDays(13),bd("3"),bd("50"));
        var rows=new ArrayList<DailyCampaign>();
        for(int i=0;i<14;i++) rows.add(row(i,"20","1","200",1000,100));
        var report=run(req,rows);
        assertTrue(contains(report,"FAVORABLE_PERFORMANCE"));
        assertTrue(report.recommendations().stream().allMatch(r ->
                r.actionCode().equals("MONITOR_ONLY")));
    }

    @Test void highSpendWithoutConversionsIsNotMisclassifiedAsProvenTrackingIssue() {
        var req=new Request(START,START.plusDays(6),null,bd("40"));
        var report=run(req,List.of(row(0,"40","0","0",600,10),
                row(1,"40","0","0",600,10),row(2,"20","0","0",600,10)));
        assertTrue(contains(report,"SPEND_WITHOUT_PURCHASES"));
        assertTrue(contains(report,"LOW_SAMPLE"));
        assertTrue(report.recommendations().stream().anyMatch(r ->
                r.ruleCode().equals("SPEND_WITHOUT_PURCHASES") &&
                r.justification().contains("Verifique o rastreamento")));
    }

    @Test void noDailyRowsMeansMissingCoverageNotZeroPurchases() {
        var req=new Request(START,START.plusDays(6),null,null);
        var report=run(req,List.of());
        assertTrue(contains(report,"NO_META_INSIGHTS"));
        assertTrue(contains(report,"CONFIGURE_GOALS"));
        assertTrue(report.recommendations().stream().allMatch(r->r.evidence().isEmpty()));
        assertTrue(report.warnings().contains("HISTORICAL_COVERAGE_NOT_VERIFIED"));
    }

    @Test void mixedCurrencyOrDuplicateDayIsRejectedBeforeFinancialAnalysis() {
        var req=new Request(START,START.plusDays(10),bd("2"),bd("50"));
        var a=row(0,"100","4","300",100,10);
        var mixed=new DailyCampaign(a.accountId(),a.campaignId(),a.campaignName(),
                "USD",START.plusDays(1),bd("10"),100,10,bd("2"),bd("100"));
        for(var rows:List.of(List.of(a,mixed),List.of(a,a))) {
            var report=run(req,rows);
            assertTrue(contains(report,"INVALID_CAMPAIGN_DATA"));
            assertFalse(contains(report,"FAVORABLE_PERFORMANCE"));
            assertEquals("INSUFFICIENT",report.recommendations().getFirst().confidence());
        }
    }

    @Test void ctrDeclineIsBasedOnComparableImpressionsAndTotalClicks() {
        var req=new Request(START,START.plusDays(13),null,null);
        var rows=new ArrayList<DailyCampaign>();
        for(int i=0;i<14;i++) {
            rows.add(row(i,"20","1","100",100,i<7?20:10));
        }
        var report=run(req,rows);
        assertTrue(contains(report,"CTR_DECLINING"));
        assertTrue(report.recommendations().stream()
                .filter(a -> a.ruleCode().equals("CTR_DECLINING"))
                .allMatch(a->a.limitations().contains("ALL_CLICKS_NOT_LINK_CLICKS")));
    }

    @Test void noSpendInStoredRowsIsNotEquivalentToCampaignInactive() {
        var req=new Request(START,START.plusDays(6),bd("2"),null);
        var report=run(req,List.of(row(0,"0","1","200",10,1)));
        assertTrue(contains(report,"NO_RECORDED_SPEND"));
        assertFalse(contains(report,"FAVORABLE_PERFORMANCE"));
    }

    @Test void missingLatestMetaSyncAlwaysLowersConfidence() {
        var req=new Request(START,START.plusDays(6),null,null);
        var report=engine.evaluate(req,List.of(row(0,"50","1","100",100,10)),
                new SyncState(null,null),NOW);
        assertTrue(report.warnings().contains("META_SYNC_NOT_CONFIRMED_CURRENT"));
        assertFalse(report.coverageVerified());
    }
}
