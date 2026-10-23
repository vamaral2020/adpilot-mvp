package br.com.adpilot.analytics;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class CommercialReconciliationControllerTest {
    private final CommercialReconciliationService service=mock(CommercialReconciliationService.class);
    private final CommercialReconciliationController controller=
            new CommercialReconciliationController(service);

    @Test void isReadOnlyAndDoesNotAllowResponseCaching() {
        var since=LocalDate.of(2026,10,20);
        var until=LocalDate.of(2026,10,22);
        var expected=new CommercialReconciliationService.Report(since,until,
                "INSUFFICIENT_OBSERVATIONS",false,false,
                new CommercialReconciliationService.MetaObserved(null,null,null,null,0),
                new CommercialReconciliationService.StoreObserved(null,null,null,0),
                null,null,List.of(),List.of(),null,
                List.of("NOT_ORDER_TO_CAMPAIGN_RECONCILIATION"));
        when(service.compare(since,until)).thenReturn(expected);
        var response=controller.report(since,until);
        assertEquals(200,response.getStatusCode().value());
        assertEquals("no-store",response.getHeaders().getCacheControl());
        assertSame(expected,response.getBody());
        verify(service).compare(since,until);
        verifyNoMoreInteractions(service);
    }
}
