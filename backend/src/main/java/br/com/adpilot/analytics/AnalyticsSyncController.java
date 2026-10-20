package br.com.adpilot.analytics;

import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Local, authenticated operator endpoint. POST writes only AdPilot PostgreSQL. */
@RestController
@RequestMapping("/api/v1/analytics")
public class AnalyticsSyncController {
    private final AnalyticsSyncService sync;
    private final AnalyticsSyncStore store;

    public AnalyticsSyncController(AnalyticsSyncService sync, AnalyticsSyncStore store) {
        this.sync=sync;
        this.store=store;
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Boolean>> status() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(Map.of("syncEnabled",sync.enabled()));
    }

    @GetMapping("/runs")
    public ResponseEntity<java.util.List<AnalyticsSyncStore.RunInfo>> runs() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(store.recentRuns());
    }

    @PostMapping("/sync")
    public ResponseEntity<AnalyticsSyncService.Report> run() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(sync.run());
    }
}
