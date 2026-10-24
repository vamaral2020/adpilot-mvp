package br.com.adpilot.integrations.stone;

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

/** Disabled unless BOTH diagnostic flags are explicitly enabled locally. */
@RestController
@RequestMapping("/api/v1/integrations/stone")
public class StoneNuvemCorrelationController {
    private final StoneNuvemCorrelationService service;
    private final NuvemshopAuthService auth;
    private final boolean enabled;

    public StoneNuvemCorrelationController(StoneNuvemCorrelationService service,
            NuvemshopAuthService auth,
            @Value("${adpilot.stone.diagnostics-enabled:false}") boolean stoneDiagnosticsEnabled,
            @Value("${adpilot.analytics.order-list-diagnostics-enabled:false}") boolean shopDiagnosticsEnabled) {
        this.service=service;
        this.auth=auth;
        this.enabled=stoneDiagnosticsEnabled && shopDiagnosticsEnabled;
    }

    @PostMapping("/nuvemshop-correlation")
    public ResponseEntity<StoneNuvemCorrelationService.Report> inspect(
            @RequestParam(defaultValue="50") int pageSize,
            @RequestParam(defaultValue="10") int maxPages) {
        if(!enabled) throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                "Diagnostico de correlacao desabilitado.");
        if(!"CONNECTED".equals(auth.status().status()))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A loja Nuvemshop precisa estar conectada.");
        try {
            return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                    .body(service.inspect(pageSize,maxPages));
        } catch(StoneChargeException e) {
            throw new ResponseStatusException(e.status(),e.code());
        }
    }
}
