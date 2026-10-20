package br.com.adpilot.commerce;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** HTTP Basic applies globally. Aggregates only, no order-level data or writes. */
@RestController
@RequestMapping("/api/v1/nuvemshop/payments")
public class PaymentAuditController {
    private final PaymentAuditService service;

    public PaymentAuditController(PaymentAuditService service) {
        this.service = service;
    }

    @GetMapping("/audit")
    public ResponseEntity<PaymentAuditResponse> audit(
            @RequestParam(defaultValue = "100") int pageSize,
            @RequestParam(defaultValue = "20") int maxPages) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.audit(pageSize, maxPages));
    }
}
