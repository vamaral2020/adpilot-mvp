package br.com.adpilot.analytics;

import static org.junit.jupiter.api.Assertions.*;
import br.com.adpilot.analytics.AnalyticsHistoryModels.MetaWindow;
import br.com.adpilot.analytics.RecommendationModels.DailyCampaign;
import br.com.adpilot.analytics.RecommendationModels.Request;
import br.com.adpilot.analytics.RecommendationModels.SyncState;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class RecommendationDataQualityAssessmentTest {
    private final RecommendationDataQualityAssessment assessor=new RecommendationDataQualityAssessment();
    private static final LocalDate FROM=LocalDate.of(2026,10,14);
    private static final LocalDate UNTIL=LocalDate.of(2026,10,21);
    private static final Instant NOW=Instant.parse("2026-10-22T21:17:00Z");
    private static final Instant DONE=Instant.parse("2026-10-22T21:15:20Z");
    private static final Request REQUEST=new Request(FROM,UNTIL,null,null);
    private static DailyCampaign row(LocalDate day,String account) {
        return new DailyCampaign(account,"52596120828588","Campanha","BRL",day,
                new BigDecimal("5"),100,10,BigDecimal.ZERO,BigDecimal.ZERO);
    }
    private static List<MetaWindow> full() {
        return List.of(new MetaWindow("497208073675481",
                LocalDate.of(2026,9,22),UNTIL,DONE));
    }
    private static SyncState sync() { return new SyncState("SUCCEEDED",DONE); }

    @Test void realEightDayReceiptIsFullyObservedButNotFinanciallyCertified() {
        var result=assessor.assess(REQUEST,List.of(
                row(FROM.plusDays(2),"497208073675481"),
                row(FROM.plusDays(3),"497208073675481"),
                row(FROM.plusDays(4),"497208073675481"),
                row(FROM.plusDays(5),"497208073675481"),
                row(FROM.plusDays(6),"497208073675481"),
                row(FROM.plusDays(7),"497208073675481")),
                full(),sync(),NOW);
        assertEquals("WINDOWS_OBSERVED",result.status());
        assertEquals(8,result.requestedDays());
        assertEquals(8,result.collectedDays());
        assertEquals(6,result.daysWithRows());
        assertEquals(2,result.daysWithoutRows());
        assertEquals(List.of(FROM,FROM.plusDays(1)),result.collectedWithoutRows());
        assertTrue(result.uncollectedDates().isEmpty());
        assertTrue(result.syncFresh());
        assertTrue(result.analysisEligible());
    }

    @Test void oldRowsWithoutReceiptsRemainUnverified() {
        var result=assessor.assess(REQUEST,List.of(row(FROM.plusDays(3),"497208073675481")),
                List.of(),sync(),NOW);
        assertEquals("NO_COLLECTION_RECEIPT",result.status());
        assertEquals(0,result.collectedDays());
        assertEquals(8,result.uncollectedDates().size());
        assertFalse(result.analysisEligible());
        assertFalse(result.syncFresh());
    }

    @Test void overlappingReceiptsCountDatesOnceAndIdentifyUncollectedDays() {
        var windows=List.of(
                new MetaWindow("497208073675481",FROM,FROM.plusDays(2),DONE),
                new MetaWindow("497208073675481",FROM.plusDays(2),FROM.plusDays(4),DONE));
        var result=assessor.assess(REQUEST,List.of(row(FROM.plusDays(2),"497208073675481")),
                windows,sync(),NOW);
        assertEquals("PARTIAL_WINDOWS",result.status());
        assertEquals(5,result.collectedDays());
        assertEquals(3,result.uncollectedDates().size());
        assertEquals(4,result.daysWithoutRows());
        assertFalse(result.analysisEligible());
    }

    @Test void differentAccountsCannotUseReceiptsFromAnotherAccount() {
        var result=assessor.assess(REQUEST,List.of(row(FROM.plusDays(2),"999")),
                full(),sync(),NOW);
        assertEquals("ACCOUNT_MISMATCH",result.status());
        assertEquals(0,result.collectedDays());
        assertFalse(result.analysisEligible());
    }

    @Test void multipleAccountReceiptsBlockComparison() {
        var windows=List.of(full().getFirst(),
                new MetaWindow("999",FROM,UNTIL,DONE));
        var result=assessor.assess(REQUEST,List.of(row(FROM.plusDays(2),"497208073675481")),
                windows,sync(),NOW);
        assertEquals("MULTIPLE_ACCOUNTS",result.status());
        assertEquals(0,result.collectedDays());
        assertFalse(result.analysisEligible());
    }

    @Test void missingRowsInsideFetchedDaysNeverRepresentProvenZeroSales() {
        var result=assessor.assess(REQUEST,List.of(),full(),sync(),NOW);
        assertEquals("WINDOWS_OBSERVED",result.status());
        assertEquals(8,result.daysWithoutRows());
        assertEquals(0,result.daysWithRows());
        assertTrue(result.analysisEligible()); // eligible to classify data, not invented conversions
    }

    @Test void failedSyncAndTwoDayOldSuccessCannotPassFreshnessGate() {
        for(var state:List.of(new SyncState("FAILED",DONE),
                new SyncState("SUCCEEDED",NOW.minusSeconds(172800)),
                new SyncState(null,null))) {
            var quality=assessor.assess(REQUEST,List.of(),full(),state,NOW);
            assertEquals("WINDOWS_OBSERVED",quality.status());
            assertFalse(quality.syncFresh());
            assertFalse(quality.analysisEligible());
        }
    }

    @Test void staleWindowReceiptCannotBeRescuedByNewSyncState() {
        var oldWindow=new MetaWindow("497208073675481",FROM,UNTIL,
                NOW.minusSeconds(172800));
        var result=assessor.assess(REQUEST,List.of(),List.of(oldWindow),sync(),NOW);
        assertFalse(result.syncFresh());
        assertFalse(result.analysisEligible());
    }

    @Test void currentDayIsNotConsolidatedEvenWhenWindowCoversIt() {
        var today=LocalDate.of(2026,10,22);
        var req=new Request(FROM,today,null,null);
        var window=new MetaWindow("497208073675481",FROM,today,DONE);
        var result=assessor.assess(req,List.of(),List.of(window),sync(),NOW);
        assertEquals("CURRENT_DAY_INCOMPLETE",result.status());
        assertFalse(result.analysisEligible());
    }

    @Test void futureTimestampsAreNotConsideredFresh() {
        var future=NOW.plusSeconds(86400);
        var result=assessor.assess(REQUEST,List.of(),
                List.of(new MetaWindow("497208073675481",FROM,UNTIL,future)),
                new SyncState("SUCCEEDED",future),NOW);
        assertFalse(result.syncFresh());
    }
}
