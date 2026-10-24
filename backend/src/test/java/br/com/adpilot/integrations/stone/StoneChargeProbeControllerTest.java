package br.com.adpilot.integrations.stone;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class StoneChargeProbeControllerTest {
    private final StoneChargeClient client=mock(StoneChargeClient.class);

    @Test void flagOffBlocksAllProviderCalls() {
        var controller=new StoneChargeProbeController(client,false);
        var error=assertThrows(ResponseStatusException.class,
                ()->controller.probe(new StoneChargeProbeController.ChargeProbeRequest("ch_abcdef12")));
        assertEquals(404,error.getStatusCode().value());
        verifyNoInteractions(client);
    }

    @Test void missingChargeIdentifierFailsWithoutProviderCall() {
        var controller=new StoneChargeProbeController(client,true);
        var error=assertThrows(ResponseStatusException.class,
                ()->controller.probe(new StoneChargeProbeController.ChargeProbeRequest(null)));
        assertEquals(400,error.getStatusCode().value());
        verifyNoInteractions(client);
    }

    @Test void returnsOnlySanitizedStatusAndNeverSetsFinancialVerificationTrue() {
        var controller=new StoneChargeProbeController(client,true);
        when(client.lookup("ch_abcdef12")).thenReturn(
                new StoneChargeClient.ChargeSignals("PAID","CREDIT_CARD","BRL",
                        true,true,true,true));
        var response=controller.probe(
                new StoneChargeProbeController.ChargeProbeRequest("ch_abcdef12"));
        assertEquals(200,response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertTrue(response.getHeaders().getCacheControl().contains("no-store"));
        var data=response.getBody();
        assertEquals("PAGARME_CORE_V5",data.source());
        assertEquals("PAID",data.status());
        assertEquals("CREDIT_CARD",data.paymentMethod());
        assertFalse(data.financiallyVerified());
        assertFalse(data.paymentSettlementVerified());
        assertFalse(data.matchedToNuvemshopOrder());
        assertEquals("CHARGE_NOT_LINKED_TO_NUVEMSHOP_ORDER",data.warning());
        assertFalse(data.toString().contains("ch_abcdef12"));
    }

    @Test void returnsSanitizedCodeWithoutChargeIdOnFailure() {
        when(client.lookup("ch_abcdef12")).thenThrow(
                new StoneChargeException(HttpStatus.BAD_GATEWAY,"STONE_CHARGE_NOT_FOUND",
                        "provider sensitive ch_abcdef12",404));
        var controller=new StoneChargeProbeController(client,true);
        var error=assertThrows(ResponseStatusException.class,
                ()->controller.probe(new StoneChargeProbeController.ChargeProbeRequest("ch_abcdef12")));
        assertEquals(502,error.getStatusCode().value());
        assertEquals("STONE_CHARGE_NOT_FOUND",error.getReason());
        assertFalse(error.getMessage().contains("ch_abcdef12"));
    }
}
