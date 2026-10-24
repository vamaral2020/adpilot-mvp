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
    @Test void auditClassifiesValueDirectionStatusMethodGatewayAndMissingDatesWithoutDetails() {
        var otherGateway=shopRow("9001","158","paid","101.50","credit_card");
        otherGateway.put("gateway","nuvempago");
        otherGateway.put("gateway_name","Nuvem Pago");
        otherGateway.put("created_at",null);
        var stoneGateway=shopRow("9002","159","paid","80.00","pix");
        stoneGateway.put("gateway","stone");
        stoneGateway.put("gateway_name","Stone");
        fixtures(List.of(otherGateway,stoneGateway),
                List.of(new StoneHistoryClient.CrossReferenceOrder("or_aaa111",
                                "158","FAILED","BRL",10000L,null,"UNPARSEABLE"),
                        order("or_bbb222","159","PAID",10000)),
                List.of(charge("ch_aaa111","or_aaa111","FAILED","PIX"),
                        charge("ch_bbb222","or_bbb222","PAID","PIX")));
        var report=service.inspect(50,10);
        var audit=report.audit();
        assertEquals(2,report.uniqueOneToOneCandidates());
        assertEquals(2,report.amountsDifferent());
        assertEquals(Map.of(
                "NUVEMSHOP_GREATER_OVER_1_UP_TO_10_BRL",1,
                "PAGARME_GREATER_OVER_10_BRL",1),
                audit.grossDifferenceDirectionAndMagnitude());
        assertEquals(1,audit.grossDifferencesAlsoPaymentStateMismatch());
        assertEquals(1,audit.grossDifferencesAlsoMethodMismatch());
        assertEquals(1,audit.grossDifferencesWithOtherGatewayHint());
        assertEquals(Map.of("UNPARSEABLE",1,"PARSED",1),
                audit.candidateProviderCreatedAtFields());
        assertEquals(Map.of("ABSENT_OR_NULL",1,"PARSED",1),
                audit.candidateNuvemshopCreatedAtFields());
        assertEquals(Map.of("OTHER_REPORTED",1,"STONE_OR_PAGARME_REPORTED",1),
                audit.candidateGatewayHints());
        assertEquals(0,audit.duplicateNuvemshopTargetGroups());
        assertTrue(report.warnings().contains("ORDER_CREATION_TIMESTAMPS_INCOMPLETE_OR_UNPARSEABLE"));
        assertTrue(report.warnings().contains("CANDIDATE_NUVEMSHOP_GATEWAY_MAY_DIFFER_FROM_STONE"));
        assertFalse(report.financiallyVerified());
        assertFalse(report.paymentSettlementVerified());
        String safe=report.toString();
        assertFalse(safe.contains("9001"));
        assertFalse(safe.contains("or_aaa111"));
        assertFalse(safe.contains("101.50"));
        assertFalse(safe.contains("nuvempago"));
    }

    @Test void unsupportedExternalReferenceFormatsAreAggregatedNeverNormalized() {
        fixtures(List.of(shopRow("9001","158","paid","100.00","pix")),
                List.of(order("or_aaa111","VEST-158","PAID",10000),
                        order("or_bbb222","158/2026","PAID",10000),
                        order("or_ccc333","unknown","PAID",10000)),List.of());
        var result=service.inspect(50,10);
        assertEquals(3,result.providerCodesUnsupportedForExactMatch());
        assertEquals(0,result.uniqueOneToOneCandidates());
        assertEquals(Map.of("MIXED_ASCII_LETTERS_AND_DIGITS",1,
                "DIGITS_WITH_NONCANONICAL_CHARACTERS",1,
                "OTHER_NONNUMERIC_FORMAT",1),
                result.audit().unsupportedCodeFormats());
        assertFalse(result.toString().contains("VEST-158"));
        assertFalse(result.toString().contains("158/2026"));
        assertFalse(result.toString().contains("unknown"));
    }

    @Test void repeatedProviderCodesCountOneDuplicateTargetGroupWithoutTreatingItAsMatch() {
        fixtures(List.of(shopRow("9001","158","paid","100.00","pix")),
                List.of(order("or_aaa111","158","PAID",10000),
                        order("or_bbb222","158","FAILED",10000)),List.of());
        var result=service.inspect(50,10);
        assertEquals(2,result.providerOrdersWithDuplicateTarget());
        assertEquals(1,result.audit().duplicateNuvemshopTargetGroups());
        assertEquals(0,result.uniqueOneToOneCandidates());
        assertTrue(result.audit().grossDifferenceDirectionAndMagnitude().isEmpty());
    }

    @Test void compactNuvemshopUtcOffsetRestoresComparisonAndPreservesTimezones() {
        var shopRow=shopRow("9001","158","paid","95.00","credit_card");
        shopRow.put("created_at","2026-10-20T09:00:00-0300");
        fixtures(List.of(shopRow),
                List.of(order("or_aaa111","158","PAID",9500L)),
                List.of(charge("ch_aaa111","or_aaa111","PAID","CREDIT_CARD")));
        var report=service.inspect(50,10);
        assertEquals(1,report.creationTimesComparable());
        assertEquals(1,report.creationTimesWithin48Hours());
        assertEquals(Map.of("PARSED",1),report.audit().candidateNuvemshopCreatedAtFields());
        assertEquals(Map.of("ISO_OFFSET_COMPACT_HHMM",1),
                report.investigation().candidateNuvemshopTimestampFormats());
        assertFalse(report.financiallyVerified());
        assertFalse(report.ordersMatchedToNuvemshop());
        assertFalse(report.toString().contains("09:00:00"));
    }

    @Test void mismatchedGrossAmountsProduceOnlyArithmeticHypotheses() {
        var first=shopRow("9001","158","paid","95.00","credit_card");
        first.put("created_at","2026-10-20T09:00:00-0300");
        first.put("subtotal","100.00");
        first.put("discount","5.00");
        first.put("discount_gateway","5.00");
        first.put("shipping_cost_customer","0.00");
        var second=shopRow("9002","159","paid","110.00","pix");
        second.put("subtotal","100.00");
        second.put("discount","0.00");
        second.put("shipping_cost_customer","10.00");
        fixtures(List.of(first,second),
                List.of(order("or_aaa111","158","PAID",10000L),
                        order("or_bbb222","159","PAID",12000L)),
                List.of(charge("ch_aaa111","or_aaa111","PAID","CREDIT_CARD"),
                        charge("ch_bbb222","or_bbb222","PAID","PIX")));
        var result=service.inspect(50,10);
        var diag=result.investigation();
        assertEquals(2,result.amountsDifferent());
        assertEquals(Map.of("SUBTOTAL_PRESENT",2,"DISCOUNT_PRESENT",2,
                "SHIPPING_COST_PRESENT",2,"GATEWAY_DISCOUNT_PRESENT",1),
                diag.mismatchedGrossComponentAvailability());
        assertEquals(1,diag.grossMismatchesWhereProviderEqualsSubtotal());
        assertEquals(1,diag.grossMismatchesWhereDifferenceEqualsDiscount());
        assertEquals(1,diag.grossMismatchesWhereDifferenceEqualsCustomerShipping());
        assertEquals(2,diag.grossMismatchesWithConsistentShopSubtotalPlusShippingMinusDiscount());
        assertFalse(result.financiallyVerified());
        assertFalse(result.paymentSettlementVerified());
        assertFalse(result.ordersMatchedToCampaigns());
        assertFalse(result.toString().contains("95.00"));
        assertFalse(result.toString().contains("or_aaa111"));
    }

    @Test void missingComponentsAndTimestampWithoutOffsetNeverCreateFinancialProof() {
        var row=shopRow("9001","158","paid","90.00","pix");
        row.put("created_at","2026-10-20T12:00:00");
        fixtures(List.of(row),List.of(order("or_aaa111","158","PAID",10000)),
                List.of(charge("ch_aaa111","or_aaa111","PAID","PIX")));
        var result=service.inspect(50,10);
        assertEquals(0,result.creationTimesComparable());
        assertEquals(Map.of("LOCAL_WITHOUT_OFFSET",1),
                result.investigation().candidateNuvemshopTimestampFormats());
        assertEquals(0,result.investigation().grossMismatchesWhereDifferenceEqualsDiscount());
        assertTrue(result.investigation().mismatchedGrossComponentAvailability().isEmpty());
    }

    @Test void expanded404FallsBackToPreviousProjectionWithoutManufacturingComponents() {
        fixtures(List.of(shopRow("9001","158","paid","95.00","credit_card")),
                List.of(order("or_aaa111","158","PAID",10000)),
                List.of(charge("ch_aaa111","or_aaa111","PAID","CREDIT_CARD")));
        doThrow(new br.com.adpilot.integrations.nuvemshop.NuvemshopException(
                org.springframework.http.HttpStatus.BAD_GATEWAY,
                "NUVEMSHOP_UPSTREAM_ERROR","sanitized upstream",404))
                .when(shop).orderCrossProviderCandidates(1,50);
        when(shop.orderCrossProviderCandidatesBaseline(1,50)).thenReturn(
                new NuvemshopPage(
                        List.of(shopRow("9001","158","paid","95.00","credit_card")),
                        1,50,1L,false));
        var report=service.inspect(50,10);
        assertEquals("BASELINE_AFTER_EXPANDED_404",report.investigation().nuvemshopReadMode());
        assertEquals(1,report.uniqueOneToOneCandidates());
        assertEquals(1,report.amountsDifferent());
        assertTrue(report.investigation().mismatchedGrossComponentAvailability().isEmpty());
        assertEquals(0,report.investigation().grossMismatchesWhereDifferenceEqualsDiscount());
        assertTrue(report.warnings().contains(
                "NUVEMSHOP_EXPANDED_ORDER_FIELDS_RETURNED_404_BASELINE_SUCCEEDED"));
        assertTrue(report.warnings().contains(
                "NUVEMSHOP_FINANCIAL_COMPONENTS_NOT_REQUESTED_AFTER_FALLBACK"));
        assertFalse(report.financiallyVerified());
        assertFalse(report.paymentSettlementVerified());
        verify(shop).orderCrossProviderCandidatesBaseline(1,50);
        verify(shop,never()).orderCrossProviderCandidates(2,50);
    }

    @Test void fallbackPageTwoKeepsOriginalProjectionAcrossEntireRun() {
        when(shop.orderCrossProviderCandidates(1,50)).thenThrow(
                new br.com.adpilot.integrations.nuvemshop.NuvemshopException(
                        org.springframework.http.HttpStatus.BAD_GATEWAY,
                        "NUVEMSHOP_UPSTREAM_ERROR","sanitized upstream",404));
        when(shop.orderCrossProviderCandidatesBaseline(1,50)).thenReturn(
                new NuvemshopPage(
                        List.of(shopRow("9001","158","paid","95.00","credit_card")),
                        1,50,2L,true));
        when(shop.orderCrossProviderCandidatesBaseline(2,50)).thenReturn(
                new NuvemshopPage(
                        List.of(shopRow("9002","159","paid","100.00","pix")),
                        2,50,2L,false));
        when(stone.correlationOrders(1,50)).thenReturn(
                new StoneHistoryClient.Page<>(List.of(
                        order("or_aaa111","158","PAID",9500),
                        order("or_bbb222","159","PAID",10000)),2L,false,true));
        when(stone.charges(1,50)).thenReturn(
                new StoneHistoryClient.Page<>(List.of(
                        charge("ch_aaa111","or_aaa111","PAID","CREDIT_CARD"),
                        charge("ch_bbb222","or_bbb222","PAID","PIX")),2L,false,true));
        var report=service.inspect(50,10);
        assertEquals(2,report.uniqueOneToOneCandidates());
        assertTrue(report.nuvemshopOrders().paginationReconciled());
        assertEquals("BASELINE_AFTER_EXPANDED_404",report.investigation().nuvemshopReadMode());
        verify(shop,never()).orderCrossProviderCandidates(2,50);
        verify(shop).orderCrossProviderCandidatesBaseline(2,50);
    }

    @Test void unrelatedHttpErrorsNeverTriggerFallback() {
        for(int code:new int[]{400,401,403,429,500}) {
            reset(shop);
            when(shop.orderCrossProviderCandidates(1,50)).thenThrow(
                    new br.com.adpilot.integrations.nuvemshop.NuvemshopException(
                            org.springframework.http.HttpStatus.BAD_GATEWAY,
                            "NUVEMSHOP_UPSTREAM_ERROR","upstream",code));
            var error=assertThrows(br.com.adpilot.integrations.nuvemshop.NuvemshopException.class,
                    ()->service.inspect(50,10));
            assertEquals(code,error.upstreamStatus());
            verify(shop,never()).orderCrossProviderCandidatesBaseline(anyInt(),anyInt());
            verifyNoInteractions(stone);
        }
    }

    @Test void double404IsReportedAsFailureRatherThanSilentlyEmptyReport() {
        when(shop.orderCrossProviderCandidates(1,50)).thenThrow(
                new br.com.adpilot.integrations.nuvemshop.NuvemshopException(
                        org.springframework.http.HttpStatus.BAD_GATEWAY,"NUVEMSHOP_UPSTREAM_ERROR",
                        "expanded projection failed",404));
        when(shop.orderCrossProviderCandidatesBaseline(1,50)).thenThrow(
                new br.com.adpilot.integrations.nuvemshop.NuvemshopException(
                        org.springframework.http.HttpStatus.BAD_GATEWAY,"NUVEMSHOP_UPSTREAM_ERROR",
                        "baseline projection failed",404));
        var error=assertThrows(br.com.adpilot.integrations.nuvemshop.NuvemshopException.class,
                ()->service.inspect(50,10));
        assertEquals(404,error.upstreamStatus());
        verifyNoInteractions(stone);
    }

}
