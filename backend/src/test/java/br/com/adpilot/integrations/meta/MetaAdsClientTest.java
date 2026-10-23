package br.com.adpilot.integrations.meta;

import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalDate;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import com.sun.net.httpserver.HttpServer;
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

    @Test void encodesJsonTimeRangeWithoutUriTemplateExpansion() {
        var params = new LinkedHashMap<String, Object>();
        params.put("fields", "spend,impressions,clicks");
        params.put("time_range", "{\"since\":\"2026-10-12\",\"until\":\"2026-10-18\"}");
        params.put("limit", 25);

        var uri = MetaAdsClient.buildUri("https://graph.facebook.com",
                "/v26.0/act_497208073675481/insights", params);
        assertEquals("https", uri.getScheme());
        assertEquals("graph.facebook.com", uri.getHost());
        assertEquals("/v26.0/act_497208073675481/insights", uri.getPath());
        assertTrue(uri.getRawQuery().contains("time_range=%7B%22since%22"));
        var decoded = URLDecoder.decode(uri.getRawQuery(), StandardCharsets.UTF_8);
        assertTrue(decoded.contains("time_range={\"since\":\"2026-10-12\",\"until\":\"2026-10-18\"}"), decoded);
        assertTrue(decoded.contains("fields=spend,impressions,clicks"), decoded);
        assertTrue(decoded.contains("limit=25"), decoded);
    }

    @Test void preservesSpecialCharactersInPaginationCursor() {
        var uri = MetaAdsClient.buildUri("https://graph.facebook.com/",
                "/v26.0/act_497208073675481/campaigns", Map.of("after", "a+b/c&d=e"));
        assertEquals("after=a%2Bb%2Fc%26d%3De", uri.getRawQuery());
    }

    @Test void callsInsightsThroughRealRestClientWithoutExpansionError() throws Exception {
        var server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        var receivedQuery = new AtomicReference<String>();
        server.createContext("/", exchange -> {
            receivedQuery.set(exchange.getRequestURI().getRawQuery());
            var payload = "{\"data\":[{\"date_start\":\"2026-10-12\",\"spend\":\"1.00\"}]}";
            var response = payload.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/javascript; charset=UTF-8");
            exchange.sendResponseHeaders(200, response.length);
            try (var output = exchange.getResponseBody()) {
                output.write(response);
            }
        });
        server.start();
        try {
            var settings = new MetaSettings("v26.0", "497208073675481", "test-token",
                    "http://127.0.0.1:" + server.getAddress().getPort());
            var client = new MetaAdsClient(RestClient.builder(), settings);
            var result = client.insights(LocalDate.of(2026, 10, 12),
                    LocalDate.of(2026, 10, 18), 25, null);
            assertEquals(1, result.data().size());
            assertEquals("1.00", result.data().getFirst().get("spend"));
            String decoded = URLDecoder.decode(receivedQuery.get(), StandardCharsets.UTF_8);
            assertTrue(decoded.contains("time_range={\"since\":\"2026-10-12\",\"until\":\"2026-10-18\"}"), decoded);
        } finally {
            server.stop(0);
        }
    }

    @Test void adFormatsUseCampaignScopedPathsAndNeverExposeMetaTokenInUri() throws Exception {
        var server=HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(),0),0);
        var paths=new java.util.concurrent.CopyOnWriteArrayList<String>();
        server.createContext("/",exchange->{
            paths.add(exchange.getRequestURI().toString());
            byte[] response="{\"data\":[]}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/json");
            exchange.sendResponseHeaders(200,response.length);
            try(var out=exchange.getResponseBody()){out.write(response);}
        });
        server.start();
        try {
            var settings=new MetaSettings("v26.0","497208073675481","fake-secret",
                    "http://127.0.0.1:"+server.getAddress().getPort());
            var client=new MetaAdsClient(RestClient.builder(),settings);
            assertTrue(client.adsets("52597352471388",100,null).data().isEmpty());
            assertTrue(client.ads("52597352471388",100,null).data().isEmpty());
            assertEquals(2,paths.size());
            assertTrue(paths.get(0).startsWith("/v26.0/52597352471388/adsets?"));
            assertTrue(paths.get(1).startsWith("/v26.0/52597352471388/ads?"));
            assertTrue(paths.get(1).contains("creative%7Bid%2Cobject_type"));
            assertTrue(paths.stream().noneMatch(x->x.contains("fake-secret")));
        }finally{server.stop(0);}
    }


    @Test void checksCampaignOwnershipAgainstConfiguredAdAccount() throws Exception {
        var server=HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(),0),0);
        var seen=new java.util.concurrent.CopyOnWriteArrayList<String>();
        server.createContext("/",exchange->{
            seen.add(exchange.getRequestURI().toString());
            String response="{\"id\":\"52597352471388\",\"account_id\":\"act_497208073675481\"}";
            byte[] body=response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/json");
            exchange.sendResponseHeaders(200,body.length);
            try(var out=exchange.getResponseBody()){out.write(body);}
        });
        server.start();
        try {
            var client=new MetaAdsClient(RestClient.builder(),new MetaSettings("v26.0",
                    "497208073675481","secret","http://127.0.0.1:"+server.getAddress().getPort()));
            assertTrue(client.belongsToConfiguredAccount("52597352471388"));
            assertEquals(1,seen.size());
            assertTrue(seen.getFirst().startsWith("/v26.0/52597352471388?"));
            assertFalse(seen.getFirst().contains("secret"));
        }finally{server.stop(0);}
    }

}
