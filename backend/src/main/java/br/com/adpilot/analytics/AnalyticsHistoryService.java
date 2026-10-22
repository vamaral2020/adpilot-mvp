package br.com.adpilot.analytics;

import br.com.adpilot.analytics.AnalyticsHistoryModels.History;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Validates business rules and assembles the historical read model. */
@Service
public class AnalyticsHistoryService {
    private final AnalyticsHistoryRepository repository;
    private final AnalyticsCoverageAssessment assessment;
    private final Clock clock;

    @Autowired
    public AnalyticsHistoryService(AnalyticsHistoryRepository repository,
            AnalyticsCoverageAssessment assessment) {
        this(repository,assessment,Clock.systemUTC());
    }

    AnalyticsHistoryService(AnalyticsHistoryRepository repository, Clock clock) {
        this(repository,new AnalyticsCoverageAssessment(),clock);
    }

    AnalyticsHistoryService(AnalyticsHistoryRepository repository,
            AnalyticsCoverageAssessment assessment, Clock clock) {
        this.repository = repository;
        this.assessment = assessment;
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

        var meta = repository.metaDaily(since,until);
        var store = repository.paidDaily(since,until);
        var coverage = assessment.assess(since,until,meta,
                repository.metaWindows(since,until),repository.shopWindowSummary());

        // Even an observed Meta fetch window does not verify attribution finality,
        // account-timezone boundary accuracy, or payment-date coverage in the shop.
        var warnings = new ArrayList<String>();
        warnings.add("HISTORICAL_COVERAGE_NOT_VERIFIED");
        warnings.add("META_ACCOUNT_TIMEZONE_VS_STORE_UTC");
        warnings.add("ORDER_SNAPSHOTS_NOT_EVENT_LEDGER");
        warnings.add("NUVEMSHOP_UPDATED_AT_NOT_PAYMENT_DATE_COVERAGE");
        switch (coverage.meta().status()) {
            case "NO_COLLECTION_RECEIPT" -> warnings.add("META_NO_COLLECTION_RECEIPT");
            case "PARTIAL_WINDOWS" -> warnings.add("META_COLLECTION_WINDOWS_PARTIAL");
            case "MULTIPLE_ACCOUNTS" -> warnings.add("META_MULTIPLE_ACCOUNTS_UNRESOLVED");
            default -> { /* Windows observed, NOT proof of finality. */ }
        }
        if (!coverage.meta().observedWithoutRows().isEmpty())
            warnings.add("META_FETCHED_DAYS_WITHOUT_REPORTED_ROWS");
        if (until.equals(LocalDate.now(clock.withZone(ZoneOffset.UTC))))
            warnings.add("CURRENT_UTC_DAY_NOT_COMPLETE");

        return new History(since,until,meta,store,
                repository.campaignPerformance(since,until),
                repository.syncStates(),false,java.util.List.copyOf(warnings),coverage);
    }
}
