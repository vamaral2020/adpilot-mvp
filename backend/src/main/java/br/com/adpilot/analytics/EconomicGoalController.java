package br.com.adpilot.analytics;

import br.com.adpilot.analytics.EconomicGoalModels.Catalog;
import br.com.adpilot.analytics.EconomicGoalModels.Goal;
import br.com.adpilot.analytics.EconomicGoalModels.Input;
import br.com.adpilot.analytics.EconomicGoalModels.LinkRequest;
import br.com.adpilot.analytics.EconomicGoalModels.Result;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Existing Basic Auth applies; writes only to local AdPilot data. */
@RestController
@RequestMapping("/api/v1/planning")
public class EconomicGoalController {
    private final EconomicGoalService service;

    public EconomicGoalController(EconomicGoalService service) {
        this.service=service;
    }

    @GetMapping("/catalog")
    public ResponseEntity<Catalog> catalog() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.catalog());
    }

    @PostMapping("/goals")
    public ResponseEntity<Goal> save(@RequestBody Input request) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(service.save(request));
    }

    @PutMapping("/campaigns/{accountId}/{campaignId}/goal")
    public ResponseEntity<Result> associate(@PathVariable String accountId,
            @PathVariable String campaignId,@RequestBody LinkRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.associate(accountId,campaignId,request));
    }

    @DeleteMapping("/campaigns/{accountId}/{campaignId}/goal")
    public ResponseEntity<Result> unlink(@PathVariable String accountId,
            @PathVariable String campaignId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.unlink(accountId,campaignId));
    }
}
