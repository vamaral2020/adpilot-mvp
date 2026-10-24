package br.com.adpilot.integrations.stone;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Single-charge, operator-only diagnostic. No order reconciliation,
 * mutation, webhook, persisted ID or gateway raw response.
 *
 * Global HTTP Basic is enforced by SecurityConfiguration.
 */
@RestController
@RequestMapping("/api/v1/integrations/stone")
public class StoneChargeProbeController {
    private final StoneChargeClient client;
    private final boolean enabled;

    public StoneChargeProbeController(StoneChargeClient client,
            @Value("${adpilot.stone.diagnostics-enabled:false}") boolean enabled) {
        this.client=client;
        this.enabled=enabled;
    }

    public record ChargeProbeRequest(String chargeId) {}

    public record ChargeProbeResponse(String source,
            String status,String paymentMethod,String currency,
            boolean amountPresent,boolean paidAmountPresent,
            boolean amountFormatValid,boolean paidAmountFormatValid,
            boolean financiallyVerified,boolean paymentSettlementVerified,
            boolean matchedToNuvemshopOrder,String warning) {}

    @PostMapping("/charge-probe")
    public ResponseEntity<ChargeProbeResponse> probe(@RequestBody ChargeProbeRequest request) {
        if (!enabled) throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                "Diagnostico Stone desabilitado.");
        if (request==null || request.chargeId()==null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "INVALID_CHARGE_ID");
        try {
            var signal=client.lookup(request.chargeId());
            var response=new ChargeProbeResponse("PAGARME_CORE_V5",
                    signal.status(),signal.paymentMethod(),signal.currency(),
                    signal.amountPresent(),signal.paidAmountPresent(),
                    signal.amountFormatValid(),signal.paidAmountFormatValid(),
                    false,false,false,"CHARGE_NOT_LINKED_TO_NUVEMSHOP_ORDER");
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(response);
        } catch (StoneChargeException e) {
            throw new ResponseStatusException(e.status(),e.code());
        }
    }
}
