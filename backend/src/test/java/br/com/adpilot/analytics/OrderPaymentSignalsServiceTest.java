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

class OrderPaymentSignalsServiceTest {
    NuvemshopClient client;
    OrderPaymentSignalsService service;

    @BeforeEach void setup() {
        client=mock(NuvemshopClient.class);
        service=new OrderPaymentSignalsService(client);
    }

    private static Map<String,Object> order(int id, String state, String payment,
            Object gateway, Object gatewayName, String method, Object gross,
            Object paid, Object paidFees, Object currency) {
        var row=new HashMap<String,Object>();
        row.put("id",id);row.put("status",state);row.put("payment_status",payment);
        row.put("gateway",gateway);row.put("gateway_name",gatewayName);
        row.put("payment_details",method==null ? null : Map.of("method",method,
                "credit_card_company","PRIVATE-CARD-BRAND"));
        row.put("total",gross);row.put("total_paid_by_customer",paid);
        row.put("total_paid_by_customer_including_fees",paidFees);
        row.put("currency",currency);
        row.put("customer",Map.of("email","private@example.com"));
        return row;
    }

    @SafeVarargs private static NuvemshopPage page(int n, Long total,
            boolean more, Map<String,Object>... rows) {
        return new NuvemshopPage(List.of(rows),n,100,total,more);
    }

    @Test void segmentsByProviderMethodCurrencyWithoutAssumingSettlement() {
        when(client.orderPaymentSignals(1,100)).thenReturn(page(1,4L,false,
            order(11,"open","paid","pagar.me",null,"credit_card","100.00","100.00","103.00","BRL"),
            order(12,"closed","paid","pagarme",null,"credit_card","70.00","50.00","51.50","BRL"),
            order(13,"open","paid","nuvem_pago",null,"pix","50.00","50.00","50.00","BRL"),
            order(14,"cancelled","refunded","mercadopago",null,"pix","30.00",null,null,"BRL")
        ));
        var report=service.inspect(100,20);
        assertTrue(report.upstreamPaginationReconciled());
        assertFalse(report.financiallyVerified());
        assertFalse(report.paymentSettlementVerified());
        assertFalse(report.ordersMatchedToCampaigns());
        assertEquals(3,report.upstreamEligiblePaidOrders());
        assertEquals(1,report.upstreamCancelledOrders());
        assertEquals(1,report.upstreamRefundStatusOrders());
        assertEquals(1,report.missingReportedBuyerPaidOrders());
        assertEquals(2,report.eligiblePaidByProviderMethodCurrency().size());
        var pagarme=report.eligiblePaidByProviderMethodCurrency().stream()
                .filter(x->x.provider().equals("PAGARME")).findFirst().orElseThrow();
        assertEquals("CREDIT_CARD",pagarme.paymentMethod());
        assertEquals("BRL",pagarme.currency());
        assertEquals(2,pagarme.eligiblePaidOrders());
        assertEquals(2,pagarme.grossAndPaidComparable());
        assertEquals(1,pagarme.paidLessThanGross());
        assertEquals(0,new BigDecimal("170.00").compareTo(pagarme.grossOrderTotal()));
        assertEquals(0,new BigDecimal("150.00").compareTo(pagarme.reportedBuyerPaidTotal()));
        assertEquals(0,new BigDecimal("154.50").compareTo(pagarme.reportedBuyerPaidIncludingFeesTotal()));
        assertEquals("NUVEM_PAGO",report.eligiblePaidByProviderMethodCurrency().stream()
                .filter(x->x.provider().equals("NUVEM_PAGO")).findFirst().orElseThrow().provider());
        assertFalse(report.toString().contains("private@example.com"));
        assertFalse(report.toString().contains("PRIVATE-CARD-BRAND"));
        assertFalse(report.toString().contains("pagar.me"));
    }

    @Test void unknownAndConflictingGatewaysDoNotBecomeKnownProviders() {
        when(client.orderPaymentSignals(1,100)).thenReturn(page(1,4L,false,
            order(11,"open","paid",null,null,null,"100.00",null,null,"BRL"),
            order(12,"open","paid","other-private-string",null,"custom","10.00",null,null,"BRL"),
            order(13,"open","paid","pagarme","Mercado Pago","credit_card","20.00","20.00",null,"BRL"),
            order(14,"open","paid",null,"Mercado Pago","pix","30.00","30.00",null,"BRL")));
        var report=service.inspect(100,20);
        assertEquals(1,report.missingProviderOrders());
        assertEquals(1,report.unrecognizedProviderOrders());
        assertEquals(1,report.ambiguousProviderOrders());
        assertEquals(1,report.missingPaymentMethodOrders());
        assertEquals(2,report.eligibleOrdersWithReportedBuyerPaid());
        assertEquals(4,report.eligiblePaidByProviderMethodCurrency().size());
        assertTrue(report.warnings().contains("PROVIDER_IDENTIFICATION_INCOMPLETE"));
        assertTrue(report.warnings().contains("PAYMENT_METHOD_INCOMPLETE"));
        assertFalse(report.toString().contains("other-private-string"));
    }

    @Test void invalidFieldsDoNotBecomeMonetaryTotals() {
        when(client.orderPaymentSignals(1,100)).thenReturn(page(1,2L,false,
            order(11,"open","paid","mercado_pago",null,"pix","not-number","1.231",null,"BRL"),
            order(12,"open","paid","mercado_pago",null,"pix","25.00","20.00","20.00","USD")));
        var report=service.inspect(100,20);
        assertEquals(1,report.invalidMonetaryOrCurrencyOrders());
        assertEquals(1,report.eligibleOrdersWithGrossAndBuyerPaidComparable());
        var brl=report.eligiblePaidByProviderMethodCurrency().stream()
                .filter(x->x.currency().equals("BRL")).findFirst().orElseThrow();
        assertEquals(0,brl.ordersWithGrossTotal());
        assertEquals(0,brl.ordersWithReportedBuyerPaid());
        assertEquals(0,new BigDecimal("0.00").compareTo(brl.grossOrderTotal()));
        assertEquals(1,report.eligiblePaidByProviderMethodCurrency().stream()
                .filter(x->x.currency().equals("USD")).findFirst().orElseThrow().paidLessThanGross());
    }

    @Test void missingTotalAndPaginationCapNeverIndicateCompleteRead() {
        when(client.orderPaymentSignals(1,100)).thenReturn(page(1,3L,true,
            order(11,"open","pending",null,null,null,"1.00",null,null,"BRL")));
        var report=service.inspect(100,1);
        assertFalse(report.upstreamPaginationReconciled());
        assertTrue(report.warnings().contains("UPSTREAM_PAGINATION_NOT_RECONCILED"));
        verify(client,never()).orderPaymentSignals(2,100);
    }

    @Test void rejectsMalformedIdsOrStatusesWithoutLeakingData() {
        when(client.orderPaymentSignals(1,100)).thenReturn(new NuvemshopPage(
                List.of(Map.of("id","PII@example.com","status","open","payment_status","paid")),
                1,100,1L,false));
        var e=assertThrows(NuvemshopException.class,()->service.inspect(100,20));
        assertEquals("NUVEMSHOP_INVALID_ORDER_DATA",e.code());
        assertFalse(e.getMessage().contains("PII@example.com"));
        reset(client);
        when(client.orderPaymentSignals(1,100)).thenReturn(new NuvemshopPage(
                List.of(Map.of("id",1,"status","PRIVATE@EMAIL","payment_status","paid")),
                1,100,1L,false));
        assertEquals("NUVEMSHOP_INVALID_ORDER_DATA",
                assertThrows(NuvemshopException.class,()->service.inspect(100,20)).code());
    }

    @Test void rejectsInvalidLimitsBeforeApiCalls() {
        for(int[] params:new int[][]{{0,1},{101,1},{100,0},{100,21}})
            assertEquals("INVALID_PAGINATION",assertThrows(NuvemshopException.class,
                    ()->service.inspect(params[0],params[1])).code());
        verifyNoInteractions(client);
    }

    @Test void preventsDuplicateOrUnstableTotalsFromCertifyingPagination() {
        when(client.orderPaymentSignals(1,100)).thenReturn(page(1,2L,true,
            order(11,"open","paid","pagarme",null,"credit_card","1.00","1.00","1.00","BRL")));
        when(client.orderPaymentSignals(2,100)).thenReturn(page(2,3L,false,
            order(11,"open","paid","pagarme",null,"credit_card","1.00","1.00","1.00","BRL")));
        var report=service.inspect(100,20);
        assertFalse(report.upstreamPaginationReconciled());
        assertEquals(1,report.duplicateOrderIds());
        assertEquals(1,report.uniqueOrderIds());
    }
    @Test void separatesStonePspGatewayAndBareBrandWithoutAssumingAcquirer() {
        when(client.orderPaymentSignals(1,100)).thenReturn(page(1,5L,false,
            order(1,"open","paid","Stone PSP","stone","credit_card","100.00",null,null,"BRL"),
            order(2,"open","paid","stone_gateway",null,"credit_card","200.00",null,null,"BRL"),
            order(3,"open","paid","Stone",null,"pix","20.00",null,null,"BRL"),
            order(4,"open","paid","pagar.me",null,"credit_card","30.00",null,null,"BRL"),
            order(5,"open","paid","stone psp","Stone Gateway","pix","40.00",null,null,"BRL")
        ));
        var report=service.inspect(100,20);
        var byProvider=report.eligiblePaidByProviderMethodCurrency();
        assertEquals(5,report.upstreamEligiblePaidOrders());
        assertEquals(1,report.ambiguousProviderOrders());
        assertEquals(1,byProvider.stream().filter(g->g.provider().equals("STONE_PSP")).count());
        assertEquals(1,byProvider.stream().filter(g->g.provider().equals("STONE_GATEWAY")).count());
        assertEquals(1,byProvider.stream().filter(g->g.provider().equals("STONE_UNSPECIFIED")).count());
        assertEquals(1,byProvider.stream().filter(g->g.provider().equals("PAGARME")).count());
        assertEquals(1,byProvider.stream().filter(g->g.provider().equals("AMBIGUOUS")).count());
        assertFalse(report.financiallyVerified());
        assertFalse(report.paymentSettlementVerified());
    }

    @Test void unknownGatewayEvidenceIsVocabularyOnlyNeverRaw() {
        when(client.orderPaymentSignals(1,100)).thenReturn(page(1,3L,false,
            order(11,"open","paid","my-stone-customer-123",null,"credit_card","100.00",null,null,"BRL"),
            order(12,"open","paid","private-shop@example.com","other-private-id","credit_card","20.00",null,null,"BRL"),
            order(13,"cancelled","pending","notstone",null,"pix","30.00",null,null,"BRL")
        ));
        var report=service.inspect(100,20);
        assertEquals(3,report.unrecognizedProviderOrders());
        assertEquals(3,report.unknownGatewayEvidence().stream()
                .mapToInt(x->x.orders()).sum());
        assertEquals(2,report.unknownGatewayEvidence().stream()
                .mapToInt(x->x.eligiblePaidOrders()).sum());
        assertTrue(report.unknownGatewayEvidence().stream().anyMatch(x->
                x.brandHint().equals("STONE_TEXT")));
        String serialized=report.toString();
        assertFalse(serialized.contains("private-shop"));
        assertFalse(serialized.contains("my-stone-customer"));
        assertFalse(serialized.contains("notstone"));
        assertFalse(serialized.contains("other-private-id"));
    }

}
