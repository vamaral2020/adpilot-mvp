package br.com.adpilot.analytics;

import br.com.adpilot.analytics.RecommendationModels.Report;
import br.com.adpilot.analytics.RecommendationModels.Request;
import br.com.adpilot.analytics.RecommendationModels.Stored;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** HTTP-only adapter; no SQL or business decisions in the controller. */
@RestController
@RequestMapping("/api/v1/analytics/recommendations")
public class RecommendationController {
    private final RecommendationService service;

    public RecommendationController(RecommendationService service) {
        this.service=service;
    }

    @GetMapping
    public ResponseEntity<Report> preview(@RequestParam LocalDate since,
            @RequestParam LocalDate until,
            @RequestParam(required=false) BigDecimal targetRoas,
            @RequestParam(required=false) BigDecimal targetCpa) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.preview(new Request(since,until,targetRoas,targetCpa)));
    }

    @PostMapping("/evaluations")
    public ResponseEntity<Stored> evaluate(@RequestBody Request request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.saveEvaluation(request));
    }
}
