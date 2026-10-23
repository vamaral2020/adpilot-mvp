package br.com.adpilot.analytics;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import br.com.adpilot.analytics.AnalyticsHistoryModels.*;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class CommercialReconciliationServiceTest {
    private static final LocalDate SINCE=LocalDate.of(2026,10,20);
    private static final LocalDate UNTIL=LocalDate.of(2026,10,22);
    private static final Instant UPDATED=Instant.parse("2026-10-23T10:00:00Z");
    private final AnalyticsHistoryService history=mock(AnalyticsHistoryService.class);
    private final CommercialReconciliationService service=new CommercialReconciliationService(
            history,Clock.fixed(Instant.parse("2026-10-23T12:00:00Z"),ZoneOffset.UTC));

    private static BigDecimal bd(String v) { return new BigDecimal(v); }
    private static MetaDay meta(int offset,String currency,String spend,String revenue) {
        return new MetaDay(SINCE.plusDays(offset),currency,bd(spend),300,20,
                bd("1"),bd(revenue));
    }
    private static StoreDay store(int offset,String currency,long count,String revenue) {
        return new StoreDay(SINCE.plusDays(offset),currency,count,bd(revenue));
    }
    private static Coverage coverage(String status,String account,List<LocalDate> gaps,long windows) {
        return new Coverage(new MetaCoverage(status,account,3,3-gaps.size(),
                3-gaps.size(),0,gaps,List.of(),UPDATED),
                new StoreCoverage("UPDATED_AT_ONLY_NOT_PAID_DATE",windows,UPDATED));
    }
    private static List<SourceState> states(String shopStatus) {
        return List.of(new SourceState("META_ADS","INSIGHTS_DAILY","SUCCEEDED",UPDATED,UPDATED),
                new SourceState("NUVEMSHOP","ORDERS",shopStatus,UPDATED,UPDATED));
    }
    private static History report(List<MetaDay> meta,List<StoreDay> store,
            Coverage coverage,List<SourceState> sources) {
        return new History(SINCE,UNTIL,meta,store,List.of(
                new CampaignResult("123","456","Vendas","BRL",bd("100"),300,20,
                        bd("2"),bd("200"))),sources,false,
                List.of("HISTORICAL_COVERAGE_NOT_VERIFIED"),coverage);
    }
    private void given(History value) {
        when(history.history(SINCE,UNTIL)).thenReturn(value);
    }

    @Test void correctRatiosAreOnlyIndicativeAndNeverClaimMatchedOrders() {
        given(report(List.of(meta(0,"BRL","40","60"),meta(1,"BRL","30","90"),
                        meta(2,"BRL","30","50")),
                List.of(store(1,"BRL",2,"150")),
                coverage("WINDOWS_OBSERVED","123",List.of(),2),states("SUCCEEDED")));
        var result=service.compare(SINCE,UNTIL);

        assertEquals("INDICATIVE_ONLY",result.comparisonStatus());
        assertFalse(result.financiallyVerified());
        assertFalse(result.ordersMatchedToCampaigns());
        assertEquals("BRL",result.meta().currency());
        assertEquals(bd("100"),result.meta().spend());
        assertEquals(bd("200"),result.meta().attributedRevenue());
        assertEquals(bd("3"),result.meta().attributedPurchases());
        assertEquals(2,result.store().identifiedPaidOrders());
        assertEquals(bd("150"),result.store().identifiedPaidRevenue());
        assertEquals(bd("1.5000"),result.indicativeMer());
        assertEquals(bd("2.0000"),result.indicativeAttributedRoas());
        assertEquals(3,result.daily().size());
        assertEquals(0,result.daily().getFirst().store().size());
        assertEquals(1,result.daily().get(1).store().size());
        assertTrue(result.warnings().contains("MER_USES_IDENTIFIED_SNAPSHOTS_ONLY_NOT_AUDITED"));
        assertTrue(result.warnings().contains("NOT_ORDER_TO_CAMPAIGN_RECONCILIATION"));
        verify(history).history(SINCE,UNTIL);
        verifyNoMoreInteractions(history);
    }

    @Test void missingStoreRowsDoNotBecomeZeroOrdersOrZeroRevenue() {
        given(report(List.of(meta(0,"BRL","40","60")),List.of(),
                coverage("WINDOWS_OBSERVED","123",List.of(),2),states("SUCCEEDED")));
        var result=service.compare(SINCE,UNTIL);
        assertEquals("INSUFFICIENT_OBSERVATIONS",result.comparisonStatus());
        assertNull(result.store().identifiedPaidOrders());
        assertNull(result.store().identifiedPaidRevenue());
        assertNull(result.indicativeMer());
        assertFalse(result.financiallyVerified());
    }

    @Test void mixedCurrenciesAreNotAddedOrConverted() {
        given(report(List.of(meta(0,"BRL","50","60"),meta(1,"USD","50","70")),
                List.of(store(0,"BRL",2,"150")),
                coverage("WINDOWS_OBSERVED","123",List.of(),1),states("SUCCEEDED")));
        var result=service.compare(SINCE,UNTIL);
        assertEquals("AMBIGUOUS_CURRENCY",result.comparisonStatus());
        assertNull(result.meta().spend());
        assertNull(result.indicativeMer());
        assertTrue(result.warnings().contains("CROSS_CURRENCY_COMPARISON_BLOCKED"));
    }

    @Test void differentCurrenciesBlockRatiosAndDoNotPretendToConvert() {
        given(report(List.of(meta(0,"USD","100","200")),
                List.of(store(0,"BRL",1,"150")),
                coverage("WINDOWS_OBSERVED","123",List.of(),1),states("SUCCEEDED")));
        var result=service.compare(SINCE,UNTIL);
        assertEquals("CURRENCY_MISMATCH",result.comparisonStatus());
        assertNull(result.indicativeMer());
        assertEquals("USD",result.meta().currency());
        assertEquals("BRL",result.store().currency());
    }

    @Test void partialMetaWindowsBlockAllCrossSourceRatios() {
        given(report(List.of(meta(0,"BRL","100","200")),
                List.of(store(0,"BRL",1,"150")),
                coverage("PARTIAL_WINDOWS","123",List.of(UNTIL),1),states("SUCCEEDED")));
        var result=service.compare(SINCE,UNTIL);
        assertEquals("META_COVERAGE_UNVERIFIED",result.comparisonStatus());
        assertNull(result.indicativeMer());
        assertNull(result.indicativeAttributedRoas());
    }

    @Test void failedLastStoreSyncDoesNotLookLikeReliableFinancialEvidence() {
        given(report(List.of(meta(0,"BRL","100","200")),
                List.of(store(0,"BRL",1,"150")),
                coverage("WINDOWS_OBSERVED","123",List.of(),1),states("FAILED")));
        var result=service.compare(SINCE,UNTIL);
        assertEquals("SYNC_EVIDENCE_INCOMPLETE",result.comparisonStatus());
        assertNull(result.indicativeMer());
        assertTrue(result.warnings().contains("LAST_PROVIDER_SYNC_NOT_SUCCESSFUL"));
    }

    @Test void currentDayCannotShowIndicativeMerEvenWithOldReceipts() {
        var now=LocalDate.of(2026,10,23);
        var todayReport=new History(SINCE,now,
                List.of(meta(0,"BRL","100","200")),List.of(store(0,"BRL",1,"150")),
                List.of(new CampaignResult("123","456","Vendas","BRL",bd("100"),300,20,
                        bd("1"),bd("200"))),states("SUCCEEDED"),false,List.of(),
                coverage("WINDOWS_OBSERVED","123",List.of(),1));
        when(history.history(SINCE,now)).thenReturn(todayReport);
        var result=service.compare(SINCE,now);
        assertNull(result.indicativeMer());
        assertTrue(result.warnings().contains("CURRENT_UTC_DAY_INCOMPLETE"));
    }

    @Test void historicalRangeValidationIsPreserved() {
        when(history.history(UNTIL,SINCE)).thenThrow(
                new ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST));
        assertEquals(400,assertThrows(ResponseStatusException.class,
                ()->service.compare(UNTIL,SINCE)).getStatusCode().value());
    }

    @Test void accountMismatchNeverProducesObservedMer() {
        given(report(List.of(meta(0,"BRL","100","200")),
                List.of(store(0,"BRL",1,"150")),
                coverage("WINDOWS_OBSERVED","999",List.of(),1),states("SUCCEEDED")));
        var result=service.compare(SINCE,UNTIL);
        assertEquals("META_COVERAGE_UNVERIFIED",result.comparisonStatus());
        assertNull(result.indicativeMer());
        assertTrue(result.warnings().contains("META_ACCOUNT_CONSISTENCY_NOT_VERIFIED"));
    }
}
