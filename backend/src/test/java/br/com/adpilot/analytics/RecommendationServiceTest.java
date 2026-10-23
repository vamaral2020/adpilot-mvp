package br.com.adpilot.analytics;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import br.com.adpilot.analytics.RecommendationModels.Request;
import br.com.adpilot.analytics.RecommendationModels.SyncState;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class RecommendationServiceTest {
    private final RecommendationRepository repository=mock(RecommendationRepository.class);
    private final AnalyticsHistoryRepository historyRepository=mock(AnalyticsHistoryRepository.class);
    private final EconomicGoalRepository economicGoals=mock(EconomicGoalRepository.class);
    private final Clock clock=Clock.fixed(Instant.parse("2026-10-21T15:00:00Z"),ZoneOffset.UTC);
    private final RecommendationService service=new RecommendationService(
            repository,new RecommendationRuleEngine(),historyRepository,
            new RecommendationDataQualityAssessment(),economicGoals,clock);
    private static final LocalDate FROM=LocalDate.of(2026,10,14);
    private static final LocalDate TO=LocalDate.of(2026,10,20);

    @Test void rejectsBadDatesAndUnsafeTargetsBeforeQueryingDatabase() {
        var bad=List.of(
                new Request(TO,FROM,null,null),
                new Request(FROM,LocalDate.of(2026,10,22),null,null),
                new Request(LocalDate.of(2026,1,1),TO,null,null),
                new Request(null,TO,null,null),
                new Request(FROM,TO,BigDecimal.ZERO,null),
                new Request(FROM,TO,new BigDecimal("101"),null),
                new Request(FROM,TO,null,new BigDecimal("-1")),
                new Request(FROM,TO,null,new BigDecimal("1000001")),
                new Request(FROM,TO,new BigDecimal("3.12345"),null));
        for(Request request:bad){
            var error=assertThrows(ResponseStatusException.class,()->service.preview(request));
            assertEquals(400,error.getStatusCode().value());
        }
        verifyNoInteractions(repository,historyRepository,economicGoals);
    }

    @Test void previewReadsOnlyAndIncludesAttributionCaveats() {
        when(repository.dailyCampaigns(FROM,TO)).thenReturn(List.of());
        when(repository.metaSyncState()).thenReturn(new SyncState("SUCCEEDED",clock.instant()));
        var report=service.preview(new Request(FROM,TO,new BigDecimal("3"),null));
        assertFalse(report.coverageVerified());
        assertTrue(report.warnings().contains("HISTORICAL_COVERAGE_NOT_VERIFIED"));
        assertTrue(report.warnings().contains("META_PURCHASES_NOT_RECONCILED_WITH_STORE"));
        verify(repository).dailyCampaigns(FROM,TO);
        verify(repository).metaSyncState();
        verify(historyRepository).metaWindows(FROM,TO);
        verify(economicGoals).linkedGoals();
        assertEquals("NO_COLLECTION_RECEIPT",report.quality().status());
        verify(repository,never()).store(any());
    }


    @Test void successfulReceiptIsIncludedAsAdvisoryOnlyQualityContext() {
        var completed=Instant.parse("2026-10-21T14:55:00Z");
        when(repository.dailyCampaigns(FROM,TO)).thenReturn(List.of());
        when(repository.metaSyncState()).thenReturn(new SyncState("SUCCEEDED",completed));
        when(historyRepository.metaWindows(FROM,TO)).thenReturn(List.of(
                new br.com.adpilot.analytics.AnalyticsHistoryModels.MetaWindow(
                        "123",FROM,TO,completed)));
        var report=service.preview(new Request(FROM,TO,null,null));
        assertEquals("WINDOWS_OBSERVED",report.quality().status());
        assertEquals(7,report.quality().collectedDays());
        assertTrue(report.quality().analysisEligible());
        assertFalse(report.coverageVerified());
        verify(repository,never()).store(any());
    }


    @Test void passesOnlyLocalLinkedGoalsThroughTheReadOnlyPreview() {
        var when=Instant.parse("2026-10-21T14:56:00Z");
        var row=new br.com.adpilot.analytics.RecommendationModels.DailyCampaign(
                "123","456","Produto A","BRL",FROM,new BigDecimal("100"),
                1000,100,BigDecimal.ONE,new BigDecimal("150"));
        when(repository.dailyCampaigns(FROM,TO)).thenReturn(List.of(row));
        when(repository.metaSyncState()).thenReturn(new SyncState("SUCCEEDED",when));
        when(historyRepository.metaWindows(FROM,TO)).thenReturn(List.of(
                new br.com.adpilot.analytics.AnalyticsHistoryModels.MetaWindow(
                        "123",FROM,TO,when)));
        var saved=new br.com.adpilot.analytics.RecommendationModels.LinkedGoal(
                UUID.randomUUID(),"123","456","Vestido",19900,
                new BigDecimal("52.26"),new BigDecimal("3.81"),when);
        when(economicGoals.linkedGoals()).thenReturn(List.of(saved));
        var report=service.preview(new Request(FROM,TO,null,null));
        assertEquals(1,report.linkedGoals().size());
        assertEquals(saved.goalId(),report.linkedGoals().getFirst().goalId());
        assertFalse(report.warnings().contains("BUSINESS_GOALS_NOT_CONFIGURED"));
        verify(repository,never()).store(any());
    }

    @Test void explicitPostCreatesLocalEvaluationOnly() {
        when(repository.dailyCampaigns(FROM,TO)).thenReturn(List.of());
        when(repository.metaSyncState()).thenReturn(new SyncState(null,null));
        var id=UUID.randomUUID();
        when(repository.store(any())).thenReturn(id);
        var stored=service.saveEvaluation(new Request(FROM,TO,null,null));
        assertEquals(id,stored.evaluationId());
        assertTrue(stored.report().recommendations().stream()
                .anyMatch(x->x.ruleCode().equals("CONFIGURE_GOALS")));
        verify(repository).store(stored.report());
    }

    @Test void currentDayIsExplicitlyMarkedIncomplete() {
        var today=LocalDate.of(2026,10,21);
        when(repository.dailyCampaigns(FROM,today)).thenReturn(List.of());
        when(repository.metaSyncState()).thenReturn(new SyncState(null,null));
        var report=service.preview(new Request(FROM,today,null,null));
        assertTrue(report.warnings().contains("CURRENT_DAY_NOT_COMPLETE"));
    }
}
