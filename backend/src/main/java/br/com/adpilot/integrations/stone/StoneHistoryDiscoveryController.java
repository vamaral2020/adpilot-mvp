package br.com.adpilot.integrations.stone;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** HTTP Basic globally enforced. Opt-in, aggregate-only provider discovery. */
@RestController
@RequestMapping("/api/v1/integrations/stone")
public class StoneHistoryDiscoveryController {
    private final StoneHistoryDiscoveryService service;
    private final boolean enabled;

    public StoneHistoryDiscoveryController(StoneHistoryDiscoveryService service,
            @Value("${adpilot.stone.diagnostics-enabled:false}") boolean enabled) {
        this.service=service;
        this.enabled=enabled;
    }

    @PostMapping("/history-discovery")
    public ResponseEntity<StoneHistoryDiscoveryService.Report> discover(
            @RequestParam(defaultValue="20") int pageSize,
            @RequestParam(defaultValue="5") int maxPages) {
        if(!enabled)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Diagnostico Stone desabilitado.");
        try {
            return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                    .body(service.inspect(pageSize,maxPages));
        } catch(StoneChargeException e) {
            throw new ResponseStatusException(e.status(),e.code());
        }
    }
}
