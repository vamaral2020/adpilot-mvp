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

/** Authenticated, opt-in list-vs-detail fields check; never exposes order IDs. */
@RestController
@RequestMapping("/api/v1/analytics/orders")
public class OrderPaymentFieldProbeController {
    private final OrderPaymentFieldProbeService service;
    private final NuvemshopAuthService auth;
    private final boolean enabled;

    public OrderPaymentFieldProbeController(OrderPaymentFieldProbeService service,
            NuvemshopAuthService auth,
            @Value("${adpilot.analytics.order-list-diagnostics-enabled:false}") boolean enabled) {
        this.service=service;
        this.auth=auth;
        this.enabled=enabled;
    }

    @PostMapping("/payment-field-probe")
    public ResponseEntity<OrderPaymentFieldProbeService.Report> inspect(
            @RequestParam(defaultValue="100") int pageSize,
            @RequestParam(defaultValue="20") int maxPages,
            @RequestParam(defaultValue="5") int maxDetailOrders) {
        if (!enabled) throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                "Diagnostico de campos desabilitado.");
        if (!"CONNECTED".equals(auth.status().status()))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A loja precisa estar conectada.");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.inspect(pageSize,maxPages,maxDetailOrders));
    }
}
