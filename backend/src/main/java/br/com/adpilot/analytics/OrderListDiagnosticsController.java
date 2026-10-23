package br.com.adpilot.analytics;

import br.com.adpilot.integrations.nuvemshop.NuvemshopAuthService;
import br.com.adpilot.integrations.nuvemshop.NuvemshopClient;
import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Disabled-by-default, authenticated operator diagnostic. No customer content is
 * parsed or returned. Only HTTP statuses for the existing incremental window.
 * No writes to AdPilot PostgreSQL, Nuvemshop or Meta Ads.
 */
@RestController
@RequestMapping("/api/v1/analytics/orders")
public class OrderListDiagnosticsController {
    private final AnalyticsSyncStore store;
    private final NuvemshopClient client;
    private final NuvemshopAuthService shopAuth;
    private final boolean enabled;

    public record DiagnosticReport(boolean cursorPresent,
            List<NuvemshopClient.OrderListProbe> checks) {}

    public OrderListDiagnosticsController(AnalyticsSyncStore store,NuvemshopClient client,
            NuvemshopAuthService shopAuth,
            @Value("${adpilot.analytics.order-list-diagnostics-enabled:false}") boolean enabled) {
        this.store=store;
        this.client=client;
        this.shopAuth=shopAuth;
        this.enabled=enabled;
    }

    @PostMapping("/listing-diagnostics")
    public ResponseEntity<DiagnosticReport> run() {
        if(!enabled)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Diagnostico temporario desabilitado.");
        if(!"CONNECTED".equals(shopAuth.status().status()))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A loja precisa estar conectada.");
        Instant now=Instant.now();
        Instant upper=now.minusSeconds(300);
        Instant cursor=store.cursor("NUVEMSHOP","ORDERS");
        Instant lower=cursor==null?upper.minusSeconds(30L*86400):cursor.minusSeconds(600);
        if(!lower.isBefore(upper))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Janela de coleta invalida.");
        var result=client.probeOrderList(lower,upper);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new DiagnosticReport(cursor!=null,result));
    }

    public record FollowupReport(boolean cursorPresent, boolean nextPageAdvertised,
            boolean totalExceedsPage, List<NuvemshopClient.OrderListProbe> checks) {}

    @PostMapping("/listing-diagnostics/followup")
    public ResponseEntity<FollowupReport> followup() {
        if (!enabled)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Diagnostico temporario desabilitado.");
        if (!"CONNECTED".equals(shopAuth.status().status()))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A loja precisa estar conectada.");
        Instant upper=Instant.now().minusSeconds(300);
        Instant cursor=store.cursor("NUVEMSHOP","ORDERS");
        Instant lower=cursor==null?upper.minusSeconds(30L*86400):cursor.minusSeconds(600);
        if (!lower.isBefore(upper))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Janela de coleta invalida.");
        var report=client.probeOrderListFollowup(lower,upper);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new FollowupReport(cursor!=null,report.nextPageAdvertised(),
                        report.totalExceedsPage(),report.checks()));
    }
    /**
     * Isolate timezone representation from a date-value-dependent upstream 404.
     * No cursor changes and no order data is returned.
     */
    @PostMapping("/listing-diagnostics/lower-bound")
    public ResponseEntity<DiagnosticReport> lowerBound() {
        if(!enabled)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Diagnostico temporario desabilitado.");
        if(!"CONNECTED".equals(shopAuth.status().status()))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A loja precisa estar conectada.");
        Instant upper=Instant.now().minusSeconds(300);
        Instant cursor=store.cursor("NUVEMSHOP","ORDERS");
        Instant lower=cursor==null?upper.minusSeconds(30L*86400):cursor.minusSeconds(600);
        if(!lower.isBefore(upper))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Janela de coleta invalida.");
        var checks=client.probeOrderLowerBoundFormats(lower,upper);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new DiagnosticReport(cursor!=null,checks));
    }

}
