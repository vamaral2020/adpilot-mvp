package br.com.adpilot.integrations.meta;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MetaPurchaseAuditServiceTest {
    private static final LocalDate START = LocalDate.of(2026, 10, 12);
    private static final LocalDate END = LocalDate.of(2026, 10, 18);
    private MetaAdsClient client;
    private MetaPurchaseAuditService service;

    @BeforeEach void init() {
        client = mock(MetaAdsClient.class);
        service = new MetaPurchaseAuditService(client);
    }

    private static Map<String, Object> action(String type, String value) {
        return Map.of("action_type", type, "value", value);
    }

    private static Map<String, Object> row(List<?> actions, List<?> values) {
        return Map.of("campaign_id", "123", "spend", "100.00",
                "actions", actions, "action_values", values,
                "customer", Map.of("email", "secret@example.com"));
    }

    @Test void auditsOverlappingActionTaxonomiesWithoutDoubleCountingPurchases() {
        when(client.insights(START, END, 100, null))
                .thenReturn(new MetaPage(List.of(
                        row(List.of(action("purchase", "3"),
                                    action("omni_purchase", "3"),
                                    action("offsite_conversion.fb_pixel_purchase", "3")),
                            List.of(action("purchase", "698.05"),
                                    action("omni_purchase", "698.05"))),
                        row(List.of(action("omni_purchase", "1")),
                            List.of(action("omni_purchase", "99.99")))
                ), null));
        var result = service.audit(START, END, 100, 20);
        assertTrue(result.complete());
        assertEquals(2, result.recordsFetched());
        assertEquals(1, result.pagesFetched());
        assertEquals(new BigDecimal("4"), result.selectedPurchases());
        assertEquals(new BigDecimal("798.04"), result.selectedRevenue());
        assertEquals(1, result.selectedCountTypes().get("purchase"));
        assertEquals(1, result.selectedCountTypes().get("omni_purchase"));
        var purchase = result.actionBreakdown().stream()
                .filter(a -> a.actionType().equals("purchase")).findFirst().orElseThrow();
        var omni = result.actionBreakdown().stream()
                .filter(a -> a.actionType().equals("omni_purchase")).findFirst().orElseThrow();
        assertEquals(new BigDecimal("3"), purchase.count());
        assertEquals(new BigDecimal("4"), omni.count()); // correlated taxonomies: do not add.
        assertTrue(result.warnings().contains("TAXONOMIES_NOT_ADDITIVE"));
        assertTrue(result.warnings().contains("PIXEL_CAPI_DEDUP_NOT_VERIFIED"));
        assertFalse(result.toString().contains("secret@example.com"));
        verify(client).insights(START, END, 100, null);
    }

    @Test void firstPresentTypeMatchesCurrentSummaryPrecedenceIndependentlyForCountsAndValues() {
        when(client.insights(START, END, 100, null)).thenReturn(new MetaPage(List.of(
                row(List.of(action("offsite_conversion.fb_pixel_purchase", "2"),
                            action("purchase", "3")),
                    List.of(action("omni_purchase", "300.00")))), null));
        var result = service.audit(START, END, 100, 20);
        assertEquals(new BigDecimal("3"), result.selectedPurchases());
        assertEquals(new BigDecimal("300.00"), result.selectedRevenue());
        assertEquals(1, result.rowsWithDifferentSelectedTypes());
        assertTrue(result.warnings().contains("COUNT_VALUE_TYPES_DIFFER"));
    }

    @Test void flagsNoPurchaseActionsWithoutAssumingPixelOrCapiIsBroken() {
        when(client.insights(START, END, 100, null)).thenReturn(new MetaPage(List.of(
                row(List.of(action("link_click", "30")), List.of())),
                null));
        var result = service.audit(START, END, 100, 20);
        assertEquals(new BigDecimal("0"), result.selectedPurchases());
        assertEquals(1, result.rowsWithoutPurchaseActions());
        assertEquals(1, result.rowsWithoutPurchaseValues());
        assertTrue(result.warnings().contains("NO_ATTRIBUTED_PURCHASES"));
        assertTrue(result.warnings().contains("PIXEL_CAPI_DEDUP_NOT_VERIFIED"));
    }

    @Test void paginatesAndNeverPublishesSampleTotalsAsAccountTotals() {
        when(client.insights(START, END, 100, null)).thenReturn(new MetaPage(List.of(
                row(List.of(action("purchase", "3")), List.of(action("purchase", "100")))), "cursorX"));
        var result = service.audit(START, END, 100, 1);
        assertFalse(result.complete());
        assertEquals(1, result.pagesFetched());
        assertEquals(1, result.recordsFetched());
        assertNull(result.selectedPurchases());
        assertNull(result.selectedRevenue());
        assertTrue(result.warnings().contains("INSIGHTS_INCOMPLETE"));
        verify(client, never()).insights(START, END, 100, "cursorX");
    }

    @Test void followsNextPageAndDetectsRepeatedCursor() {
        when(client.insights(START, END, 100, null)).thenReturn(new MetaPage(
                List.of(row(List.of(action("purchase", "1")), List.of())), "cursor1"));
        when(client.insights(START, END, 100, "cursor1")).thenReturn(new MetaPage(
                List.of(row(List.of(action("purchase", "2")), List.of())), null));
        var result = service.audit(START, END, 100, 20);
        assertTrue(result.complete());
        assertEquals(new BigDecimal("3"), result.selectedPurchases());
        assertEquals(2, result.pagesFetched());

        when(client.insights(START, END, 100, "cursor1")).thenReturn(new MetaPage(
                List.of(row(List.of(action("purchase", "2")), List.of())), "cursor1"));
        assertEquals("META_INVALID_PURCHASE_AUDIT",
                assertThrows(MetaApiException.class,
                        () -> service.audit(START, END, 100, 20)).errorCode());
    }

    @Test void invalidInputRejectedWithoutCallingMeta() {
        assertEquals("INVALID_DATE_RANGE",
                assertThrows(MetaApiException.class, () -> service.audit(END, START, 100, 20))
                        .errorCode());
        assertEquals("INVALID_PAGINATION",
                assertThrows(MetaApiException.class, () -> service.audit(START, END, 101, 20))
                        .errorCode());
        verifyNoInteractions(client);
    }

    @Test void rejectsInvalidPurchaseValuesAndNeverLogsRawResponse() {
        when(client.insights(START, END, 100, null)).thenReturn(new MetaPage(
                List.of(row(List.of(action("purchase", "-1")), List.of())), null));
        assertEquals("META_INVALID_PURCHASE_AUDIT",
                assertThrows(MetaApiException.class,
                        () -> service.audit(START, END, 100, 20)).errorCode());
    }

    @Test void duplicateTaxonomyWithinSingleRowUsesFirstOccurrence() {
        when(client.insights(START, END, 100, null)).thenReturn(new MetaPage(
                List.of(row(List.of(action("purchase", "2"), action("purchase", "5")),
                        List.of(action("purchase", "100")))), null));
        var result = service.audit(START, END, 100, 20);
        assertEquals(new BigDecimal("2"), result.selectedPurchases());
    }
}
