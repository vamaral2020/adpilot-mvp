package br.com.adpilot.analytics;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import br.com.adpilot.integrations.nuvemshop.NuvemshopAuthService;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class OrderPaymentSignalsControllerTest {
    private final OrderPaymentSignalsService service=mock(OrderPaymentSignalsService.class);
    private final NuvemshopAuthService auth=mock(NuvemshopAuthService.class);

    @Test void disablesEndpointByDefault() {
        var controller=new OrderPaymentSignalsController(service,auth,false);
        var err=assertThrows(ResponseStatusException.class,()->controller.inspect(100,20));
        assertEquals(404,err.getStatusCode().value());
        verifyNoInteractions(auth,service);
    }

    @Test void requiresConnectedShop() {
        when(auth.status()).thenReturn(new NuvemshopAuthService.Status(
                "NOT_CONNECTED",true,null,null,null));
        var controller=new OrderPaymentSignalsController(service,auth,true);
        var err=assertThrows(ResponseStatusException.class,()->controller.inspect(100,20));
        assertEquals(409,err.getStatusCode().value());
        verifyNoInteractions(service);
    }

    @Test void sendsNoStoreAndKeepsExplicitPageLimits() {
        when(auth.status()).thenReturn(new NuvemshopAuthService.Status(
                "CONNECTED",true,"private-store",null,null));
        var controller=new OrderPaymentSignalsController(service,auth,true);
        var result=controller.inspect(40,3);
        assertTrue(result.getHeaders().getCacheControl().contains("no-store"));
        verify(service).inspect(40,3);
    }
}
