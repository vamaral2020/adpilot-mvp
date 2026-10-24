package br.com.adpilot.analytics;

import br.com.adpilot.integrations.nuvemshop.NuvemshopAuthService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Basic Auth is enforced globally. Operator-only and disabled by default;
 * replies contain aggregates only, never order identities or customer fields.
 */
@RestController
@RequestMapping("/api/v1/analytics/orders")
public class OrderValueAuditController {
    private final OrderValueAuditService service;
    private final NuvemshopAuthService auth;
    private final boolean enabled;

    public OrderValueAuditController(OrderValueAuditService service,
            NuvemshopAuthService auth,
            @Value("${adpilot.analytics.order-list-diagnostics-enabled:false}") boolean enabled) {
        this.service = service;
        this.auth = auth;
        this.enabled = enabled;
    }

    @PostMapping("/value-audit")
    public ResponseEntity<OrderValueAuditService.Report> audit(
            @RequestParam(defaultValue = "100") int pageSize,
            @RequestParam(defaultValue = "20") int maxPages) {
        if (!enabled) throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                "Auditoria temporaria desabilitada.");
        if (!"CONNECTED".equals(auth.status().status()))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A loja precisa estar conectada.");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.audit(pageSize, maxPages));
    }
}
