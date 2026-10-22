package br.com.adpilot.analytics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import br.com.adpilot.analytics.AnalyticsHistoryModels.History;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

class AnalyticsHistoryControllerTest {
    private final AnalyticsHistoryService service = mock(AnalyticsHistoryService.class);
    private final AnalyticsHistoryController controller = new AnalyticsHistoryController(service);

    @Test
    void delegatesToServiceAndDisablesResponseCaching() {
        var since = LocalDate.of(2026, 10, 1);
        var until = LocalDate.of(2026, 10, 7);
        var payload = new History(since, until, List.of(), List.of(), List.of(), List.of(), false,
                List.of("HISTORICAL_COVERAGE_NOT_VERIFIED"),null);
        when(service.history(since, until)).thenReturn(payload);

        var response = controller.history(since, until);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(payload, response.getBody());
        assertEquals("no-store", response.getHeaders().getCacheControl());
        verify(service).history(since, until);
    }
}
