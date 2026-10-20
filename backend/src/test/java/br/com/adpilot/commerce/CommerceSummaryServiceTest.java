package br.com.adpilot.commerce;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import br.com.adpilot.integrations.meta.MetaAdsClient;
import br.com.adpilot.integrations.meta.MetaInsightsMetrics;
import br.com.adpilot.integrations.meta.MetaInsightsSummary;
import br.com.adpilot.integrations.meta.MetaInsightsSummaryService;
import br.com.adpilot.integrations.nuvemshop.NuvemshopClient;
import br.com.adpilot.integrations.nuvemshop.NuvemshopException;
import br.com.adpilot.integrations.nuvemshop.NuvemshopPage;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class CommerceSummaryServiceTest {
    private static final LocalDate SINCE = LocalDate.of(2026, 10, 17);
    private static final LocalDate UNTIL = LocalDate.of(2026, 10, 19);
    private NuvemshopClient shop;
    private MetaAdsClient metaClient;
    private MetaInsightsSummaryService metaService;
    private CommerceSummaryService service;

    @BeforeEach
    void prepare() {
        shop = mock(NuvemshopClient.class);
        metaClient = mock(MetaAdsClient.class);
        metaService = mock(MetaInsightsSummaryService.class);
        service = new CommerceSummaryService(shop, metaClient, metaService);
        when(metaClient.account()).thenReturn(Map.of("currency", "BRL"));
        meta(true, "100.00", "300.00");
    }

    private void meta(boolean complete, String spend, String attributedRevenue) {
        var metrics = new MetaInsightsMetrics(new BigDecimal(spend), 120, 20,
                new BigDecimal("16.6667"), new BigDecimal("5.0000"),
                BigDecimal.ONE, new BigDecimal(attributedRevenue),
                new BigDecimal(attributedRevenue).divide(new BigDecimal(spend), 4,
                        java.math.RoundingMode.HALF_UP));
        when(metaService.summary(eq(SINCE), eq(UNTIL), eq(100), eq(20)))
                .thenReturn(new MetaInsightsSummary(SINCE, UNTIL, complete, 1, 1,
                        complete ? null : "next", metrics, List.of()));
    }

    private static Map<String, Object> order(int id, String payment, String paidAt,
                                             String total, String currency) {
        var data = new java.util.HashMap<String, Object>();
        data.put("id", id);
        data.put("payment_status", payment);
        data.put("paid_at", paidAt);
        data.put("total", total);
        data.put("currency", currency);
        data.put("customer", Map.of("email", "do-not-expose@example.com"));
        return data;
    }

    private static NuvemshopPage page(int index, Long total, boolean next,
                                      Map<String, Object>... rows) {
        return new NuvemshopPage(List.of(rows), index, 100, total, next);
    }

    @Test void paginatesAndAggregatesByUtcPaidAtWithNoCustomerData() {
        when(shop.orders(1, 100)).thenReturn(page(1, 3L, true,
                order(11, "paid", "2026-10-17T23:30:00-0300", "200.00", "BRL"),
                order(12, "pending", null, "9999.00", "BRL")));
        when(shop.orders(2, 100)).thenReturn(page(2, 3L, false,
                order(11, "paid", "2026-10-17T23:30:00-0300", "200.00", "BRL"),
                order(13, "paid", "2026-10-19T12:00:00+0000", "100.00", "BRL")));

        var result = service.summary(SINCE, UNTIL, 100, 20);
        assertTrue(result.complete());
        assertTrue(result.ordersComplete());
        assertEquals(2, result.pagesFetched());
        assertEquals(4, result.ordersFetched());
        assertEquals(2L, result.paidOrders());
        assertEquals(new BigDecimal("300.00"), result.paidRevenue());
        assertEquals(new BigDecimal("150.00"), result.averageTicket());
        assertEquals("BRL", result.storeCurrency());
        assertEquals("BRL", result.metaCurrency());
        assertEquals(new BigDecimal("100.00"), result.mediaSpend());
        assertEquals(new BigDecimal("300.00"), result.metaAttributedRevenue());
        assertEquals(new BigDecimal("3.0000"), result.metaRoas());
        assertEquals(new BigDecimal("3.0000"), result.mer());
        assertEquals(List.of(0L, 1L, 1L),
                result.daily().stream().map(CommerceSummary.DailySales::paidOrders).toList());
        assertEquals(new BigDecimal("200.00"), result.daily().get(1).paidRevenue());
        assertEquals(new BigDecimal("100.00"), result.daily().get(2).paidRevenue());
        assertFalse(result.toString().contains("do-not-expose@example.com"));
        verify(shop).orders(1, 100);
        verify(shop).orders(2, 100);
        verify(shop, never()).orders(3, 100);
    }

    @Test void excludesPaidOrdersOutsidePeriodWithoutUsingCreatedAt() {
        when(shop.orders(1, 100)).thenReturn(page(1, 2L, false,
                order(1, "paid", "2026-10-16T22:00:00Z", "800.00", "BRL"),
                order(2, "paid", "2026-10-19T21:00:00-0300", "700.00", "BRL")));
        var result = service.summary(SINCE, UNTIL, 100, 20);
        assertTrue(result.ordersComplete());
        assertEquals(0L, result.paidOrders());
        assertEquals(new BigDecimal("0.00"), result.paidRevenue());
        assertNull(result.averageTicket());
        assertNull(result.mer()); // No reliable store currency in matching paid orders.
    }

    @Test void noSpendDoesNotInventMer() {
        when(shop.orders(1, 100)).thenReturn(page(1, 1L, false,
                order(1, "paid", "2026-10-18T10:00:00Z", "150", "BRL")));
        var metrics = new MetaInsightsMetrics(BigDecimal.ZERO, 0, 0, null, null,
                BigDecimal.ZERO, BigDecimal.ZERO, null);
        when(metaService.summary(eq(SINCE), eq(UNTIL), eq(100), eq(20)))
                .thenReturn(new MetaInsightsSummary(SINCE, UNTIL, true, 1, 0, null,
                        metrics, List.of()));
        assertNull(service.summary(SINCE, UNTIL, 100, 20).mer());
    }

    @Test void currencyMismatchHidesMerButDoesNotHideSourceMetrics() {
        when(shop.orders(1, 100)).thenReturn(page(1, 1L, false,
                order(1, "paid", "2026-10-18T10:00:00+0000", "200.00", "BRL")));
        when(metaClient.account()).thenReturn(Map.of("currency", "USD"));
        var result = service.summary(SINCE, UNTIL, 100, 20);
        assertTrue(result.complete());
        assertNull(result.mer());
        assertEquals(new BigDecimal("200.00"), result.paidRevenue());
        assertEquals(new BigDecimal("100.00"), result.mediaSpend());
        assertTrue(result.warnings().contains("CURRENCY_MISMATCH"));
    }

    @Test void pageCapNeverExposesPartialPaidTotalsAsFinal() {
        when(shop.orders(1, 100)).thenReturn(page(1, 500L, true,
                order(1, "paid", "2026-10-18T12:00:00Z", "200.00", "BRL")));
        var result = service.summary(SINCE, UNTIL, 100, 1);
        assertFalse(result.ordersComplete());
        assertFalse(result.complete());
        assertNull(result.paidOrders());
        assertNull(result.paidRevenue());
        assertNull(result.averageTicket());
        assertNull(result.mer());
        assertTrue(result.daily().isEmpty());
        assertTrue(result.warnings().contains("ORDERS_INCOMPLETE"));
        verify(shop, never()).orders(2, 100);
    }

    @Test void missingPagesOrDuplicatesCannotBeMarkedComplete() {
        when(shop.orders(1, 100)).thenReturn(page(1, 4L, false,
                order(1, "paid", "2026-10-18T12:00:00Z", "100.00", "BRL"),
                order(2, "paid", "2026-10-18T12:00:00Z", "100.00", "BRL")));
        assertFalse(service.summary(SINCE, UNTIL, 100, 20).ordersComplete());
    }

    @Test void incompleteMetaNeverProducesMerOrSpendFinality() {
        when(shop.orders(1, 100)).thenReturn(page(1, 1L, false,
                order(1, "paid", "2026-10-18T12:00:00Z", "100.00", "BRL")));
        meta(false, "100.00", "300.00");
        var result = service.summary(SINCE, UNTIL, 100, 20);
        assertTrue(result.ordersComplete());
        assertFalse(result.metaComplete());
        assertFalse(result.complete());
        assertEquals(new BigDecimal("100.00"), result.paidRevenue());
        assertNull(result.mediaSpend());
        assertNull(result.mer());
        assertTrue(result.warnings().contains("META_INCOMPLETE"));
    }

    @Test void rejectsInvalidDatesAndPaginationBeforeAccessingExternalApis() {
        var reversed = assertThrows(NuvemshopException.class,
                () -> service.summary(UNTIL, SINCE, 100, 20));
        assertEquals("INVALID_DATE_RANGE", reversed.code());
        assertEquals("INVALID_PAGINATION",
                assertThrows(NuvemshopException.class,
                        () -> service.summary(SINCE, UNTIL, 101, 20)).code());
        assertEquals("INVALID_PAGINATION",
                assertThrows(NuvemshopException.class,
                        () -> service.summary(SINCE, UNTIL, 100, 101)).code());
        verifyNoInteractions(shop);
    }

    @Test void rejectsMissingPaidAtNegativeTotalsAndMixedCurrencies() {
        when(shop.orders(1, 100)).thenReturn(page(1, null, false,
                order(1, "paid", null, "100.00", "BRL")));
        assertEquals("NUVEMSHOP_INVALID_ORDER_DATA",
                assertThrows(NuvemshopException.class,
                        () -> service.summary(SINCE, UNTIL, 100, 20)).code());
        when(shop.orders(1, 100)).thenReturn(page(1, null, false,
                order(1, "paid", "2026-10-18T12:00:00Z", "-1.00", "BRL")));
        assertEquals("NUVEMSHOP_INVALID_ORDER_DATA",
                assertThrows(NuvemshopException.class,
                        () -> service.summary(SINCE, UNTIL, 100, 20)).code());
        when(shop.orders(1, 100)).thenReturn(page(1, null, false,
                order(1, "paid", "2026-10-18T12:00:00Z", "100.00", "BRL"),
                order(2, "paid", "2026-10-18T12:00:00Z", "100.00", "USD")));
        assertEquals("NUVEMSHOP_INVALID_ORDER_DATA",
                assertThrows(NuvemshopException.class,
                        () -> service.summary(SINCE, UNTIL, 100, 20)).code());
        verifyNoInteractions(metaService);
    }

    @Test void refusesEmptyPageWithNextLink() {
        when(shop.orders(1, 100)).thenReturn(new NuvemshopPage(List.of(), 1, 100, 99L, true));
        assertEquals("NUVEMSHOP_INVALID_ORDER_DATA",
                assertThrows(NuvemshopException.class,
                        () -> service.summary(SINCE, UNTIL, 100, 20)).code());
    }

    @Test void propagatesControlledUpstreamRateLimitError() {
        when(shop.orders(1, 100)).thenThrow(new NuvemshopException(HttpStatus.SERVICE_UNAVAILABLE,
                "NUVEMSHOP_RATE_LIMIT", "Limite temporario.", 429));
        var exception = assertThrows(NuvemshopException.class,
                () -> service.summary(SINCE, UNTIL, 100, 20));
        assertEquals("NUVEMSHOP_RATE_LIMIT", exception.code());
        assertEquals(429, exception.upstreamStatus());
    }
}
