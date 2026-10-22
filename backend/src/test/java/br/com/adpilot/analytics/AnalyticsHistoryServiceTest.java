package br.com.adpilot.analytics;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import br.com.adpilot.analytics.AnalyticsHistoryModels.MetaWindow;
import br.com.adpilot.analytics.AnalyticsHistoryModels.ShopWindowSummary;
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

    @Test void refusesInvertedPeriod() {
        rejects(LocalDate.of(2026,10,20), LocalDate.of(2026,10,19));
    }

    @Test void refusesOverNinetyDays() {
        rejects(LocalDate.of(2026,1,1), LocalDate.of(2026,4,1));
    }

    @Test void refusesFutureDate() {
        rejects(LocalDate.of(2026,10,20), LocalDate.of(2026,10,21));
    }

    @Test void refusesNullBounds() {
        rejects(null,LocalDate.of(2026,10,1));
        rejects(LocalDate.of(2026,10,1),null);
    }

    @Test void oldRunsWithoutV5WindowsNeverBecomeCompletenessProof() {
        var since=LocalDate.of(2026,10,1);
        var until=LocalDate.of(2026,10,19);
        when(repository.metaDaily(since,until)).thenReturn(List.of());
        when(repository.paidDaily(since,until)).thenReturn(List.of());
        when(repository.metaWindows(since,until)).thenReturn(List.of());
        when(repository.shopWindowSummary()).thenReturn(new ShopWindowSummary(0,null));
        when(repository.campaignPerformance(since,until)).thenReturn(List.of());
        when(repository.syncStates()).thenReturn(List.of());

        var report=service.history(since,until);
        assertFalse(report.complete());
        assertEquals(since,report.since());
        assertEquals(until,report.until());
        assertEquals("NO_COLLECTION_RECEIPT",report.coverage().meta().status());
        assertEquals(19,report.coverage().meta().unobservedDates().size());
        assertEquals(0,report.coverage().store().successfulUpdateWindows());
        assertTrue(report.warnings().contains("META_NO_COLLECTION_RECEIPT"));
        assertTrue(report.warnings().contains("HISTORICAL_COVERAGE_NOT_VERIFIED"));
        verify(repository).metaDaily(since,until);
        verify(repository).paidDaily(since,until);
        verify(repository).metaWindows(since,until);
        verify(repository).shopWindowSummary();
        verify(repository).campaignPerformance(since,until);
        verify(repository).syncStates();
    }

    @Test void fullMetaWindowStillDoesNotMakeCrossProviderHistoryComplete() {
        var since=LocalDate.of(2026,10,14);
        var until=LocalDate.of(2026,10,19);
        when(repository.metaWindows(since,until)).thenReturn(List.of(
                new MetaWindow("123",since,until,Instant.parse("2026-10-20T01:00:00Z"))));
        when(repository.shopWindowSummary()).thenReturn(
                new ShopWindowSummary(2,Instant.parse("2026-10-20T01:00:00Z")));
        var report=service.history(since,until);
        assertFalse(report.complete());
        assertEquals("WINDOWS_OBSERVED",report.coverage().meta().status());
        assertEquals(6,report.coverage().meta().observedDays());
        assertEquals(6,report.coverage().meta().datesWithoutRows());
        assertEquals("UPDATED_AT_ONLY_NOT_PAID_DATE",report.coverage().store().status());
        assertTrue(report.warnings().contains("META_FETCHED_DAYS_WITHOUT_REPORTED_ROWS"));
        assertTrue(report.warnings().contains("NUVEMSHOP_UPDATED_AT_NOT_PAYMENT_DATE_COVERAGE"));
    }

    @Test void currentUtcDayMustWarnEvenWhenMetaWindowExists() {
        var day=LocalDate.of(2026,10,20);
        when(repository.metaWindows(day,day)).thenReturn(List.of(
                new MetaWindow("123",day,day,Instant.parse("2026-10-20T11:00:00Z"))));
        var report=service.history(day,day);
        assertTrue(report.warnings().contains("CURRENT_UTC_DAY_NOT_COMPLETE"));
        assertFalse(report.complete());
    }
}
