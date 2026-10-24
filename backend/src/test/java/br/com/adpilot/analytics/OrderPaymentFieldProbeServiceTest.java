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

class OrderPaymentFieldProbeServiceTest {
    NuvemshopClient client;
    OrderPaymentFieldProbeService service;

    @BeforeEach void setup() {
        client=mock(NuvemshopClient.class);
        service=new OrderPaymentFieldProbeService(client);
    }

    private static Map<String,Object> order(long id,String state,String payment) {
        var result=new HashMap<String,Object>();
        result.put("id",id);
        result.put("status",state);
        result.put("payment_status",payment);
        result.put("customer",Map.of("email","private@example.com"));
        return result;
    }

    @SafeVarargs
    private static NuvemshopPage page(int number,Long total,boolean next,
            Map<String,Object>... rows) {
        return new NuvemshopPage(List.of(rows),number,100,total,next);
    }

    @Test void distinguishesListAbsentNullAndDetailNumbersWithoutLeakingAmounts() {
        var first=order(11,"open","paid"); // no list buyerPaid key
        first.put("total_paid_by_customer_including_fees","100.00");
        var second=order(12,"closed","paid");
        second.put("total_paid_by_customer",null);
        second.put("total_paid_by_customer_including_fees",null);
        when(client.orderPaymentSignals(1,100)).thenReturn(page(1,3L,false,
                first,second,order(13,"cancelled","paid")));
        var detailFirst=order(11,"open","paid");
        detailFirst.put("total_paid_by_customer","100.00");
        detailFirst.put("total_paid_by_customer_including_fees","100.00");
        var detailSecond=order(12,"closed","paid");
        detailSecond.put("total_paid_by_customer",null);
        detailSecond.put("total_paid_by_customer_including_fees","200.00");
        when(client.orderPaymentSignalDetails(11)).thenReturn(detailFirst);
        when(client.orderPaymentSignalDetails(12)).thenReturn(detailSecond);

        var result=service.inspect(100,20,2);
        assertTrue(result.upstreamPaginationReconciled());
        assertEquals(2,result.eligibleCandidatesSeen());
        assertEquals(2,result.detailsQueried());
        assertEquals(0,result.stateChangedDuringProbe());
        assertFalse(result.financiallyVerified());
        assertFalse(result.paymentSettlementVerified());
        var buyer=result.fields().get(0);
        assertEquals("total_paid_by_customer",buyer.field());
        assertEquals(1,buyer.listAbsent());
        assertEquals(1,buyer.listNull());
        assertEquals(1,buyer.detailNumeric());
        assertEquals(1,buyer.detailNull());
        assertEquals(1,buyer.numericInDetailWhenListAbsentOrNull());
        var fees=result.fields().get(1);
        assertEquals(1,fees.listNumeric());
        assertEquals(1,fees.listNull());
        assertEquals(2,fees.detailNumeric());
        String safe=result.toString();
        assertFalse(safe.contains("private@example.com"));
        assertFalse(safe.contains("200.00"));
        assertFalse(safe.contains("100.00"));
        verify(client).orderPaymentSignalDetails(11);
        verify(client).orderPaymentSignalDetails(12);
        verify(client,never()).orderPaymentSignalDetails(13);
    }

    @Test void prefersOrdersWithMissingBuyerPaidAndCapsDetailLookups() {
        var withPaid=order(11,"open","paid");
        withPaid.put("total_paid_by_customer","4.00");
        var missing=order(12,"open","paid");
        when(client.orderPaymentSignals(1,100)).thenReturn(page(1,2L,false,
                withPaid,missing));
        when(client.orderPaymentSignalDetails(12)).thenReturn(order(12,"open","paid"));
        var result=service.inspect(100,20,1);
        assertEquals(1,result.detailsQueried());
        assertTrue(result.warnings().contains("DETAIL_LOOKUP_LIMITED_SAMPLE"));
        verify(client).orderPaymentSignalDetails(12);
        verify(client,never()).orderPaymentSignalDetails(11);
    }

    @Test void providerDetailsNotFoundDoNotBecomeNullFinancialFields() {
        when(client.orderPaymentSignals(1,100)).thenReturn(page(1,1L,false,
                order(11,"open","paid")));
        when(client.orderPaymentSignalDetails(11)).thenThrow(new NuvemshopException(
                HttpStatus.BAD_GATEWAY,"ORDER_PAYMENT_DETAIL_NOT_FOUND",
                "Detalhe indisponivel",404));
        var e=assertThrows(NuvemshopException.class,()->service.inspect(100,20,1));
        assertEquals("ORDER_PAYMENT_DETAIL_NOT_FOUND",e.code());
        assertEquals(404,e.upstreamStatus());
    }

    @Test void refusesDetailOfDifferentOrderAndDoesNotExposeIdentifier() {
        when(client.orderPaymentSignals(1,100)).thenReturn(page(1,1L,false,
                order(11,"open","paid")));
        when(client.orderPaymentSignalDetails(11)).thenReturn(order(999,"open","paid"));
        var e=assertThrows(NuvemshopException.class,()->service.inspect(100,20,1));
        assertEquals("NUVEMSHOP_INVALID_ORDER_DATA",e.code());
        assertFalse(e.getMessage().contains("999"));
    }

    @Test void rejectsInvalidPaginationAndMalformedOrdersBeforeDetails() {
        for(int[] bad:new int[][]{{0,20,5},{101,20,5},{100,0,5},
                {100,21,5},{100,20,0},{100,20,6}}) {
            assertEquals("INVALID_PAGINATION",assertThrows(NuvemshopException.class,
                    ()->service.inspect(bad[0],bad[1],bad[2])).code());
        }
        verifyNoInteractions(client);
        when(client.orderPaymentSignals(1,100)).thenReturn(
                new NuvemshopPage(List.of(Map.of("id","pii@example.com",
                        "status","open","payment_status","paid")),1,100,1L,false));
        assertEquals("NUVEMSHOP_INVALID_ORDER_DATA",assertThrows(NuvemshopException.class,
                ()->service.inspect(100,20,2)).code());
        verify(client,never()).orderPaymentSignalDetails(anyLong());
    }

    @Test void incompletePagingCannotClaimCompleteOrderPopulation() {
        when(client.orderPaymentSignals(1,100)).thenReturn(page(1,100L,true,
                order(11,"cancelled","paid")));
        var result=service.inspect(100,1,1);
        assertFalse(result.upstreamPaginationReconciled());
        assertEquals(0,result.detailsQueried());
        assertEquals(0,result.eligibleCandidatesSeen());
    }
}
