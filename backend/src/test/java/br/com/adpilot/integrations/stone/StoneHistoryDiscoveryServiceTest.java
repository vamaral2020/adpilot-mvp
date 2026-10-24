package br.com.adpilot.integrations.stone;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class StoneHistoryDiscoveryServiceTest {
    StoneHistoryClient client;
    StoneHistoryDiscoveryService service;

    @BeforeEach void setup() {
        client=mock(StoneHistoryClient.class);
        service=new StoneHistoryDiscoveryService(client);
        when(client.keyEnvironment()).thenReturn("SANDBOX");
    }

    private static StoneHistoryClient.ProviderOrder o(String id,String status,boolean code) {
        return new StoneHistoryClient.ProviderOrder(id,status,code);
    }
    private static StoneHistoryClient.ProviderCharge c(String id,String parent,
            String status,String method) {
        return new StoneHistoryClient.ProviderCharge(id,parent,status,method,true,true);
    }
    private static <T> StoneHistoryClient.Page<T> page(Long total,boolean more,
            T... rows) {
        return new StoneHistoryClient.Page<>(List.of(rows),total,more,true);
    }

    @Test void discoversProviderOrdersAndMultipleChargesWithoutNuvemshopMatching() {
        when(client.orders(1,3)).thenReturn(page(3L,false,
                o("or_abcd11","PAID",true),
                o("or_abcd22","PENDING",false),
                o("or_abcd33","PAID",true)));
        when(client.charges(1,3)).thenReturn(page(3L,false,
                c("ch_a11111","or_abcd11","FAILED","CREDIT_CARD"),
                c("ch_a22222","or_abcd11","PAID","CREDIT_CARD"),
                c("ch_a33333","or_notobs","PAID","PIX")));
        var result=service.inspect(3,5);
        assertEquals("SANDBOX",result.keyEnvironment());
        assertTrue(result.providerOrders().paginationReconciled());
        assertTrue(result.providerCharges().paginationReconciled());
        assertEquals(3,result.providerOrders().uniqueIds());
        assertEquals(2,result.ordersWithExternalCode());
        assertEquals(3,result.chargesWithProviderOrderReference());
        assertEquals(2,result.chargesLinkedToObservedProviderOrders());
        assertEquals(1,result.chargesReferencingProviderOrdersOutsideSample());
        assertEquals(1,result.observedProviderOrdersWithMultipleCharges());
        assertEquals(Map.of("CREDIT_CARD",2,"PIX",1),result.paymentMethodCounts());
        assertFalse(result.financiallyVerified());
        assertFalse(result.paymentSettlementVerified());
        assertFalse(result.ordersMatchedToNuvemshop());
        assertFalse(result.ordersMatchedToCampaigns());
        assertTrue(result.warnings().contains("MULTIPLE_CHARGES_FOR_PROVIDER_ORDER_OBSERVED"));
        assertFalse(result.toString().contains("ch_a11111"));
        assertFalse(result.toString().contains("or_abcd11"));
        verify(client,never()).orders(2,3);
    }

    @Test void pageLimitNeverCertifiesCompleteHistory() {
        when(client.orders(1,2)).thenReturn(page(4L,true,
                o("or_abcd11","PAID",false),o("or_abcd22","PAID",false)));
        when(client.charges(1,2)).thenReturn(page(4L,true,
                c("ch_a11111",null,"PAID","PIX"),c("ch_a22222",null,"PAID","PIX")));
        var result=service.inspect(2,1);
        assertFalse(result.providerOrders().paginationReconciled());
        assertFalse(result.providerCharges().paginationReconciled());
        assertFalse(result.providerOrders().paginationExhausted());
        assertTrue(result.warnings().contains("INCOMPLETE_PROVIDER_PAGINATION"));
        assertEquals(2,result.chargesWithMissingProviderOrderReference());
        verify(client,never()).orders(2,2);
        verify(client,never()).charges(2,2);
    }

    @Test void duplicateRowsAreDeduplicatedAndNotCountedTwice() {
        when(client.orders(1,2)).thenReturn(page(2L,true,
                o("or_abcd11","PAID",true),o("or_abcd22","PAID",true)));
        when(client.orders(2,2)).thenReturn(page(2L,false,
                o("or_abcd11","PAID",true)));
        when(client.charges(1,2)).thenReturn(page(2L,true,
                c("ch_a11111","or_abcd11","PAID","PIX"),
                c("ch_a22222","or_abcd22","PAID","PIX")));
        when(client.charges(2,2)).thenReturn(page(2L,false,
                c("ch_a11111","or_abcd11","PAID","PIX")));
        var r=service.inspect(2,5);
        assertEquals(1,r.providerOrders().duplicateIds());
        assertEquals(1,r.providerCharges().duplicateIds());
        assertFalse(r.providerOrders().paginationReconciled());
        assertFalse(r.providerCharges().paginationReconciled());
        assertEquals(2,r.chargesWithProviderOrderReference());
    }

    @Test void missingPagingCannotBecomeVerifiedCompleteness() {
        when(client.orders(1,2)).thenReturn(new StoneHistoryClient.Page<>(
                List.of(o("or_abcd11","PAID",false)),null,false,false));
        when(client.charges(1,2)).thenReturn(new StoneHistoryClient.Page<>(
                List.of(),null,false,false));
        var r=service.inspect(2,5);
        assertFalse(r.providerOrders().paginationReconciled());
        assertFalse(r.providerCharges().paginationReconciled());
        assertNull(r.providerOrders().providerReportedTotal());
    }

    @Test void invalidLimitsFailWithoutHttp() {
        for(int[] bad:new int[][]{{0,5},{51,5},{20,0},{20,11}}) {
            var e=assertThrows(StoneChargeException.class,
                    ()->service.inspect(bad[0],bad[1]));
            assertEquals("STONE_INVALID_PAGINATION",e.code());
        }
        verify(client,never()).orders(anyInt(),anyInt());
        verify(client,never()).charges(anyInt(),anyInt());
    }

    @Test void propagatesAuthAndUpstreamErrorsWithoutReturningPartialResults() {
        when(client.orders(1,20)).thenThrow(new StoneChargeException(
                HttpStatus.BAD_GATEWAY,"STONE_CREDENTIALS_REJECTED",
                "Nao autorizado",401));
        var e=assertThrows(StoneChargeException.class,()->service.inspect(20,5));
        assertEquals("STONE_CREDENTIALS_REJECTED",e.code());
        verify(client,never()).charges(anyInt(),anyInt());
    }
}
