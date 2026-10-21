package br.com.adpilot.analytics;

import br.com.adpilot.analytics.AnalyticsHistoryModels.History;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Validates business rules and assembles the historical read model. */
@Service
public class AnalyticsHistoryService {
    private final AnalyticsHistoryRepository repository;
    private final Clock clock;

    public AnalyticsHistoryService(AnalyticsHistoryRepository repository) {
        this(repository, Clock.systemUTC());
    }

    // Package-private constructor allows deterministic date-boundary tests.
    AnalyticsHistoryService(AnalyticsHistoryRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public History history(LocalDate since, LocalDate until) {
        if (since == null || until == null || since.isAfter(until)
                || ChronoUnit.DAYS.between(since, until) >= 90
                || until.isAfter(LocalDate.now(clock.withZone(ZoneOffset.UTC)))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Informe um periodo valido de ate 90 dias, sem datas futuras.");
        }

        // A successful sync does not demonstrate complete coverage of the requested days.
        return new History(since, until, repository.metaDaily(since, until),
                repository.paidDaily(since, until), repository.syncStates(), false,
                List.of("HISTORICAL_COVERAGE_NOT_VERIFIED",
                        "META_ACCOUNT_TIMEZONE_VS_STORE_UTC",
                        "ORDER_SNAPSHOTS_NOT_EVENT_LEDGER"));
    }
}
