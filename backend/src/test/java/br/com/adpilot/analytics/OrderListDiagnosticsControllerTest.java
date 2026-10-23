package br.com.adpilot.analytics;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import br.com.adpilot.integrations.nuvemshop.NuvemshopAuthService;
import br.com.adpilot.integrations.nuvemshop.NuvemshopClient;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class OrderListDiagnosticsControllerTest {
    private final AnalyticsSyncStore store=mock(AnalyticsSyncStore.class);
    private final NuvemshopClient client=mock(NuvemshopClient.class);
    private final NuvemshopAuthService auth=mock(NuvemshopAuthService.class);

    @Test void disabledByDefaultBeforeAnyDatabaseOrExternalRead() {
        var controller=new OrderListDiagnosticsController(store,client,auth,false);
        var error=assertThrows(ResponseStatusException.class,controller::run);
        assertEquals(404,error.getStatusCode().value());
        verifyNoInteractions(store,client,auth);
    }

    @Test void disconnectedShopDoesNotProbeOrReadCursor() {
        when(auth.status()).thenReturn(new NuvemshopAuthService.Status(
                "NOT_CONNECTED",true,null,null,null));
        var controller=new OrderListDiagnosticsController(store,client,auth,true);
        var error=assertThrows(ResponseStatusException.class,controller::run);
        assertEquals(409,error.getStatusCode().value());
        verifyNoInteractions(store,client);
    }

    @Test void existingCursorIsReusedWithTenMinuteOverlapAndNoMutation() {
        Instant cursor=Instant.parse("2026-10-22T21:10:16Z");
        when(auth.status()).thenReturn(new NuvemshopAuthService.Status(
                "CONNECTED",true,"123","read_orders",cursor));
        when(store.cursor("NUVEMSHOP","ORDERS")).thenReturn(cursor);
        var probes=List.of(new NuvemshopClient.OrderListProbe(
                "RANGE_EXACT","HTTP_STATUS",404));
        when(client.probeOrderList(eq(cursor.minusSeconds(600)),any())).thenReturn(probes);

        var result=new OrderListDiagnosticsController(store,client,auth,true).run();

        assertEquals("no-store",result.getHeaders().getCacheControl());
        assertTrue(result.getBody().cursorPresent());
        assertEquals(probes,result.getBody().checks());
        verify(client).probeOrderList(eq(cursor.minusSeconds(600)),
                argThat(end->end.isAfter(cursor)));
        verify(store,never()).saveOrders(anyLong(),anyList(),any(),any(),anyInt());
        verify(store,never()).begin(anyString(),anyString());
    }
}
