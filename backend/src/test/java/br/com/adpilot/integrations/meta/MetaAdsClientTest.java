package br.com.adpilot.integrations.meta;

import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalDate;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.mock.http.MockHttpInputMessage;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class MetaAdsClientTest {
    private MetaAdsClient unconfigured() {
        return new MetaAdsClient(RestClient.builder(),
                new MetaSettings("v26.0", "", "", "https://graph.facebook.com"));
    }

    @Test void requiresConfigurationBeforeCallingMeta() {
        var err = assertThrows(MetaApiException.class, () -> unconfigured().account());
        assertEquals("META_NOT_CONFIGURED", err.errorCode());
    }

    @Test void checksPageSizeAndCursor() {
        assertEquals("INVALID_PAGINATION",
                assertThrows(MetaApiException.class, () -> unconfigured().campaigns(101, null)).errorCode());
        assertEquals("INVALID_PAGINATION",
                assertThrows(MetaApiException.class, () -> unconfigured().campaigns(10, "x".repeat(2049))).errorCode());
    }

    @Test void checksInsightDates() {
        assertEquals("INVALID_DATE_RANGE",
            assertThrows(MetaApiException.class, () ->
                unconfigured().insights(LocalDate.of(2025,1,1),LocalDate.of(2025,5,1),10,null)).errorCode());
    }

    @Test void rejectsInvalidAccountOrToken() {
        assertFalse(new MetaSettings("v26.0","act_123456","token","https://graph.facebook.com").configured());
        assertFalse(new MetaSettings("v26.0","123456","","https://graph.facebook.com").configured());
        assertTrue(new MetaSettings("v26.0","123456","token","https://graph.facebook.com").configured());
    }

    @Test void readsGraphJsonDeliveredAsJavascriptContentType() throws Exception {
        var converter = MetaAdsClient.metaJsonConverter();
        var javascript = new MediaType("text", "javascript");
        assertTrue(converter.canRead(Map.class, javascript));
        assertTrue(converter.canRead(Map.class, MediaType.APPLICATION_JSON));
        assertFalse(converter.canRead(Map.class, MediaType.TEXT_HTML));

        var response = new MockHttpInputMessage(
                "{\"id\":\"act_497208073675481\",\"account_status\":1}"
                        .getBytes(StandardCharsets.UTF_8));
        response.getHeaders().setContentType(javascript);
        Map<?, ?> body = assertInstanceOf(Map.class, converter.read(Map.class, response));
        assertEquals("act_497208073675481", body.get("id"));
        assertEquals(1, body.get("account_status"));
    }
}
