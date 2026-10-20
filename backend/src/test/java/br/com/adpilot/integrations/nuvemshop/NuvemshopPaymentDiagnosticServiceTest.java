package br.com.adpilot.integrations.nuvemshop;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.json.JsonMapper;

class NuvemshopPaymentDiagnosticServiceTest {
    private NuvemshopClient shop;
    private JsonMapper mapper;
    private NuvemshopPaymentDiagnosticService service;

    @BeforeEach
    void setup() {
        shop = mock(NuvemshopClient.class);
        mapper = JsonMapper.builder().build();
        service = new NuvemshopPaymentDiagnosticService(shop, mapper, true);
    }

    private static Map<String, Object> row(Object paidAt) {
        var data = new HashMap<String, Object>();
        data.put("id", 987654321L);
        data.put("number", 158);
        data.put("status", "open");
        data.put("payment_status", "paid");
        data.put("created_at", "2026-10-09T02:06:00+0000");
        data.put("paid_at", paidAt);
        data.put("customer", Map.of("email", "very-private@example.com"));
        data.put("payment_details", Map.of("card", "NEVER-DISCLOSE-CARD"));
        data.put("access_token", "NEVER-DISCLOSE-TOKEN");
        return data;
    }

    @Test void comparesAllThreeResponsesAndPreservesNullVersusPresentDate() {
        var listed = row(null);
        var filtered = row(null);
        var full = row("2026-10-09T11:22:33-0300");
        full.put("updated_at", "2026-10-10T01:00:00+0000");
        when(shop.orders(1, 100))
                .thenReturn(new NuvemshopPage(List.of(listed), 1, 100, 1L, false));
        when(shop.diagnosticOrder(987654321L, true)).thenReturn(filtered);
        when(shop.diagnosticOrder(987654321L, false)).thenReturn(full);

        var output = service.diagnose(158);
        assertEquals(158, output.orderNumber());
        assertEquals("null", output.list().get("paid_at").type());
        assertTrue(output.list().get("paid_at").present());
        assertEquals("null", output.individualFiltered().get("paid_at").type());
        assertEquals("string", output.individualFull().get("paid_at").type());
        assertEquals("2026-10-09T11:22:33-0300",
                output.individualFull().get("paid_at").value());
        assertFalse(output.list().get("updated_at").present());
        assertTrue(output.individualFull().get("updated_at").present());
        String json = mapper.writeValueAsString(output);
        assertTrue(json.contains("2026-10-09T11:22:33-0300"));
        assertFalse(json.contains("very-private@example.com"));
        assertFalse(json.contains("NEVER-DISCLOSE-CARD"));
        assertFalse(json.contains("NEVER-DISCLOSE-TOKEN"));
        verify(shop).orders(1, 100);
        verify(shop).diagnosticOrder(987654321L, true);
        verify(shop).diagnosticOrder(987654321L, false);
        verifyNoMoreInteractions(shop);
    }

    @Test void reportsMissingVersusExplicitNullAndIgnoresUnexpectedValues() {
        var raw = row(null);
        raw.remove("paid_at");
        raw.put("status", "private-value@example.com");
        raw.put("updated_at", "<sensitive-html>");
        raw.put("created_at", Map.of("secret", "should-not-leak"));
        var projected = NuvemshopPaymentDiagnosticService.snapshot(raw);
        assertEquals(new NuvemshopPaymentDiagnosticService.Field(false, "missing", null),
                projected.get("paid_at"));
        assertEquals(new NuvemshopPaymentDiagnosticService.Field(true, "unexpected", null),
                projected.get("updated_at"));
        assertEquals("unexpected", projected.get("status").type());
        assertEquals("unexpected", projected.get("created_at").type());
        String json = mapper.writeValueAsString(projected);
        assertFalse(json.contains("sensitive-html"));
        assertFalse(json.contains("private-value@example.com"));
        assertFalse(json.contains("should-not-leak"));
    }

    @Test void featureDisabledDoesNotAccessUpstream() {
        var disabled = new NuvemshopPaymentDiagnosticService(shop, mapper, false);
        var error = assertThrows(NuvemshopException.class, () -> disabled.diagnose(158));
        assertEquals(HttpStatus.NOT_FOUND, error.status());
        assertEquals("DIAGNOSTICS_DISABLED", error.code());
        verifyNoInteractions(shop);
    }

    @Test void rejectsInvalidOrderNumbersWithoutUpstreamCalls() {
        for (int number : new int[] {0, -1}) {
            var error = assertThrows(NuvemshopException.class, () -> service.diagnose(number));
            assertEquals("INVALID_ORDER_NUMBER", error.code());
        }
        verifyNoInteractions(shop);
    }

    @Test void findsRequestedNumberAcrossPaginatedListWithoutGuessingLastOrder() {
        var other = row(null);
        other.put("id", 111L);
        other.put("number", 201);
        var wanted = row(null);
        when(shop.orders(1, 100)).thenReturn(new NuvemshopPage(List.of(other), 1, 100, 2L, true));
        when(shop.orders(2, 100)).thenReturn(new NuvemshopPage(List.of(wanted), 2, 100, 2L, false));
        when(shop.diagnosticOrder(987654321L, true)).thenReturn(wanted);
        when(shop.diagnosticOrder(987654321L, false)).thenReturn(wanted);
        service.diagnose(158);
        verify(shop).orders(1, 100);
        verify(shop).orders(2, 100);
    }

    @Test void rejectsMismatchBetweenListAndIndividualOrderIdentity() {
        var listed = row(null);
        var wrong = row(null);
        wrong.put("number", 159);
        when(shop.orders(1, 100)).thenReturn(new NuvemshopPage(List.of(listed), 1, 100, 1L, false));
        when(shop.diagnosticOrder(987654321L, true)).thenReturn(wrong);
        when(shop.diagnosticOrder(987654321L, false)).thenReturn(listed);
        var error = assertThrows(NuvemshopException.class, () -> service.diagnose(158));
        assertEquals("ORDER_DIAGNOSTIC_MISMATCH", error.code());
    }

    @Test void absentOrderReturnsNotFoundAndDoesNotFetchIndividualData() {
        when(shop.orders(1, 100))
                .thenReturn(new NuvemshopPage(List.of(row(null)), 1, 100, 1L, false));
        var error = assertThrows(NuvemshopException.class, () -> service.diagnose(119));
        assertEquals("ORDER_NOT_FOUND_IN_LIST", error.code());
        assertEquals(HttpStatus.NOT_FOUND, error.status());
        verify(shop, never()).diagnosticOrder(anyLong(), anyBoolean());
    }
}
