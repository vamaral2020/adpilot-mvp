package br.com.adpilot.analytics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import static org.mockito.Mockito.mock;

class AnalyticsHistoryControllerTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final AnalyticsHistoryController controller = new AnalyticsHistoryController(jdbc);

    @Test
    void rejectsReversedDateRangeBeforeDatabaseAccess() {
        var e = assertThrows(ResponseStatusException.class, () ->
                controller.history(LocalDate.of(2026, 10, 20), LocalDate.of(2026, 10, 19)));
        assertEquals(400, e.getStatusCode().value());
        verifyNoInteractions(jdbc);
    }

    @Test
    void rejectsMoreThanNinetyDaysBeforeDatabaseAccess() {
        var e = assertThrows(ResponseStatusException.class, () ->
                controller.history(LocalDate.of(2025, 1, 1), LocalDate.of(2025, 4, 1)));
        assertEquals(400, e.getStatusCode().value());
        verifyNoInteractions(jdbc);
    }

    @Test
    void rejectsFutureUntilBeforeDatabaseAccess() {
        var e = assertThrows(ResponseStatusException.class, () ->
                controller.history(LocalDate.of(2099, 1, 1), LocalDate.of(2099, 1, 2)));
        assertEquals(400, e.getStatusCode().value());
        verifyNoInteractions(jdbc);
    }
}
