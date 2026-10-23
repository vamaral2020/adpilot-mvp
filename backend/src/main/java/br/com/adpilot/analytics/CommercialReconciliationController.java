package br.com.adpilot.analytics;

import java.time.LocalDate;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Authenticated, read-only projection of local snapshots. Never contacts providers. */
@RestController
@RequestMapping("/api/v1/analytics/reconciliation")
public class CommercialReconciliationController {
    private final CommercialReconciliationService service;
    public CommercialReconciliationController(CommercialReconciliationService service) {
        this.service=service;
    }

    @GetMapping
    public ResponseEntity<CommercialReconciliationService.Report> report(
            @RequestParam LocalDate since,@RequestParam LocalDate until) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.compare(since,until));
    }
}
