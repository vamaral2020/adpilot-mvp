package br.com.adpilot.analytics;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import br.com.adpilot.integrations.nuvemshop.NuvemshopAuthService;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class OrderValueAuditControllerTest {
    private final OrderValueAuditService service = mock(OrderValueAuditService.class);
    private final NuvemshopAuthService auth = mock(NuvemshopAuthService.class);

    @Test void disabledFlagBlocksBeforeReadingCredentialsOrCallingProviders() {
        var controller=new OrderValueAuditController(service,auth,false);
        var error=assertThrows(ResponseStatusException.class,
                () -> controller.audit(100,20));
        assertEquals(404,error.getStatusCode().value());
        verifyNoInteractions(service,auth);
    }

    @Test void disconnectedShopPreventsAudit() {
        when(auth.status()).thenReturn(new NuvemshopAuthService.Status(
                "NOT_CONNECTED",true,null,null,null));
        var controller=new OrderValueAuditController(service,auth,true);
        var error=assertThrows(ResponseStatusException.class,
                () -> controller.audit(100,20));
        assertEquals(409,error.getStatusCode().value());
        verifyNoInteractions(service);
    }

    @Test void connectedShopUsesNoStoreAndBindsPageLimits() {
        when(auth.status()).thenReturn(new NuvemshopAuthService.Status(
                "CONNECTED",true,"private-store-id",null,null));
        var controller=new OrderValueAuditController(service,auth,true);
        var response=controller.audit(100,5);
        assertNotNull(response.getHeaders().getCacheControl());
        assertTrue(response.getHeaders().getCacheControl().contains("no-store"));
        verify(service).audit(100,5);
    }
}
