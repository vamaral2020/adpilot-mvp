package br.com.adpilot.integrations.meta;

import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalDate;
import java.net.URLDecoder;
import java.util.LinkedHashMap;
import org.springframework.web.util.DefaultUriBuilderFactory;
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

    @Test void encodesInsightJsonTimeRangeAsSingleQueryParameter() {
        var query = new LinkedHashMap<String, Object>();
        query.put("fields", "spend,impressions,clicks");
        query.put("time_range", "{\\"since\\":\\"2026-10-12\\",\\"until\\":\\"2026-10-18\\"}");
        query.put("limit", 25);

        var factory = new DefaultUriBuilderFactory("https://graph.facebook.com");
        var uri = MetaAdsClient.buildUri(factory.builder(),
                "/v26.0/act_497208073675481/insights", query);

        assertEquals("https", uri.getScheme());
        assertEquals("graph.facebook.com", uri.getHost());
        assertEquals("/v26.0/act_497208073675481/insights", uri.getPath());
        assertFalse(uri.getRawQuery().contains("{"));
        String decoded = URLDecoder.decode(uri.getRawQuery(), StandardCharsets.UTF_8);
        assertTrue(decoded.contains("time_range={\\"since\\":\\"2026-10-12\\",\\"until\\":\\"2026-10-18\\"}"), decoded);
        assertTrue(decoded.contains("fields=spend,impressions,clicks"), decoded);
        assertTrue(decoded.contains("limit=25"), decoded);
    }

    @Test void safelyEncodesPaginationCursorAsValue() {
        var factory = new DefaultUriBuilderFactory("https://graph.facebook.com");
        var uri = MetaAdsClient.buildUri(factory.builder(), "/v26.0/act_497208073675481/campaigns",
                Map.of("after", "a+b/c&d=e"));
        assertEquals("after=a+b/c&d=e", URLDecoder.decode(uri.getRawQuery(), StandardCharsets.UTF_8));
        assertFalse(uri.getRawQuery().contains("&d=e"));
    }
}
