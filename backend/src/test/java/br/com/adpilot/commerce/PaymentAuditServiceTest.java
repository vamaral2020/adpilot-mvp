package br.com.adpilot.commerce;

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

class PaymentAuditServiceTest {
    private NuvemshopClient shop;
    private PaymentAuditService service;

    @BeforeEach
    void setup() {
        shop = mock(NuvemshopClient.class);
        service = new PaymentAuditService(shop);
    }

    private static Map<String, Object> order(long id, String payment, String paidAt,
            String createdAt, String gateway, String storefront, Object appId, String status) {
        var row = new HashMap<String, Object>();
        row.put("id", id);
        row.put("payment_status", payment);
        row.put("paid_at", paidAt);
        row.put("created_at", createdAt);
        row.put("gateway", gateway);
        row.put("storefront", storefront);
        row.put("app_id", appId);
        row.put("status", status);
        row.put("customer", Map.of("email", "never-disclose@example.com"));
        return row;
    }

    @SafeVarargs
    private static NuvemshopPage page(int page, Long total, boolean hasNext,
                                     Map<String, Object>... rows) {
        return new NuvemshopPage(List.of(rows), page, 100, total, hasNext);
    }

    private static long count(List<PaymentAuditResponse.Bucket> buckets, String name) {
        return buckets.stream().filter(x -> x.label().equals(name))
                .mapToLong(PaymentAuditResponse.Bucket::count).sum();
    }

    @Test void auditsAllPagesAndDiagnosesUndatedPaymentsWithoutRevealingOrderData() {
        when(shop.paymentAuditOrders(1, 100)).thenReturn(page(1, 5L, true,
                order(11, "paid", null, "2026-08-31T23:10:00-0300",
                        "offline", "api", 1001, "open"),
                order(12, "paid", "", "2026-10-05T12:00:00Z",
                        "not-provided", "store", null, "closed"),
                order(13, "pending", null, "2026-10-08T10:00:00Z",
                        "offline", "store", null, "open")));
        when(shop.paymentAuditOrders(2, 100)).thenReturn(page(2, 5L, false,
                order(14, "paid", "INVALID", "bad date",
                        "never-disclose@example.com", "arbitrary-source", null, "secret-status"),
                order(15, "paid", "2026-10-31T23:59:00-0300", "2026-10-15T12:00:00Z",
                        "offline", "store", null, "open")));

        var data = service.audit(100, 20);
        assertTrue(data.complete());
        assertEquals(2, data.pagesFetched());
        assertEquals(5, data.recordsFetched());
        assertEquals(5, data.uniqueOrders());
        assertEquals(5L, data.upstreamTotalCount());
        assertEquals(4, data.paidOrders());
        assertEquals(1, data.paidWithValidDate());
        assertEquals(2, data.paidWithoutDate());
        assertEquals(1, data.paidWithInvalidDate());
        assertEquals(1, count(data.paymentStatuses(), "pending"));
        assertEquals(4, count(data.paymentStatuses(), "paid"));
        assertEquals(1, count(data.undatedByCreationMonth(), "2026-09"));
        assertEquals(1, count(data.undatedByCreationMonth(), "2026-10"));
        assertEquals(1, count(data.undatedByCreationMonth(), "unknown"));
        assertEquals(1, count(data.undatedByGateway(), "offline"));
        assertEquals(1, count(data.undatedByGateway(), "not-provided"));
        assertEquals(1, count(data.undatedByGateway(), "other"));
        assertEquals(1, count(data.undatedByStorefront(), "api"));
        assertEquals(1, count(data.undatedByStorefront(), "store"));
        assertEquals(1, count(data.undatedByStorefront(), "other"));
        assertEquals(1, count(data.undatedByAppAssociation(), "has_app_id"));
        assertEquals(2, count(data.undatedByAppAssociation(), "no_app_id"));
        assertEquals(1, count(data.undatedByOrderStatus(), "open"));
        assertEquals(1, count(data.undatedByOrderStatus(), "closed"));
        assertEquals(1, count(data.undatedByOrderStatus(), "other"));
        assertEquals(1, count(data.datedPaymentsByMonth(), "2026-11"));
        assertTrue(data.warnings().contains("PAID_AT_MISSING"));
        assertTrue(data.warnings().contains("PAID_AT_INVALID"));
        assertTrue(data.warnings().contains("CREATED_AT_IS_NOT_PAYMENT_DATE"));
        String serialized = data.toString();
        assertFalse(serialized.contains("never-disclose@example.com"));
        assertFalse(serialized.contains("1001"));
        assertFalse(serialized.contains("secret-status"));
        verify(shop).paymentAuditOrders(1, 100);
        verify(shop).paymentAuditOrders(2, 100);
        verifyNoMoreInteractions(shop);
    }

    @Test void repeatsAndChangingPageTotalsRemainMarkedIncomplete() {
        when(shop.paymentAuditOrders(1, 100)).thenReturn(page(1, 3L, true,
                order(11, "paid", null, "2026-10-01T12:00:00Z", null, null, null, "open")));
        when(shop.paymentAuditOrders(2, 100)).thenReturn(page(2, 3L, false,
                order(11, "paid", null, "2026-10-01T12:00:00Z", null, null, null, "open"),
                order(12, "paid", "2026-10-01T12:00:00Z",
                        "2026-10-01T12:00:00Z", null, null, null, "open")));
        var data = service.audit(100, 20);
        assertFalse(data.complete());
        assertEquals(3, data.recordsFetched());
        assertEquals(2, data.uniqueOrders());
        assertEquals(1, data.duplicateRecords());
        assertEquals(2, data.paidOrders());
        assertEquals(1, data.paidWithoutDate());
        assertTrue(data.warnings().contains("DUPLICATE_IDS_IGNORED"));
        assertTrue(data.warnings().contains("PAGINATION_INCOMPLETE"));
    }

    @Test void pageCapExplicitlyReportsPartialAuditWithoutInventingMissingData() {
        when(shop.paymentAuditOrders(1, 100)).thenReturn(page(1, 102L, true,
                order(11, "paid", null, "2026-10-01T12:00:00Z", "offline", "store", null, "open")));
        var data = service.audit(100, 1);
        assertFalse(data.complete());
        assertEquals(1, data.pagesFetched());
        assertEquals(1, data.paidOrders());
        assertTrue(data.warnings().contains("PAGINATION_INCOMPLETE"));
        verify(shop, never()).paymentAuditOrders(2, 100);
    }

    @Test void noOrdersIsCompleteIfUpstreamIsEmpty() {
        when(shop.paymentAuditOrders(1, 100)).thenReturn(new NuvemshopPage(List.of(), 1, 100, 0L, false));
        var data = service.audit(100, 20);
        assertTrue(data.complete());
        assertEquals(0, data.paidOrders());
        assertEquals(0, data.paidWithoutDate());
        assertTrue(data.paymentStatuses().isEmpty());
    }

    @Test void emptyPageWithContinuationIsRejected() {
        when(shop.paymentAuditOrders(1, 100)).thenReturn(new NuvemshopPage(List.of(), 1, 100, 99L, true));
        var error = assertThrows(NuvemshopException.class, () -> service.audit(100, 20));
        assertEquals("NUVEMSHOP_INVALID_PAGINATION", error.code());
    }

    @Test void missingOrMalformedOrderIdIsRejectedWithoutEchoingIt() {
        var invalid = new HashMap<String, Object>();
        invalid.put("id", "private-email@example.com");
        when(shop.paymentAuditOrders(1, 100)).thenReturn(
                new NuvemshopPage(List.of(invalid), 1, 100, 1L, false));
        var error = assertThrows(NuvemshopException.class, () -> service.audit(100, 20));
        assertEquals("NUVEMSHOP_INVALID_ORDER_DATA", error.code());
        assertFalse(error.getMessage().contains("private-email@example.com"));
    }

    @Test void unknownProviderStatusesAndGatewaysAreNeverEchoed() {
        when(shop.paymentAuditOrders(1, 100)).thenReturn(page(1, 2L, false,
                order(11, "paid", null, "2026-10-18T12:00:00Z",
                        "customer-123@example.com", "secret-from-upstream", null, "private-state"),
                order(12, "sensitive-upstream-string", null,
                        "2026-10-18T12:00:00Z", "offline", "store", null, "open")));
        var data = service.audit(100, 20);
        assertEquals(1, count(data.paymentStatuses(), "other"));
        assertEquals(1, count(data.undatedByGateway(), "other"));
        assertEquals(1, count(data.undatedByStorefront(), "other"));
        String visible = data.toString();
        assertFalse(visible.contains("customer-123"));
        assertFalse(visible.contains("secret-from-upstream"));
        assertFalse(visible.contains("sensitive-upstream-string"));
        assertFalse(visible.contains("private-state"));
    }

    @Test void invalidLimitsRejectedWithoutNetworkCalls() {
        for (int[] values : new int[][] {{0, 20}, {101, 20}, {100, 0}, {100, 101}}) {
            var e = assertThrows(NuvemshopException.class,
                    () -> service.audit(values[0], values[1]));
            assertEquals("INVALID_PAGINATION", e.code());
        }
        verifyNoInteractions(shop);
    }

    @Test void preservesUpstreamRateLimitAndPermissionErrors() {
        when(shop.paymentAuditOrders(1, 100))
                .thenThrow(new NuvemshopException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                        "NUVEMSHOP_RATE_LIMIT", "Limite atingido", 429));
        var e = assertThrows(NuvemshopException.class, () -> service.audit(100, 20));
        assertEquals(429, e.upstreamStatus());
        assertEquals("NUVEMSHOP_RATE_LIMIT", e.code());
    }
}
