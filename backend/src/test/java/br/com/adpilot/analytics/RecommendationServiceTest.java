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
    private final Clock clock=Clock.fixed(Instant.parse("2026-10-21T15:00:00Z"),ZoneOffset.UTC);
    private final RecommendationService service=new RecommendationService(
            repository,new RecommendationRuleEngine(),clock);
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
        verifyNoInteractions(repository);
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
