package br.com.adpilot.analytics;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import br.com.adpilot.integrations.nuvemshop.NuvemshopClient;
import br.com.adpilot.integrations.nuvemshop.NuvemshopException;
import br.com.adpilot.integrations.nuvemshop.NuvemshopPage;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OrderSnapshotDiagnosticServiceTest {
    NuvemshopClient shop;
    OrderSnapshotDiagnosticRepository repository;
    OrderSnapshotDiagnosticService service;

    @BeforeEach
    void setup() {
        shop = mock(NuvemshopClient.class);
        repository = mock(OrderSnapshotDiagnosticRepository.class);
        service = new OrderSnapshotDiagnosticService(shop, repository);
    }

    private static Map<String,Object> order(Object id, String status, Object paidAt) {
        var row = new HashMap<String,Object>();
        row.put("id",id);
        row.put("payment_status",status);
        row.put("paid_at",paidAt);
        // An upstream payload must never reach the report, even if present.
        row.put("customer",Map.of("email","hidden@example.com"));
        return row;
    }

    @SafeVarargs
    private static NuvemshopPage page(int index, Long total, boolean next,
            Map<String,Object>... rows) {
        return new NuvemshopPage(List.of(rows),index,100,total,next);
    }

    @Test void matchesOnlyWithinFetchedPagesWithoutImplyingFinancialVerification() {
        when(shop.paymentAuditOrders(1,100)).thenReturn(page(1,4L,true,
                order(11,"paid","2026-10-20T08:00:00-0300"),
                order(12,"pending",null)));
        when(shop.paymentAuditOrders(2,100)).thenReturn(page(2,4L,false,
                order(13,"paid",null),order(14,"paid","2026-10-20T15:00:00Z")));
        when(repository.find(List.of("11"))).thenReturn(Map.of("11",
                new OrderSnapshotDiagnosticService.Snapshot("11","paid",
                        Instant.parse("2026-10-20T11:00:00Z"))));
        when(repository.find(List.of("13","14"))).thenReturn(Map.of(
                "13",new OrderSnapshotDiagnosticService.Snapshot("13","paid",
                        Instant.parse("2026-10-20T12:00:00Z")),
                "14",new OrderSnapshotDiagnosticService.Snapshot("14","pending",null)));

        var result=service.inspect(100,20);
        assertTrue(result.upstreamPaginationReconciled());
        assertFalse(result.financiallyVerified());
        assertFalse(result.ordersMatchedToCampaigns());
        assertEquals(4,result.uniqueUpstreamOrderIds());
        assertEquals(3,result.paidOrdersChecked());
        assertEquals(3,result.paidOrderIdsFoundLocally());
        assertEquals(0,result.paidOrderIdsMissingLocally());
        assertEquals(1,result.locallyNotPaid());
        assertEquals(1,result.upstreamPaidAtMissing());
        assertEquals(1,result.localPaidAtMissing());
        assertEquals(1,result.paidAtEqual());
        assertEquals(0,result.paidAtDifferent());
        assertTrue(result.warnings().contains("UPSTREAM_LIST_PAID_AT_MISSING"));
        String safe=result.toString();
        assertFalse(safe.contains("hidden@example.com"));
        assertFalse(safe.contains("2026-10-20T11:00:00Z"));
        assertFalse(safe.contains("orderId"));
        verify(repository).find(List.of("11"));
        verify(repository).find(List.of("13","14"));
        verify(shop,times(2)).paymentAuditOrders(anyInt(),eq(100));
    }

    @Test void missingSnapshotsAndDifferingDatesAreReportedOnlyAsCounts() {
        when(shop.paymentAuditOrders(1,100)).thenReturn(page(1,3L,false,
                order(11,"paid","2026-10-20T11:00:00Z"),
                order(12,"paid","not-a-date"),
                order(13,"paid","2026-10-20T11:00:00Z")));
        when(repository.find(List.of("11","12","13"))).thenReturn(Map.of(
                "11",new OrderSnapshotDiagnosticService.Snapshot("11","paid",
                        Instant.parse("2026-10-21T11:00:00Z")),
                "12",new OrderSnapshotDiagnosticService.Snapshot("12","paid",null)));
        var result=service.inspect(100,20);
        assertEquals(1,result.paidOrderIdsMissingLocally());
        assertEquals(1,result.upstreamPaidAtInvalid());
        assertEquals(1,result.localPaidAtMissing());
        assertEquals(1,result.paidAtDifferent());
        assertTrue(result.warnings().contains("PAID_ORDER_IDS_MISSING_FROM_LOCAL_SNAPSHOTS"));
        assertTrue(result.warnings().contains("PAID_AT_DISAGREEMENT"));
        assertFalse(result.toString().contains("11:00:00Z"));
    }

    @Test void duplicatesAndPaginationCapNeverClaimCompleteUpstreamScan() {
        when(shop.paymentAuditOrders(1,100)).thenReturn(page(1,3L,true,
                order(11,"paid",null)));
        when(shop.paymentAuditOrders(2,100)).thenReturn(page(2,3L,false,
                order(11,"paid",null),order(12,"paid",null)));
        when(repository.find(List.of("11"))).thenReturn(Map.of());
        when(repository.find(List.of("12"))).thenReturn(Map.of());
        var duplicates=service.inspect(100,20);
        assertFalse(duplicates.upstreamPaginationReconciled());
        assertEquals(1,duplicates.duplicateUpstreamIds());
        assertEquals(2,duplicates.paidOrdersChecked());

        reset(shop,repository);
        when(shop.paymentAuditOrders(1,100)).thenReturn(page(1,200L,true,
                order(11,"paid",null)));
        when(repository.find(List.of("11"))).thenReturn(Map.of());
        var truncated=service.inspect(100,1);
        assertFalse(truncated.upstreamPaginationReconciled());
        assertEquals(1,truncated.pagesFetched());
        assertTrue(truncated.warnings().contains("UPSTREAM_PAGINATION_NOT_RECONCILED"));
        verify(shop,never()).paymentAuditOrders(2,100);
    }

    @Test void missingOrChangingUpstreamTotalsCannotClaimPaginationReconciled() {
        when(shop.paymentAuditOrders(1,100)).thenReturn(page(1,null,false,
                order(11,"pending",null)));
        var missing=service.inspect(100,1);
        assertFalse(missing.upstreamPaginationReconciled());

        reset(shop,repository);
        when(shop.paymentAuditOrders(1,100)).thenReturn(page(1,2L,true,
                order(11,"pending",null)));
        when(shop.paymentAuditOrders(2,100)).thenReturn(page(2,3L,false,
                order(12,"pending",null)));
        var changed=service.inspect(100,3);
        assertFalse(changed.upstreamPaginationReconciled());
        assertNull(changed.upstreamReportedTotal());
    }

    @Test void invalidIdentifiersAndPaginationAreRejectedWithoutLeakingSourceValue() {
        when(shop.paymentAuditOrders(1,100)).thenReturn(page(1,1L,false,
                order("private@example.com","paid",null)));
        var error=assertThrows(NuvemshopException.class,()->service.inspect(100,20));
        assertEquals("NUVEMSHOP_INVALID_ORDER_DATA",error.code());
        assertFalse(error.getMessage().contains("private@example.com"));
        verifyNoInteractions(repository);

        clearInvocations(shop,repository);
        for (int[] bad:new int[][]{{0,10},{101,10},{100,0},{100,21}}) {
            assertEquals("INVALID_PAGINATION",assertThrows(NuvemshopException.class,
                    ()->service.inspect(bad[0],bad[1])).code());
        }
        verifyNoInteractions(shop,repository);
    }

    @Test void emptyPageWithNextIsRejectedAndNoFalseZeroInferred() {
        when(shop.paymentAuditOrders(1,100))
                .thenReturn(new NuvemshopPage(List.of(),1,100,2L,true));
        assertEquals("NUVEMSHOP_INVALID_PAGINATION",
                assertThrows(NuvemshopException.class,()->service.inspect(100,20)).code());
        verifyNoInteractions(repository);
    }
}
