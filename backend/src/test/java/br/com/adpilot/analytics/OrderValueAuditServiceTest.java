package br.com.adpilot.analytics;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import br.com.adpilot.integrations.nuvemshop.NuvemshopClient;
import br.com.adpilot.integrations.nuvemshop.NuvemshopException;
import br.com.adpilot.integrations.nuvemshop.NuvemshopPage;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OrderValueAuditServiceTest {
    NuvemshopClient client;
    OrderValueAuditRepository repository;
    OrderValueAuditService service;

    @BeforeEach void setup() {
        client = mock(NuvemshopClient.class);
        repository = mock(OrderValueAuditRepository.class);
        service = new OrderValueAuditService(client, repository);
    }

    private static Map<String,Object> upstream(Object id, Object status,
            Object paymentStatus, Object total, Object currency) {
        var result=new HashMap<String,Object>();
        result.put("id",id);
        result.put("status",status);
        result.put("payment_status",paymentStatus);
        result.put("total",total);
        result.put("currency",currency);
        result.put("customer",Map.of("email","private@example.com"));
        result.put("payment_details",Map.of("card","PRIVATE-CARD"));
        return result;
    }

    private static OrderValueAuditRepository.Snapshot local(long id, String state,
            String payment, String total, String currency, boolean eligible) {
        return new OrderValueAuditRepository.Snapshot(Long.toString(id),state,payment,
                total == null ? null : new BigDecimal(total),currency,eligible);
    }

    @SafeVarargs
    private static NuvemshopPage page(int number, Long total, boolean next,
            Map<String,Object>... rows) {
        return new NuvemshopPage(List.of(rows),number,100,total,next);
    }

    @Test void matchingGrossValuesExcludeCancelledAndRefundStatuses() {
        when(client.orderValueAuditOrders(1,100)).thenReturn(page(1,5L,true,
                upstream(11,"open","paid","100.00","BRL"),
                upstream(12,"cancelled","paid","80.00","BRL"),
                upstream(13,"closed","refunded","20.00","BRL")));
        when(client.orderValueAuditOrders(2,100)).thenReturn(page(2,5L,false,
                upstream(14,"closed","paid","50.00","BRL"),
                upstream(15,"open","pending","5.00","BRL")));
        when(repository.find(List.of("11","12","13"))).thenReturn(Map.of(
                "11",local(11,"open","paid","100.00","BRL",true),
                "12",local(12,"cancelled","paid","80.00","BRL",false),
                "13",local(13,"closed","refunded","20.00","BRL",false)));
        when(repository.find(List.of("14","15"))).thenReturn(Map.of(
                "14",local(14,"closed","paid","45.00","BRL",true),
                "15",local(15,"open","pending","5.00","BRL",false)));
        var result=service.audit(100,20);

        assertTrue(result.upstreamPaginationReconciled());
        assertFalse(result.financiallyVerified());
        assertFalse(result.ordersMatchedToCampaigns());
        assertEquals(5,result.uniqueUpstreamOrderIds());
        assertEquals(5L,result.upstreamReportedTotal());
        assertEquals(2,result.upstreamEligiblePaidOrders());
        assertEquals(2,result.localEligiblePaidOrdersAmongMatches());
        assertEquals(2,result.mutuallyEligiblePaidOrders());
        assertEquals(2,result.comparableEligiblePaidOrders());
        assertEquals(1,result.upstreamCancelledOrders());
        assertEquals(1,result.upstreamRefundStatusOrders());
        assertEquals(1,result.amountDifferences());
        assertEquals(0,result.currencyDifferences());
        assertEquals(0,result.eligibilityDifferences());
        assertEquals(0,result.orderIdsMissingLocally());
        assertEquals(1,result.comparableGrossByCurrency().size());
        var brl=result.comparableGrossByCurrency().getFirst();
        assertEquals("BRL",brl.currency());
        assertEquals(2,brl.comparedOrders());
        assertEquals(0,new BigDecimal("150.00").compareTo(brl.upstreamGrossTotal()));
        assertEquals(0,new BigDecimal("145.00").compareTo(brl.localGrossTotal()));
        assertEquals(0,new BigDecimal("5.00").compareTo(brl.signedGrossDifference()));
        assertTrue(result.warnings().contains("GROSS_ORDER_TOTAL_DIFFERS"));
        String visible=result.toString();
        assertFalse(visible.contains("private@example.com"));
        assertFalse(visible.contains("PRIVATE-CARD"));
        verify(repository).find(List.of("11","12","13"));
        verify(repository).find(List.of("14","15"));
        verify(client,times(2)).orderValueAuditOrders(anyInt(),eq(100));
    }

    @Test void mismatchedCurrencyAndStatusAreNotCombinedWithComparableTotals() {
        when(client.orderValueAuditOrders(1,100)).thenReturn(page(1,4L,false,
                upstream(21,"open","paid","99.00","BRL"),
                upstream(22,"open","paid","100.00","BRL"),
                upstream(23,"open","paid","secret-invalid","BRL"),
                upstream(24,"open","paid","25.00","BRL")));
        when(repository.find(List.of("21","22","23","24"))).thenReturn(Map.of(
                "21",local(21,"open","paid","99.00","USD",true),
                "22",local(22,"cancelled","paid","100.00","BRL",false),
                "24",local(24,"open","paid","25.00","BRL",true)));
        var result=service.audit(100,20);
        assertEquals(4,result.upstreamEligiblePaidOrders());
        assertEquals(1,result.orderIdsMissingLocally());
        assertEquals(2,result.localEligiblePaidOrdersAmongMatches());
        assertEquals(2,result.mutuallyEligiblePaidOrders());
        assertEquals(1,result.comparableEligiblePaidOrders());
        assertEquals(1,result.currencyDifferences());
        assertEquals(1,result.orderStatusDifferences());
        assertEquals(1,result.eligibilityDifferences());
        assertEquals(1,result.upstreamInvalidAmount());
        assertEquals(0,result.amountDifferences());
        assertEquals(1,result.comparableGrossByCurrency().getFirst().comparedOrders());
        assertEquals(0,new BigDecimal("25.00").compareTo(
                result.comparableGrossByCurrency().getFirst().upstreamGrossTotal()));
        assertTrue(result.warnings().contains("INVALID_OR_MISSING_MONETARY_FIELDS"));
        assertTrue(result.warnings().contains("ORDER_IDS_MISSING_LOCALLY"));
        assertFalse(result.toString().contains("secret-invalid"));
    }

    @Test void separateCurrenciesRemainSeparateEvenWhenNumbersLookAdditive() {
        when(client.orderValueAuditOrders(1,100)).thenReturn(page(1,2L,false,
                upstream(31,"open","paid","30.00","BRL"),
                upstream(32,"closed","paid","40.00","USD")));
        when(repository.find(List.of("31","32"))).thenReturn(Map.of(
                "31",local(31,"open","paid","30.00","BRL",true),
                "32",local(32,"closed","paid","40.00","USD",true)));
        var result=service.audit(100,20);
        assertEquals(2,result.comparableGrossByCurrency().size());
        assertEquals("BRL",result.comparableGrossByCurrency().get(0).currency());
        assertEquals("USD",result.comparableGrossByCurrency().get(1).currency());
        assertEquals(1,result.comparableGrossByCurrency().get(0).comparedOrders());
        assertEquals(1,result.comparableGrossByCurrency().get(1).comparedOrders());
    }

    @Test void duplicateIdsAndTruncatedResultsAreNeverCertifiedAsFullCollection() {
        when(client.orderValueAuditOrders(1,100)).thenReturn(page(1,2L,true,
                upstream(11,"open","paid","12.00","BRL")));
        when(client.orderValueAuditOrders(2,100)).thenReturn(page(2,2L,false,
                upstream(11,"open","paid","12.00","BRL"),
                upstream(12,"open","pending","12.00","BRL")));
        when(repository.find(List.of("11"))).thenReturn(Map.of(
                "11",local(11,"open","paid","12.00","BRL",true)));
        when(repository.find(List.of("12"))).thenReturn(Map.of(
                "12",local(12,"open","pending","12.00","BRL",false)));
        var duplicates=service.audit(100,20);
        assertFalse(duplicates.upstreamPaginationReconciled());
        assertEquals(1,duplicates.duplicateUpstreamIds());
        assertEquals(2,duplicates.uniqueUpstreamOrderIds());
        assertEquals(1,duplicates.comparableEligiblePaidOrders());

        reset(client,repository);
        when(client.orderValueAuditOrders(1,100)).thenReturn(page(1,200L,true,
                upstream(11,"open","paid","12.00","BRL")));
        when(repository.find(List.of("11"))).thenReturn(Map.of());
        var truncated=service.audit(100,1);
        assertFalse(truncated.upstreamPaginationReconciled());
        assertTrue(truncated.warnings().contains("UPSTREAM_PAGINATION_NOT_RECONCILED"));
        verify(client,never()).orderValueAuditOrders(2,100);
    }

    @Test void missingOrChangingUpstreamTotalsPreventPaginationReconciliation() {
        when(client.orderValueAuditOrders(1,100)).thenReturn(page(1,null,false,
                upstream(11,"open","pending","1.00","BRL")));
        var missing=service.audit(100,1);
        assertFalse(missing.upstreamPaginationReconciled());
        assertNull(missing.upstreamReportedTotal());
        reset(client,repository);
        when(client.orderValueAuditOrders(1,100)).thenReturn(page(1,2L,true,
                upstream(11,"open","pending","1.00","BRL")));
        when(client.orderValueAuditOrders(2,100)).thenReturn(page(2,3L,false,
                upstream(12,"open","pending","1.00","BRL")));
        var changed=service.audit(100,3);
        assertFalse(changed.upstreamPaginationReconciled());
        assertNull(changed.upstreamReportedTotal());
    }

    @Test void malformedIdsAndStatusesFailWithoutLeakingPersonalDetails() {
        when(client.orderValueAuditOrders(1,100)).thenReturn(page(1,1L,false,
                upstream("private@example.com","open","paid","1.00","BRL")));
        var idError=assertThrows(NuvemshopException.class,()->service.audit(100,20));
        assertEquals("NUVEMSHOP_INVALID_ORDER_DATA",idError.code());
        assertFalse(idError.getMessage().contains("private@example.com"));
        verifyNoInteractions(repository);

        reset(client,repository);
        when(client.orderValueAuditOrders(1,100)).thenReturn(page(1,1L,false,
                upstream(11,"private@example.com","paid","1.00","BRL")));
        var statusError=assertThrows(NuvemshopException.class,()->service.audit(100,20));
        assertEquals("NUVEMSHOP_INVALID_ORDER_DATA",statusError.code());
        assertFalse(statusError.getMessage().contains("private@example.com"));
        verifyNoInteractions(repository);
    }

    @Test void invalidLimitsBlockUpstreamBeforeAnyNetworkOrSqlCalls() {
        for (int[] bad:new int[][]{{0,20},{101,20},{100,0},{100,21}}) {
            assertEquals("INVALID_PAGINATION",assertThrows(NuvemshopException.class,
                    ()->service.audit(bad[0],bad[1])).code());
        }
        verifyNoInteractions(client,repository);
    }

    @Test void rejectsEmptyPageWithContinuationWithoutFabricatingZeroRevenue() {
        when(client.orderValueAuditOrders(1,100))
                .thenReturn(new NuvemshopPage(List.of(),1,100,2L,true));
        assertEquals("NUVEMSHOP_INVALID_PAGINATION",
                assertThrows(NuvemshopException.class,()->service.audit(100,20)).code());
        verifyNoInteractions(repository);
    }

    @Test void malformedMoneyIsCountedButNeverIncludedInGrossTotals() {
        when(client.orderValueAuditOrders(1,100)).thenReturn(page(1,3L,false,
                upstream(71,"open","paid","1.234","BRL"),
                upstream(72,"open","paid","-3.00","BRL"),
                upstream(73,"open","paid","1e99","BRL")));
        when(repository.find(List.of("71","72","73"))).thenReturn(Map.of(
                "71",local(71,"open","paid","1.00","BRL",true),
                "72",local(72,"open","paid","3.00","BRL",true),
                "73",local(73,"open","paid","2.00","BRL",true)));
        var result=service.audit(100,20);
        assertEquals(3,result.upstreamInvalidAmount());
        assertEquals(0,result.comparableEligiblePaidOrders());
        assertTrue(result.comparableGrossByCurrency().isEmpty());
    }
}
