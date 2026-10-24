package br.com.adpilot.analytics;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import br.com.adpilot.integrations.nuvemshop.NuvemshopClient;
import br.com.adpilot.integrations.nuvemshop.NuvemshopException;
import br.com.adpilot.integrations.nuvemshop.NuvemshopPage;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class OrderValueHistoryDiagnosticServiceTest {
    NuvemshopClient client;
    OrderValueHistoryDiagnosticService service;

    @BeforeEach void init() {
        client = mock(NuvemshopClient.class);
        service = new OrderValueHistoryDiagnosticService(client);
    }

    private static Map<String,Object> order(Object id, String state, String payment) {
        var map = new HashMap<String,Object>();
        map.put("id",id);
        map.put("status",state);
        map.put("payment_status",payment);
        map.put("customer",Map.of("email","private@example.com"));
        return map;
    }

    private static Map<String,Object> event(Object status, Object delta) {
        var map = new HashMap<String,Object>();
        map.put("status",status);
        map.put("total_paid_diff",delta);
        map.put("gateway_name","secret-gateway-account");
        return map;
    }

    @SafeVarargs
    private static NuvemshopPage page(int idx, Long total, boolean next,
            Map<String,Object>... entries) {
        return new NuvemshopPage(List.of(entries),idx,100,total,next);
    }

    @Test void prioritizesRefundThenCancellationThenPaidAndCountsOnlyHistorySignals() {
        when(client.orderValueAuditOrders(1,100)).thenReturn(page(1,4L,true,
                order(11,"open","paid"),order(12,"cancelled","paid")));
        when(client.orderValueAuditOrders(2,100)).thenReturn(page(2,4L,false,
                order(13,"open","refunded"),order(14,"closed","paid")));
        when(client.orderValueHistory(13)).thenReturn(List.of(
                event("PAID",10000),event("PAID",-5000),event("PENDING",0)));
        when(client.orderValueHistory(12)).thenReturn(List.of());
        when(client.orderValueHistory(11)).thenReturn(List.of(event("PAID",2000)));

        var result=service.inspect(100,20,3);
        assertTrue(result.upstreamPaginationReconciled());
        assertFalse(result.entireOrderPopulationExamined());
        assertFalse(result.financiallyVerified());
        assertFalse(result.paymentSettlementVerified());
        assertFalse(result.ordersMatchedToCampaigns());
        assertEquals(4,result.uniqueUpstreamOrders());
        assertEquals(3,result.historiesQueried());
        assertEquals(1,result.selectedRefundStatusOrders());
        assertEquals(1,result.selectedCancelledOrders());
        assertEquals(2,result.selectedPaidOrders());
        assertEquals(1,result.ordersWithEmptyValueHistory());
        assertEquals(2,result.ordersWithPositivePaidMovement());
        assertEquals(1,result.ordersWithNegativePaidMovement());
        assertEquals(2,result.paidPositiveEvents());
        assertEquals(1,result.paidNegativeEvents());
        assertEquals(1,result.pendingEvents());
        assertEquals(0,result.cancelledEvents());
        assertTrue(result.warnings().contains("HISTORY_LOOKUP_LIMITED_SAMPLE"));
        assertTrue(result.warnings().contains("EMPTY_HISTORY_DOES_NOT_PROVE_NO_REFUNDS"));
        assertTrue(result.warnings().contains("NEGATIVE_PAID_MOVEMENTS_OBSERVED"));
        verify(client).orderValueHistory(13);
        verify(client).orderValueHistory(12);
        verify(client).orderValueHistory(11);
        verify(client,never()).orderValueHistory(14);
        assertFalse(result.toString().contains("private@example.com"));
        assertFalse(result.toString().contains("secret-gateway-account"));
        assertFalse(result.toString().contains("10000"));
        assertFalse(result.toString().contains("5000"));
    }

    @Test void readsEveryOrderWhenBoundCoversObservedPopulationButStillNotFinanciallyVerified() {
        when(client.orderValueAuditOrders(1,100)).thenReturn(page(1,2L,false,
                order(11,"open","paid"),order(12,"closed","pending")));
        when(client.orderValueHistory(11)).thenReturn(List.of(event("PAID","1750")));
        when(client.orderValueHistory(12)).thenReturn(List.of(event("CANCELLED",0)));
        var result=service.inspect(100,20,2);
        assertTrue(result.entireOrderPopulationExamined());
        assertTrue(result.upstreamPaginationReconciled());
        assertFalse(result.paymentSettlementVerified());
        assertEquals(1,result.paidPositiveEvents());
        assertEquals(1,result.cancelledEvents());
    }

    @Test void flagsAmbiguousHistoriesInsteadOfInventingPaymentTotals() {
        when(client.orderValueAuditOrders(1,100)).thenReturn(page(1,1L,false,
                order(11,"open","paid")));
        when(client.orderValueHistory(11)).thenReturn(List.of(
                event("PAID","invalid-raw"),event("PAID",null),
                event("PENDING",3),event("OTHER",0),event("PAID",0)));
        var result=service.inspect(100,20,1);
        assertEquals(2,result.invalidPaidDiffEvents());
        assertEquals(1,result.unknownStatusEvents());
        assertEquals(1,result.nonPaidNonzeroEvents());
        assertEquals(1,result.paidZeroEvents());
        assertEquals(1,result.ordersWithInvalidValueHistory());
        assertTrue(result.warnings().contains("HISTORY_VALUES_UNINTERPRETABLE"));
    }

    @Test void partialPagesAndDuplicatesCannotCountAsExhaustive() {
        when(client.orderValueAuditOrders(1,100)).thenReturn(page(1,3L,true,
                order(11,"open","paid")));
        when(client.orderValueAuditOrders(2,100)).thenReturn(page(2,3L,false,
                order(11,"open","paid"),order(12,"closed","paid")));
        when(client.orderValueHistory(11)).thenReturn(List.of());
        when(client.orderValueHistory(12)).thenReturn(List.of());
        var duplicate=service.inspect(100,20,5);
        assertEquals(1,duplicate.duplicateOrderIds());
        assertFalse(duplicate.upstreamPaginationReconciled());
        assertFalse(duplicate.entireOrderPopulationExamined());

        reset(client);
        when(client.orderValueAuditOrders(1,100)).thenReturn(page(1,3L,true,
                order(11,"open","paid")));
        when(client.orderValueHistory(11)).thenReturn(List.of());
        var truncated=service.inspect(100,1,5);
        assertFalse(truncated.upstreamPaginationReconciled());
        assertTrue(truncated.warnings().contains("UPSTREAM_PAGINATION_NOT_RECONCILED"));
        verify(client,never()).orderValueAuditOrders(2,100);
    }

    @Test void upstreamPermissionDenialFailsClosedRatherThanReturningEmptyHistory() {
        when(client.orderValueAuditOrders(1,100)).thenReturn(page(1,1L,false,
                order(11,"open","paid")));
        when(client.orderValueHistory(11)).thenThrow(new NuvemshopException(
                HttpStatus.BAD_GATEWAY,"ORDER_VALUE_HISTORY_DENIED",
                "Historico indisponivel",403));
        var error=assertThrows(NuvemshopException.class,()->service.inspect(100,20,1));
        assertEquals("ORDER_VALUE_HISTORY_DENIED",error.code());
        assertEquals(403,error.upstreamStatus());
    }

    @Test void rejectsUntrustedOrderStatusesAndIdsWithoutQueryingHistory() {
        when(client.orderValueAuditOrders(1,100)).thenReturn(page(1,1L,false,
                order("private@example.com","open","paid")));
        var err=assertThrows(NuvemshopException.class,()->service.inspect(100,20,1));
        assertEquals("NUVEMSHOP_INVALID_ORDER_DATA",err.code());
        assertFalse(err.getMessage().contains("private@example.com"));
        verify(client,never()).orderValueHistory(anyLong());

        reset(client);
        when(client.orderValueAuditOrders(1,100)).thenReturn(page(1,1L,false,
                order(11,"secret@domain.test","paid")));
        var invalid=assertThrows(NuvemshopException.class,()->service.inspect(100,20,1));
        assertEquals("NUVEMSHOP_INVALID_ORDER_DATA",invalid.code());
        verify(client,never()).orderValueHistory(anyLong());
    }

    @Test void rejectsBadLimitsBeforeAnyProviderCalls() {
        for (var limits:new int[][] {
                {0,1,1},{101,1,1},{100,0,1},{100,21,1},
                {100,1,0},{100,1,51}
        }) {
            assertEquals("INVALID_PAGINATION",
                    assertThrows(NuvemshopException.class,
                            ()->service.inspect(limits[0],limits[1],limits[2])).code());
        }
        verifyNoInteractions(client);
    }

    @Test void rejectsEmptyPageWithContinuation() {
        when(client.orderValueAuditOrders(1,100))
                .thenReturn(new NuvemshopPage(List.of(),1,100,3L,true));
        assertEquals("NUVEMSHOP_INVALID_PAGINATION",
                assertThrows(NuvemshopException.class,()->service.inspect(100,20,3)).code());
        verify(client,never()).orderValueHistory(anyLong());
    }
}
