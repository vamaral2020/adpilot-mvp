package br.com.adpilot.analytics;

import static org.junit.jupiter.api.Assertions.*;
import br.com.adpilot.analytics.RecommendationModels.DataQuality;
import br.com.adpilot.analytics.RecommendationModels.LinkedGoal;
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
    private static DataQuality quality(Request req,List<DailyCampaign> rows, boolean fresh) {
        int days=(int)java.time.temporal.ChronoUnit.DAYS.between(req.since(),req.until())+1;
        int withRows=(int)rows.stream().map(DailyCampaign::day).distinct().count();
        return new DataQuality("WINDOWS_OBSERVED",days,days,withRows,days-withRows,
                List.of(),List.of(),NOW,fresh,fresh);
    }
    private RecommendationModels.Report run(Request req,List<DailyCampaign> rows) {
        return engine.evaluate(req,rows,new SyncState("SUCCEEDED",NOW),NOW,
                quality(req,rows,true));
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
        assertEquals(2, report.recommendations().getFirst().ruleVersion());
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
                new SyncState(null,null),NOW,quality(req,List.of(row(0,"50","1","100",100,10)),false));
        assertTrue(report.warnings().contains("META_SYNC_NOT_CONFIRMED_CURRENT"));
        assertFalse(report.coverageVerified());
    }


    @Test void incompleteCollectionBlocksFinancialInvestigationsEvenWithLargeSamples() {
        var req=new Request(START,START.plusDays(13),bd("3"),bd("50"));
        var rows=new ArrayList<DailyCampaign>();
        for (int i=0;i<14;i++) rows.add(row(i,"100","1","150",1000,100));
        var q=new DataQuality("PARTIAL_WINDOWS",14,7,14,0,
                List.of(START.plusDays(7)),List.of(),NOW,true,false);
        var report=engine.evaluate(req,rows,new SyncState("SUCCEEDED",NOW),NOW,q);
        assertTrue(contains(report,"DATA_QUALITY_LIMITED"));
        assertFalse(contains(report,"ROAS_BELOW_TARGET"));
        assertFalse(contains(report,"CPA_ABOVE_TARGET"));
        assertFalse(contains(report,"FAVORABLE_PERFORMANCE"));
        assertFalse(contains(report,"CTR_DECLINING"));
        assertTrue(report.warnings().contains("META_COLLECTION_WINDOWS_PARTIAL"));
        assertEquals("REVIEW_DATA",report.recommendations().getFirst().actionCode());
        assertEquals("INSUFFICIENT",report.recommendations().getFirst().confidence());
        assertFalse(report.coverageVerified());
    }

    @Test void staleSyncDoesNotBecomeFreshFromOldCollectionReceipts() {
        var req=new Request(START,START.plusDays(13),bd("3"),bd("50"));
        var rows=new ArrayList<DailyCampaign>();
        for (int i=0;i<14;i++) rows.add(row(i,"100","1","150",1000,100));
        var report=engine.evaluate(req,rows,
                new SyncState("SUCCEEDED",NOW.minusSeconds(172800)),NOW,
                quality(req,rows,false));
        assertTrue(contains(report,"DATA_QUALITY_LIMITED"));
        assertTrue(report.warnings().contains("META_SYNC_STALE_OR_FAILED"));
        assertFalse(contains(report,"ROAS_BELOW_TARGET"));
        assertFalse(contains(report,"CPA_ABOVE_TARGET"));
    }

    @Test void partialHistoryAndLowSampleAreTwoDistinctReasonsNotToChangeBudget() {
        var req=new Request(START,START.plusDays(6),bd("3"),bd("50"));
        var rows=List.of(row(1,"130.88","3","698.05",8239,330));
        var q=new DataQuality("NO_COLLECTION_RECEIPT",7,0,1,0,
                List.of(START),List.of(),null,false,false);
        var report=engine.evaluate(req,rows,new SyncState(null,null),NOW,q);
        assertTrue(contains(report,"DATA_QUALITY_LIMITED"));
        assertTrue(contains(report,"LOW_SAMPLE"));
        assertFalse(contains(report,"ROAS_BELOW_TARGET"));
        assertTrue(report.recommendations().stream().noneMatch(r ->
                r.actionCode().contains("SCALE") || r.actionCode().contains("PAUSE")));
    }

    @Test void freshFullyObservedWindowStillDoesNotAuthorizeAutomaticBudgetChanges() {
        var req=new Request(START,START.plusDays(13),bd("3"),bd("50"));
        var rows=new ArrayList<DailyCampaign>();
        for (int i=0;i<14;i++) rows.add(row(i,"100","1","150",1000,100));
        var report=run(req,rows);
        assertEquals("WINDOWS_OBSERVED",report.quality().status());
        assertTrue(report.quality().analysisEligible());
        assertFalse(report.coverageVerified());
        assertTrue(contains(report,"ROAS_BELOW_TARGET"));
        assertTrue(contains(report,"CPA_ABOVE_TARGET"));
        assertTrue(report.recommendations().stream().noneMatch(r ->
                r.actionCode().contains("PAUSE") || r.actionCode().contains("SCALE")));
    }


    private static LinkedGoal goal(String campaign, String label,
            String cpa,String roas) {
        return new LinkedGoal(java.util.UUID.randomUUID(),"123",campaign,label,
                19900,bd(cpa),bd(roas),NOW);
    }

    @Test void distinctCampaignsUseOnlyTheirOwnManuallyLinkedProductTargets() {
        var req=new Request(START,START.plusDays(6),null,null);
        var rows=new ArrayList<DailyCampaign>();
        for(int i=0;i<7;i++) {
            rows.add(row(i,"100","1","150",1000,100));
            rows.add(new DailyCampaign("123","777","Outro produto","BRL",
                    START.plusDays(i),bd("20"),1000,100,bd("1"),bd("200")));
        }
        var goals=List.of(goal("52596120828588","Vestido azul","52.26","3.81"),
                goal("777","Blusa branca","30","8"));
        var report=engine.evaluate(req,rows,new SyncState("SUCCEEDED",NOW),NOW,
                quality(req,rows,true),goals);
        assertEquals(2,report.linkedGoals().size());
        assertTrue(report.warnings().contains("MANUALLY_LINKED_PRODUCT_GOALS"));
        assertFalse(report.warnings().contains("BUSINESS_GOALS_NOT_CONFIGURED"));
        assertFalse(contains(report,"CONFIGURE_GOALS"));
        assertTrue(report.recommendations().stream().anyMatch(a ->
                "52596120828588".equals(a.campaignId())
                && "ROAS_BELOW_TARGET".equals(a.ruleCode())));
        assertTrue(report.recommendations().stream().anyMatch(a ->
                "52596120828588".equals(a.campaignId())
                && "CPA_ABOVE_TARGET".equals(a.ruleCode())));
        assertTrue(report.recommendations().stream().anyMatch(a ->
                "777".equals(a.campaignId())
                && "WITHIN_TARGETS".equals(a.ruleCode())));
        assertFalse(report.recommendations().stream().anyMatch(a ->
                "777".equals(a.campaignId())
                && ("ROAS_BELOW_TARGET".equals(a.ruleCode())
                    || "CPA_ABOVE_TARGET".equals(a.ruleCode()))));
    }

    @Test void manualOverridesTakePrecedenceOverLinkedProductGoals() {
        var req=new Request(START,START.plusDays(6),bd("1"),bd("200"));
        var rows=new ArrayList<DailyCampaign>();
        for(int i=0;i<7;i++) rows.add(row(i,"100","1","150",1000,100));
        var report=engine.evaluate(req,rows,new SyncState("SUCCEEDED",NOW),NOW,
                quality(req,rows,true),
                List.of(goal("52596120828588","Vestido","52.26","3.81")));
        assertTrue(report.warnings().contains("MANUAL_TARGET_OVERRIDES_LINKED_GOALS"));
        assertTrue(contains(report,"WITHIN_TARGETS"));
        assertFalse(contains(report,"ROAS_BELOW_TARGET"));
        assertFalse(contains(report,"CPA_ABOVE_TARGET"));
        assertEquals(bd("52.26"),report.linkedGoals().getFirst().targetCpa());
    }

    @Test void noCampaignProductLinkDoesNotInheritAnotherCampaignsGoal() {
        var req=new Request(START,START.plusDays(6),null,null);
        var rows=new ArrayList<DailyCampaign>();
        for(int i=0;i<7;i++) rows.add(row(i,"100","1","150",1000,100));
        var unrelated=goal("777","Outro","52.26","3.81");
        var report=engine.evaluate(req,rows,new SyncState("SUCCEEDED",NOW),NOW,
                quality(req,rows,true),List.of(unrelated));
        assertTrue(report.linkedGoals().isEmpty());
        assertTrue(contains(report,"CONFIGURE_GOALS"));
        assertFalse(contains(report,"ROAS_BELOW_TARGET"));
        assertFalse(contains(report,"CPA_ABOVE_TARGET"));
    }

    @Test void linkedThreePurchaseCampaignStillReceivesLowSampleAndNoScaling() {
        var req=new Request(START,START.plusDays(6),null,null);
        var rows=List.of(row(2,"30","1","100",800,80),
                row(3,"50","1","120",1000,100),
                row(4,"50.88","1","150",1000,100));
        var report=engine.evaluate(req,rows,new SyncState("SUCCEEDED",NOW),NOW,
                quality(req,rows,true),
                List.of(goal("52596120828588","Vestido","52.26","3.81")));
        assertTrue(contains(report,"LOW_SAMPLE"));
        assertFalse(contains(report,"ROAS_BELOW_TARGET"));
        assertFalse(contains(report,"CPA_ABOVE_TARGET"));
        assertFalse(report.coverageVerified());
        assertTrue(report.recommendations().stream().allMatch(a->
                a.actionCode().equals("MONITOR_ONLY")));
    }

    @Test void staleDataSuppressesFinancialAdviceEvenWithSavedProductGoal() {
        var req=new Request(START,START.plusDays(6),null,null);
        var rows=new ArrayList<DailyCampaign>();
        for(int i=0;i<7;i++) rows.add(row(i,"100","1","150",1000,100));
        var report=engine.evaluate(req,rows,new SyncState("FAILED",NOW),NOW,
                quality(req,rows,false),
                List.of(goal("52596120828588","Vestido","52.26","3.81")));
        assertTrue(contains(report,"DATA_QUALITY_LIMITED"));
        assertFalse(contains(report,"ROAS_BELOW_TARGET"));
        assertFalse(contains(report,"CPA_ABOVE_TARGET"));
    }

}
