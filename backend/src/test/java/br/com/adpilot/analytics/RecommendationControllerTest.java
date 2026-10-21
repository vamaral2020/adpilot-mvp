package br.com.adpilot.analytics;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import br.com.adpilot.analytics.RecommendationModels.Report;
import br.com.adpilot.analytics.RecommendationModels.Request;
import br.com.adpilot.analytics.RecommendationModels.Stored;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RecommendationControllerTest {
    private final RecommendationService service=mock(RecommendationService.class);
    private final RecommendationController controller=new RecommendationController(service);
    private final LocalDate from=LocalDate.of(2026,10,14);
    private final LocalDate until=LocalDate.of(2026,10,20);
    private final Request input=new Request(from,until,new BigDecimal("3"),new BigDecimal("50"));
    private final Report report=new Report(from,until,input.targetRoas(),input.targetCpa(),false,
            Instant.parse("2026-10-21T12:00:00Z"),"SUCCEEDED",
            Instant.parse("2026-10-21T11:00:00Z"),List.of("HISTORICAL_COVERAGE_NOT_VERIFIED"),
            List.of());

    @Test void previewIsReadOnlyAndDisablesCaching() {
        when(service.preview(input)).thenReturn(report);
        var response=controller.preview(from,until,input.targetRoas(),input.targetCpa());
        assertEquals(200,response.getStatusCode().value());
        assertEquals("no-store",response.getHeaders().getCacheControl());
        assertEquals(report,response.getBody());
        verify(service).preview(input);
        verify(service,never()).saveEvaluation(any());
    }

    @Test void explicitEvaluationPersistsOnlyViaServiceAndDisablesCaching() {
        var id=UUID.randomUUID();
        when(service.saveEvaluation(input)).thenReturn(new Stored(id,report));
        var response=controller.evaluate(input);
        assertEquals("no-store",response.getHeaders().getCacheControl());
        assertEquals(id,response.getBody().evaluationId());
        verify(service).saveEvaluation(input);
    }
}
