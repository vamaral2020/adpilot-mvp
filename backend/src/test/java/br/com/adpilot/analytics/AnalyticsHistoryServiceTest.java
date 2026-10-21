package br.com.adpilot.analytics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class AnalyticsHistoryServiceTest {
    private final AnalyticsHistoryRepository repository = mock(AnalyticsHistoryRepository.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-20T12:00:00Z"), ZoneOffset.UTC);
    private final AnalyticsHistoryService service = new AnalyticsHistoryService(repository, clock);

    private void rejects(LocalDate since, LocalDate until) {
        var error = assertThrows(ResponseStatusException.class,
                () -> service.history(since, until));
        assertEquals(400, error.getStatusCode().value());
        verifyNoInteractions(repository);
    }

    @Test
    void refusesInvertedPeriod() {
        rejects(LocalDate.of(2026, 10, 20), LocalDate.of(2026, 10, 19));
    }

    @Test
    void refusesOverNinetyDays() {
        rejects(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 4, 1));
    }

    @Test
    void refusesFutureDate() {
        rejects(LocalDate.of(2026, 10, 20), LocalDate.of(2026, 10, 21));
    }

    @Test
    void refusesNullBounds() {
        rejects(null, LocalDate.of(2026, 10, 1));
        rejects(LocalDate.of(2026, 10, 1), null);
    }

    @Test
    void delegatesValidPeriodAndNeverClaimsCoverageWithoutEvidence() {
        var since = LocalDate.of(2026, 10, 1);
        var until = LocalDate.of(2026, 10, 20);
        when(repository.metaDaily(since, until)).thenReturn(List.of());
        when(repository.paidDaily(since, until)).thenReturn(List.of());
        when(repository.campaignPerformance(since, until)).thenReturn(List.of());
        when(repository.syncStates()).thenReturn(List.of());

        var report = service.history(since, until);

        assertFalse(report.complete());
        assertEquals(since, report.since());
        assertEquals(until, report.until());
        assertEquals("HISTORICAL_COVERAGE_NOT_VERIFIED", report.warnings().getFirst());
        verify(repository).metaDaily(since, until);
        verify(repository).paidDaily(since, until);
        verify(repository).campaignPerformance(since, until);
        verify(repository).syncStates();
    }
}
