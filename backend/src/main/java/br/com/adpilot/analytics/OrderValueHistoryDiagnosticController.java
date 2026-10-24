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
 * Merchant Order VALUE history only, not Payment App Transactions.
 * Authenticated, disabled by default, aggregated output and no-store.
 */
@RestController
@RequestMapping("/api/v1/analytics/orders")
public class OrderValueHistoryDiagnosticController {
    private final OrderValueHistoryDiagnosticService service;
    private final NuvemshopAuthService auth;
    private final boolean enabled;

    public OrderValueHistoryDiagnosticController(OrderValueHistoryDiagnosticService service,
            NuvemshopAuthService auth,
            @Value("${adpilot.analytics.order-list-diagnostics-enabled:false}") boolean enabled) {
        this.service = service;
        this.auth = auth;
        this.enabled = enabled;
    }

    @PostMapping("/value-history-diagnostics")
    public ResponseEntity<OrderValueHistoryDiagnosticService.Report> inspect(
            @RequestParam(defaultValue="100") int pageSize,
            @RequestParam(defaultValue="20") int maxPages,
            @RequestParam(defaultValue="30") int maxHistoryOrders) {
        if (!enabled) throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                "Diagnostico de historico desabilitado.");
        if (!"CONNECTED".equals(auth.status().status()))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A loja precisa estar conectada.");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.inspect(pageSize,maxPages,maxHistoryOrders));
    }
}
