package br.com.adpilot.integrations.stone;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import br.com.adpilot.integrations.nuvemshop.NuvemshopAuthService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class StoneNuvemCorrelationControllerTest {
    private StoneNuvemCorrelationService service=mock(StoneNuvemCorrelationService.class);
    private NuvemshopAuthService auth=mock(NuvemshopAuthService.class);

    @Test void requiresBothDiagnosticFlagsWithoutSideEffects() {
        for(var pair:new boolean[][]{{false,false},{true,false},{false,true}}) {
            var controller=new StoneNuvemCorrelationController(service,auth,pair[0],pair[1]);
            var e=assertThrows(ResponseStatusException.class,
                    ()->controller.inspect(50,10));
            assertEquals(404,e.getStatusCode().value());
        }
        verifyNoInteractions(service,auth);
    }

    @Test void requiresConnectedNuvemshop() {
        when(auth.status()).thenReturn(new NuvemshopAuthService.Status(
                "NOT_CONNECTED",true,null,null,null));
        var controller=new StoneNuvemCorrelationController(service,auth,true,true);
        var e=assertThrows(ResponseStatusException.class,
                ()->controller.inspect(50,10));
        assertEquals(409,e.getStatusCode().value());
        verifyNoInteractions(service);
    }

    @Test void returnsNoStoreForEnabledOperatorRequest() {
        when(auth.status()).thenReturn(new NuvemshopAuthService.Status(
                "CONNECTED",true,"private-store",null,null));
        var controller=new StoneNuvemCorrelationController(service,auth,true,true);
        var response=controller.inspect(50,10);
        assertEquals(200,response.getStatusCode().value());
        assertTrue(response.getHeaders().getCacheControl().contains("no-store"));
        verify(service).inspect(50,10);
    }

    @Test void sanitizesUpstreamErrorCode() {
        when(auth.status()).thenReturn(new NuvemshopAuthService.Status(
                "CONNECTED",true,"private-store",null,null));
        when(service.inspect(50,10)).thenThrow(new StoneChargeException(
                HttpStatus.BAD_GATEWAY,"STONE_CREDENTIALS_REJECTED",
                "do not expose secret key",401));
        var controller=new StoneNuvemCorrelationController(service,auth,true,true);
        var e=assertThrows(ResponseStatusException.class,()->controller.inspect(50,10));
        assertEquals(502,e.getStatusCode().value());
        assertEquals("STONE_CREDENTIALS_REJECTED",e.getReason());
        assertFalse(e.getMessage().contains("secret key"));
    }
}
