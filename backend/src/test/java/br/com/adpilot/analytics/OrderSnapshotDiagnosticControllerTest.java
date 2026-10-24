package br.com.adpilot.analytics;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import br.com.adpilot.integrations.nuvemshop.NuvemshopAuthService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class OrderSnapshotDiagnosticControllerTest {
    private final OrderSnapshotDiagnosticService service =
            mock(OrderSnapshotDiagnosticService.class);
    private final NuvemshopAuthService auth = mock(NuvemshopAuthService.class);

    @Test void disabledByDefaultDoesNotContactEitherDataSource() {
        var controller=new OrderSnapshotDiagnosticController(service,auth,false);
        var error=assertThrows(ResponseStatusException.class,
                ()->controller.inspect(100,20));
        assertEquals(404,error.getStatusCode().value());
        verifyNoInteractions(service,auth);
    }

    @Test void unconnectedShopPreventsLiveScan() {
        when(auth.status()).thenReturn(new NuvemshopAuthService.Status(
                "NOT_CONNECTED",true,null,null,null));
        var controller=new OrderSnapshotDiagnosticController(service,auth,true);
        var error=assertThrows(ResponseStatusException.class,
                ()->controller.inspect(100,20));
        assertEquals(409,error.getStatusCode().value());
        verifyNoInteractions(service);
    }

    @Test void connectedShopReturnsNoStoreAndAggregatesOnly() {
        when(auth.status()).thenReturn(new NuvemshopAuthService.Status(
                "CONNECTED",true,"private-store-id",null,null));
        var report = new OrderSnapshotDiagnosticService.Report(false,false,false,
                1,1,1,null,0,1,0,1,0,1,0,0,0,0,
                List.of("UPSTREAM_PAGINATION_NOT_RECONCILED"));
        when(service.inspect(100,5)).thenReturn(report);
        var controller=new OrderSnapshotDiagnosticController(service,auth,true);
        var response=controller.inspect(100,5);
        assertSame(report,response.getBody());
        assertNotNull(response.getHeaders().getCacheControl());
        assertTrue(response.getHeaders().getCacheControl().contains("no-store"));
        assertFalse(response.getBody().toString().contains("private-store-id"));
        verify(service).inspect(100,5);
    }
}
