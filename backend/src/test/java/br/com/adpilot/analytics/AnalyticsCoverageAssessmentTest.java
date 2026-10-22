package br.com.adpilot.analytics;

import static org.junit.jupiter.api.Assertions.*;
import br.com.adpilot.analytics.AnalyticsHistoryModels.MetaDay;
import br.com.adpilot.analytics.AnalyticsHistoryModels.MetaWindow;
import br.com.adpilot.analytics.AnalyticsHistoryModels.ShopWindowSummary;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class AnalyticsCoverageAssessmentTest {
    private final AnalyticsCoverageAssessment assessor=new AnalyticsCoverageAssessment();
    private static final LocalDate START=LocalDate.of(2026,10,14);
    private static final Instant DONE=Instant.parse("2026-10-21T14:00:00Z");
    private static MetaWindow window(int first,int last) {
        return new MetaWindow("123",START.plusDays(first),START.plusDays(last),DONE);
    }
    private static MetaDay row(int offset) {
        return new MetaDay(START.plusDays(offset),"BRL",new BigDecimal("10"),
                100,10,BigDecimal.ZERO,BigDecimal.ZERO);
    }

    @Test void overlappingSuccessfulWindowsAreUnionedWithoutDoubleCounting() {
        var coverage=assessor.assess(START,START.plusDays(6),
                List.of(row(1),row(3)),List.of(window(0,3),window(2,5)),
                new ShopWindowSummary(2,DONE));
        var meta=coverage.meta();
        assertEquals("PARTIAL_WINDOWS",meta.status());
        assertEquals("123",meta.accountId());
        assertEquals(7,meta.requestedDays());
        assertEquals(6,meta.observedDays());
        assertEquals(2,meta.datesWithRows());
        assertEquals(4,meta.datesWithoutRows());
        assertEquals(List.of(START.plusDays(6)),meta.unobservedDates());
        assertTrue(meta.observedWithoutRows().contains(START));
        assertEquals(2,coverage.store().successfulUpdateWindows());
        assertEquals("UPDATED_AT_ONLY_NOT_PAID_DATE",coverage.store().status());
    }

    @Test void noMetaRowsInsideFetchedWindowMeansNoReturnedRowsNotZeroActivity() {
        var result=assessor.assess(START,START.plusDays(1),List.of(),
                List.of(window(0,1)),new ShopWindowSummary(0,null));
        assertEquals("WINDOWS_OBSERVED",result.meta().status());
        assertEquals(0,result.meta().datesWithRows());
        assertEquals(2,result.meta().datesWithoutRows());
        assertTrue(result.meta().unobservedDates().isEmpty());
        assertEquals(START,result.meta().observedWithoutRows().getFirst());
    }

    @Test void historicalRowsWithoutReceiptDoNotCountAsObserved() {
        var result=assessor.assess(START,START,List.of(row(0)),List.of(),null);
        assertEquals("NO_COLLECTION_RECEIPT",result.meta().status());
        assertEquals(0,result.meta().observedDays());
        assertEquals(1,result.meta().datesWithRows());
        assertEquals(List.of(START),result.meta().unobservedDates());
        assertNull(result.meta().lastSuccess());
    }

    @Test void multipleMetaAccountsMustNotBeCombinedToClaimFullCollection() {
        var a=window(0,2);
        var b=new MetaWindow("456",START.plusDays(3),START.plusDays(6),DONE);
        var result=assessor.assess(START,START.plusDays(6),List.of(row(0)),
                List.of(a,b),new ShopWindowSummary(0,null));
        assertEquals("MULTIPLE_ACCOUNTS",result.meta().status());
        assertNull(result.meta().accountId());
        assertEquals(0,result.meta().observedDays());
        assertEquals(7,result.meta().unobservedDates().size());
    }

    @Test void beyondRequestedBoundsDoesNotChangeDayCounts() {
        var result=assessor.assess(START,START.plusDays(2),
                List.of(row(2)),List.of(new MetaWindow("123",
                        START.minusDays(30),START.plusDays(30),DONE)),
                new ShopWindowSummary(1,DONE));
        assertEquals(3,result.meta().observedDays());
        assertEquals(2,result.meta().datesWithoutRows());
        assertEquals(3,result.meta().requestedDays());
        assertEquals("WINDOWS_OBSERVED",result.meta().status());
    }
}
