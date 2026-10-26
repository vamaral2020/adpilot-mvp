package br.com.adpilot.integrations.meta;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class MetaFunnelDiagnosticsControllerTest {
    @Test void noStoreOnReadOnlyFunnelEndpoint() {
        var funnel=mock(MetaFunnelDiagnosticsService.class);
        var controller=new MetaAdsController(mock(MetaSettings.class),
                mock(MetaAdsClient.class),mock(MetaInsightsSummaryService.class),
                mock(MetaPurchaseAuditService.class),mock(MetaFormatService.class),
                mock(MetaDeliveryInterpreterService.class),funnel);
        var from=LocalDate.of(2026,10,1);
        var to=LocalDate.of(2026,10,7);
        var body=new MetaFunnelDiagnosticsService.Report("123","OUTCOME_SALES","BRL",
                from,to,"HISTORICAL_CONTEXT",false,true,
                new MetaDeliveryInterpreterService.Coverage(true,1,1),
                new MetaDeliveryInterpreterService.Coverage(true,1,1),
                null,List.of(),List.of(),List.of(),List.of(),List.of());
        when(funnel.inspect("123",from,to,100,3)).thenReturn(body);
        var response=controller.funnelDiagnostics("123",from,to,100,3);
        assertEquals(200,response.getStatusCode().value());
        assertEquals("no-store",response.getHeaders().getCacheControl());
        assertSame(body,response.getBody());
        verify(funnel).inspect("123",from,to,100,3);
        verifyNoMoreInteractions(funnel);
    }
}
