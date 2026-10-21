package br.com.adpilot.analytics;

import br.com.adpilot.analytics.AnalyticsHistoryModels.History;
import java.time.LocalDate;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** HTTP entry point only: delegates business rules and persistence access. */
@RestController
@RequestMapping("/api/v1/analytics/history")
public class AnalyticsHistoryController {
    private final AnalyticsHistoryService service;

    public AnalyticsHistoryController(AnalyticsHistoryService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<History> history(@RequestParam LocalDate since,
            @RequestParam LocalDate until) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.history(since, until));
    }
}
