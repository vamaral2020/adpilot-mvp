package br.com.adpilot.integrations.nuvemshop;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Temporary, admin-authenticated, opt-in endpoint for local diagnostics. */
@RestController
@RequestMapping("/api/v1/nuvemshop/diagnostics")
public class NuvemshopPaymentDiagnosticController {
    private final NuvemshopPaymentDiagnosticService service;

    public NuvemshopPaymentDiagnosticController(NuvemshopPaymentDiagnosticService service) {
        this.service = service;
    }

    @GetMapping("/orders/{number}")
    public ResponseEntity<NuvemshopPaymentDiagnosticService.Diagnostic> diagnose(
            @PathVariable int number) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.diagnose(number));
    }
}
