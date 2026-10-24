package br.com.adpilot.integrations.stone;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import br.com.adpilot.integrations.nuvemshop.NuvemshopClient;
import br.com.adpilot.integrations.nuvemshop.NuvemshopPage;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class StoneNuvemCorrelationServiceTest {
    private StoneHistoryClient stone;
    private NuvemshopClient shop;
    private StoneNuvemCorrelationService service;
    private static final Instant CREATED=Instant.parse("2026-10-20T12:00:00Z");

    @BeforeEach void setup() {
        stone=mock(StoneHistoryClient.class);
        shop=mock(NuvemshopClient.class);
        service=new StoneNuvemCorrelationService(stone,shop);
        when(stone.keyEnvironment()).thenReturn("UNDETERMINED");
    }

    private static Map<String,Object> shopRow(String id,String number,
            String paid,String value,String method) {
        var m=new LinkedHashMap<String,Object>();
        m.put("id",Long.parseLong(id));
        m.put("number",Integer.parseInt(number));
        m.put("status","open");
        m.put("payment_status",paid);
        m.put("total",value);
        m.put("currency","BRL");
        m.put("created_at","2026-10-20T12:00:00+00:00");
        m.put("payment_details",Map.of("method",method));
        return m;
    }

    private static StoneHistoryClient.CrossReferenceOrder order(String id,String code,
            String status,long cents) {
        return new StoneHistoryClient.CrossReferenceOrder(id,code,status,
                "BRL",cents,CREATED);
    }

    private static StoneHistoryClient.ProviderCharge charge(String id,String parent,
            String status,String method) {
        return new StoneHistoryClient.ProviderCharge(id,parent,status,method,true,true);
    }

    private static <T> StoneHistoryClient.Page<T> page(T... rows) {
        return new StoneHistoryClient.Page<>(List.of(rows),(long)rows.length,false,true);
    }

    private void fixtures(List<Map<String,Object>> shopRows,
            List<StoneHistoryClient.CrossReferenceOrder> orders,
            List<StoneHistoryClient.ProviderCharge> charges) {
        when(shop.orderCrossProviderCandidates(1,50)).thenReturn(
                new NuvemshopPage(shopRows,1,50,(long)shopRows.size(),false));
        when(stone.correlationOrders(1,50)).thenReturn(
                new StoneHistoryClient.Page<>(orders,(long)orders.size(),false,true));
        when(stone.charges(1,50)).thenReturn(
                new StoneHistoryClient.Page<>(charges,(long)charges.size(),false,true));
    }

    @Test void exactCodeCandidatesCompareSafelyAndBlockManyToOne() {
        fixtures(List.of(
                shopRow("9001","158","paid","100.00","credit_card"),
                shopRow("9002","159","pending","50.00","pix"),
                shopRow("9003","160","paid","60.00","pix"),
                shopRow("9004","161","paid","60.00","pix")),
                List.of(order("or_aaa111","158","PAID",10000),
                        order("or_bbb222","9002","PENDING",5000),
                        order("or_ccc333","987","PAID",5000),
                        order("or_ddd444","#160","PAID",6000),
                        order("or_eee555","161","PAID",6100),
                        order("or_fff666","158","FAILED",10000)),
                List.of(charge("ch_aaa111","or_aaa111","PAID","CREDIT_CARD"),
                        charge("ch_bbb222","or_bbb222","PENDING","PIX"),
                        charge("ch_eee555","or_eee555","PAID","PIX"),
                        charge("ch_fff666","or_fff666","FAILED","CREDIT_CARD")));
        var r=service.inspect(50,10);
        assertTrue(r.providerOrders().paginationReconciled());
        assertTrue(r.providerCharges().paginationReconciled());
        assertTrue(r.nuvemshopOrders().paginationReconciled());
        assertEquals(6,r.providerOrdersWithCode());
        assertEquals(1,r.providerCodesUnsupportedForExactMatch());
        assertEquals(1,r.providerCodesWithoutCandidate());
        assertEquals(0,r.providerCodesWithAmbiguousTargets());
        assertEquals(2,r.providerOrdersWithDuplicateTarget());
        assertEquals(1,r.candidateByNuvemshopId());
        assertEquals(1,r.candidateByNuvemshopNumber());
        assertEquals(2,r.uniqueOneToOneCandidates());
        assertEquals(2,r.amountsComparable());
        assertEquals(1,r.amountsEqual());
        assertEquals(1,r.amountsDifferent());
        assertEquals(2,r.paidFlagsAligned());
        assertEquals(2,r.creationTimesWithin48Hours());
        assertEquals(2,r.paymentMethodsEqual());
        assertEquals(0,r.paymentMethodsDifferent());
        assertFalse(r.financiallyVerified());
        assertFalse(r.paymentSettlementVerified());
        assertFalse(r.ordersMatchedToNuvemshop());
        assertFalse(r.ordersMatchedToCampaigns());
        assertTrue(r.warnings().contains("AMBIGUOUS_OR_DUPLICATE_CORRELATION"));
        assertTrue(r.warnings().contains("GROSS_AMOUNT_DISAGREEMENT_ON_CANDIDATES"));
        String serialized=r.toString();
        assertFalse(serialized.contains("or_aaa111"));
        assertFalse(serialized.contains("ch_aaa111"));
        assertFalse(serialized.contains("9001"));
        assertFalse(serialized.contains("100.00"));
    }

    @Test void collisionsBetweenIdAndAnotherOrdersNumberAreAmbiguous() {
        fixtures(List.of(shopRow("201","101","paid","50.00","pix"),
                shopRow("202","201","paid","70.00","pix")),
                List.of(order("or_aaa111","201","PAID",5000)),List.of());
        var r=service.inspect(50,10);
        assertEquals(1,r.providerCodesWithAmbiguousTargets());
        assertEquals(0,r.uniqueOneToOneCandidates());
        assertEquals(0,r.amountsComparable());
    }

    @Test void missingProviderAmountAndDateKeepComparisonsUnknown() {
        fixtures(List.of(shopRow("9001","158","paid","100.00","credit_card")),
                List.of(new StoneHistoryClient.CrossReferenceOrder("or_aaa111",
                        "158","PAID","UNKNOWN",null,null)),List.of());
        var r=service.inspect(50,10);
        assertEquals(1,r.uniqueOneToOneCandidates());
        assertEquals(1,r.amountsUnavailable());
        assertEquals(0,r.amountsComparable());
        assertEquals(0,r.creationTimesComparable());
        assertEquals(1,r.candidatesWithoutObservedProviderCharge());
    }

    @Test void multipleChargesAreNotReducedToSingleMethod() {
        fixtures(List.of(shopRow("9001","158","paid","100.00","pix")),
                List.of(order("or_aaa111","158","PAID",10000)),
                List.of(charge("ch_aaa111","or_aaa111","FAILED","CREDIT_CARD"),
                        charge("ch_bbb222","or_aaa111","PAID","PIX")));
        var r=service.inspect(50,10);
        assertEquals(1,r.candidatesWithMultipleProviderCharges());
        assertEquals(0,r.paymentMethodsComparable());
    }

    @Test void invalidPaginationFailsBeforeQueryingAnyProvider() {
        for(var pair:new int[][]{{0,5},{51,5},{50,0},{50,11}}) {
            var e=assertThrows(StoneChargeException.class,
                    ()->service.inspect(pair[0],pair[1]));
            assertEquals("INVALID_CORRELATION_PAGINATION",e.code());
        }
        verifyNoInteractions(shop);
        verify(stone,never()).correlationOrders(anyInt(),anyInt());
    }

    @Test void incompletePagingDoesNotPromoteCandidateToVerifiedOrderLink() {
        when(shop.orderCrossProviderCandidates(1,50)).thenReturn(new NuvemshopPage(
                List.of(shopRow("9001","158","paid","100.00","pix")),1,50,111L,true));
        when(stone.correlationOrders(1,50)).thenReturn(new StoneHistoryClient.Page<>(
                List.of(order("or_aaa111","158","PAID",10000)),181L,true,true));
        when(stone.charges(1,50)).thenReturn(new StoneHistoryClient.Page<>(
                List.of(charge("ch_aaa111","or_aaa111","PAID","PIX")),181L,true,true));
        var r=service.inspect(50,1);
        assertEquals(1,r.uniqueOneToOneCandidates());
        assertFalse(r.providerOrders().paginationReconciled());
        assertFalse(r.providerCharges().paginationReconciled());
        assertFalse(r.nuvemshopOrders().paginationReconciled());
        assertFalse(r.ordersMatchedToNuvemshop());
        assertTrue(r.warnings().contains("INCOMPLETE_OR_UNSTABLE_PAGINATION"));
        verify(shop,never()).orderCrossProviderCandidates(2,50);
        verify(stone,never()).correlationOrders(2,50);
    }
}
