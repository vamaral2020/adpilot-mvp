package br.com.adpilot.analytics;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import br.com.adpilot.integrations.nuvemshop.NuvemshopAuthService;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class OrderPaymentFieldProbeControllerTest {
    private final OrderPaymentFieldProbeService service=mock(OrderPaymentFieldProbeService.class);
    private final NuvemshopAuthService auth=mock(NuvemshopAuthService.class);

    @Test void disabledFlagBlocksEarly() {
        var controller=new OrderPaymentFieldProbeController(service,auth,false);
        assertEquals(404,assertThrows(ResponseStatusException.class,
                ()->controller.inspect(100,20,5)).getStatusCode().value());
        verifyNoInteractions(service,auth);
    }

    @Test void notConnectedBlocksEarly() {
        when(auth.status()).thenReturn(new NuvemshopAuthService.Status(
                "NOT_CONNECTED",true,null,null,null));
        var controller=new OrderPaymentFieldProbeController(service,auth,true);
        assertEquals(409,assertThrows(ResponseStatusException.class,
                ()->controller.inspect(100,20,5)).getStatusCode().value());
        verifyNoInteractions(service);
    }

    @Test void returnsNoStoreAndPreservesLimits() {
        when(auth.status()).thenReturn(new NuvemshopAuthService.Status(
                "CONNECTED",true,"private-store",null,null));
        var controller=new OrderPaymentFieldProbeController(service,auth,true);
        var response=controller.inspect(80,4,3);
        assertTrue(response.getHeaders().getCacheControl().contains("no-store"));
        verify(service).inspect(80,4,3);
    }
}
