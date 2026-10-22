package br.com.adpilot.analytics;

import br.com.adpilot.analytics.RecommendationModels.Report;
import br.com.adpilot.analytics.RecommendationModels.Request;
import br.com.adpilot.analytics.RecommendationModels.Stored;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * No Meta mutations, no scheduled evaluations. GET previews without writing;
 * explicit POST stores a local, explainable DRAFT evaluation and evidence.
 */
@Service
public class RecommendationService {
    private static final BigDecimal MAX_ROAS = new BigDecimal("100");
    private static final BigDecimal MAX_CPA = new BigDecimal("1000000");
    private final RecommendationRepository repository;
    private final RecommendationRuleEngine engine;
    private final Clock clock;

    @Autowired
    public RecommendationService(RecommendationRepository repository,
            RecommendationRuleEngine engine) {
        this(repository,engine,Clock.systemUTC());
    }

    RecommendationService(RecommendationRepository repository,
            RecommendationRuleEngine engine,Clock clock) {
        this.repository=repository;
        this.engine=engine;
        this.clock=clock;
    }

    @Transactional(readOnly=true)
    public Report preview(Request request) {
        validate(request);
        var report=engine.evaluate(request,repository.dailyCampaigns(request.since(),request.until()),
                repository.metaSyncState(),Instant.now(clock));
        if (request.until().equals(LocalDate.now(clock.withZone(ZoneOffset.UTC)))) {
            var warnings=new ArrayList<>(report.warnings());
            warnings.add("CURRENT_DAY_NOT_COMPLETE");
            return new Report(report.since(),report.until(),report.targetRoas(),
                    report.targetCpa(),report.coverageVerified(),report.generatedAt(),
                    report.metaSyncStatus(),report.lastMetaSyncSuccess(),
                    java.util.List.copyOf(warnings),report.recommendations());
        }
        return report;
    }

    /** Explicit local write only, never a Meta Ads action. */
    public Stored saveEvaluation(Request request) {
        var report=preview(request);
        return new Stored(repository.store(report),report);
    }

    private void validate(Request request) {
        if (request == null || request.since() == null || request.until() == null
                || request.since().isAfter(request.until())
                || ChronoUnit.DAYS.between(request.since(),request.until()) >= 90
                || request.until().isAfter(LocalDate.now(clock.withZone(ZoneOffset.UTC)))
                || !validTarget(request.targetRoas(),MAX_ROAS)
                || !validTarget(request.targetCpa(),MAX_CPA)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Periodo ou metas invalidos. Informe ate 90 dias sem datas futuras; "
                    + "ROAS e CPA devem ser positivos e limitados.");
        }
    }

    private static boolean validTarget(BigDecimal target,BigDecimal maximum) {
        return target==null || (target.signum()>0 && target.compareTo(maximum)<=0 &&
                target.scale()<=4);
    }
}
