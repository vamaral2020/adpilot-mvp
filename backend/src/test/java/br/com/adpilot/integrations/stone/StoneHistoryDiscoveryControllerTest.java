package br.com.adpilot.integrations.stone;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class StoneHistoryDiscoveryControllerTest {
    private final StoneHistoryDiscoveryService service=mock(StoneHistoryDiscoveryService.class);

    @Test void disabledEndpointFailsBeforeCallingProvider() {
        var controller=new StoneHistoryDiscoveryController(service,false);
        var e=assertThrows(ResponseStatusException.class,
                ()->controller.discover(20,5));
        assertEquals(404,e.getStatusCode().value());
        verifyNoInteractions(service);
    }

    @Test void successResponseUsesNoStoreAndNeverSetsFinancialFlags() {
        var controller=new StoneHistoryDiscoveryController(service,true);
        var population=new StoneHistoryDiscoveryService.Population(
                1,0,0,0,0L,true,true,java.util.Map.of());
        var report=new StoneHistoryDiscoveryService.Report("PAGARME_CORE_V5","SANDBOX",
                population,population,0,0,0,0,0,0,java.util.Map.of(),
                false,false,false,false,java.util.List.of());
        when(service.inspect(20,5)).thenReturn(report);
        var response=controller.discover(20,5);
        assertEquals(200,response.getStatusCode().value());
        assertTrue(response.getHeaders().getCacheControl().contains("no-store"));
        assertFalse(response.getBody().financiallyVerified());
        assertFalse(response.getBody().paymentSettlementVerified());
        verify(service).inspect(20,5);
    }

    @Test void providerExceptionExposesOnlySafeCode() {
        when(service.inspect(20,5)).thenThrow(new StoneChargeException(
                HttpStatus.BAD_GATEWAY,"STONE_CREDENTIALS_REJECTED",
                "hidden provider secret",401));
        var controller=new StoneHistoryDiscoveryController(service,true);
        var e=assertThrows(ResponseStatusException.class,
                ()->controller.discover(20,5));
        assertEquals(502,e.getStatusCode().value());
        assertEquals("STONE_CREDENTIALS_REJECTED",e.getReason());
        assertFalse(e.getMessage().contains("hidden provider secret"));
    }
}
