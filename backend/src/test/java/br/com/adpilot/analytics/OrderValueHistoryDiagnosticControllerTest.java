package br.com.adpilot.analytics;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import br.com.adpilot.integrations.nuvemshop.NuvemshopAuthService;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class OrderValueHistoryDiagnosticControllerTest {
    private final OrderValueHistoryDiagnosticService service =
            mock(OrderValueHistoryDiagnosticService.class);
    private final NuvemshopAuthService auth = mock(NuvemshopAuthService.class);

    @Test void disabledByDefaultAvoidsAllProviderCalls() {
        var controller=new OrderValueHistoryDiagnosticController(service,auth,false);
        var e=assertThrows(ResponseStatusException.class,
                ()->controller.inspect(100,20,30));
        assertEquals(404,e.getStatusCode().value());
        verifyNoInteractions(service,auth);
    }

    @Test void disconnectedShopIsBlocked() {
        when(auth.status()).thenReturn(new NuvemshopAuthService.Status(
                "NOT_CONNECTED",true,null,null,null));
        var controller=new OrderValueHistoryDiagnosticController(service,auth,true);
        var e=assertThrows(ResponseStatusException.class,
                ()->controller.inspect(100,20,30));
        assertEquals(409,e.getStatusCode().value());
        verifyNoInteractions(service);
    }

    @Test void connectedShopUsesNoStoreAndExplicitBounds() {
        when(auth.status()).thenReturn(new NuvemshopAuthService.Status(
                "CONNECTED",true,"private-store",null,null));
        var controller=new OrderValueHistoryDiagnosticController(service,auth,true);
        var response=controller.inspect(80,2,8);
        assertNotNull(response.getHeaders().getCacheControl());
        assertTrue(response.getHeaders().getCacheControl().contains("no-store"));
        verify(service).inspect(80,2,8);
    }
}
