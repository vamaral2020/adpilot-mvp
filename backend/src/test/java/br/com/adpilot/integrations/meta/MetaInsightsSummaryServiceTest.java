package br.com.adpilot.integrations.meta;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class MetaInsightsSummaryServiceTest {
    private static final LocalDate START = LocalDate.of(2026, 10, 12);
    private static final LocalDate END = LocalDate.of(2026, 10, 18);

    private static HttpServer server(java.util.function.Function<String, String> respond,
            AtomicInteger calls, AtomicReference<String> auth) throws Exception {
        var server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            calls.incrementAndGet();
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            String body = respond.apply(exchange.getRequestURI().getRawQuery());
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/javascript; charset=UTF-8");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();
        return server;
    }

    private static MetaInsightsSummaryService clientFor(HttpServer server) {
        var settings = new MetaSettings("v26.0", "497208073675481", "test-token",
                "http://127.0.0.1:" + server.getAddress().getPort());
        return new MetaInsightsSummaryService(new MetaAdsClient(RestClient.builder(), settings));
    }

    private static final String FIRST = """
            {"data":[{"campaign_id":"A","campaign_name":"Vendas",
              "date_start":"2026-10-16","spend":"10.00","impressions":"100","clicks":"10",
              "actions":[{"action_type":"purchase","value":"1"},
                         {"action_type":"omni_purchase","value":"1"}],
              "action_values":[{"action_type":"purchase","value":"30.00"},
                               {"action_type":"omni_purchase","value":"30.00"}]}],
             "paging":{"next":"https://graph.facebook.com/?access_token=DO-NOT-LEAK",
                       "cursors":{"after":"NEXT_CURSOR"}}}
            """;

    private static final String SECOND = """
            {"data":[{"campaign_id":"B","campaign_name":"Engajamento",
              "date_start":"2026-10-17","spend":"5.00","impressions":"50","clicks":"5",
              "actions":[{"action_type":"offsite_conversion.fb_pixel_purchase","value":"2"}],
              "action_values":[{"action_type":"offsite_conversion.fb_pixel_purchase","value":"20.00"}]}],
             "paging":{"cursors":{"after":"LAST_CURSOR"}}}
            """;

    @Test
    void followsCursorButNeverFollowsOrLeaksPagingNextToken() throws Exception {
        var calls = new AtomicInteger();
        var auth = new AtomicReference<String>();
        var server = server(raw -> {
            var query = URLDecoder.decode(raw, StandardCharsets.UTF_8);
            return query.contains("after=NEXT_CURSOR") ? SECOND : FIRST;
        }, calls, auth);
        try {
            var result = clientFor(server).summary(START, END, 100, 20);
            assertTrue(result.complete());
            assertEquals(2, result.pagesFetched());
            assertEquals(2, result.recordsFetched());
            assertNull(result.after());
            assertEquals(2, result.campaigns().size());
            assertEquals("Bearer test-token", auth.get());
            assertEquals(2, calls.get());
            assertEquals(new BigDecimal("15.00"), result.totals().spend());
            assertEquals(150, result.totals().impressions());
            assertEquals(15, result.totals().clicks());
            assertEquals(new BigDecimal("10.0000"), result.totals().ctr());
            assertEquals(new BigDecimal("1.0000"), result.totals().cpc());
            assertEquals(new BigDecimal("3"), result.totals().purchases());
            assertEquals(new BigDecimal("50.00"), result.totals().purchaseValue());
            assertEquals(new BigDecimal("3.3333"), result.totals().roas());
            assertEquals("A", result.campaigns().getFirst().campaignId());
            assertFalse(result.toString().contains("DO-NOT-LEAK"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void reportsPartialSummaryWhenMaximumPagesReached() throws Exception {
        var calls = new AtomicInteger();
        var server = server(raw -> FIRST, calls, new AtomicReference<>());
        try {
            var result = clientFor(server).summary(START, END, 100, 1);
            assertFalse(result.complete());
            assertEquals(1, result.pagesFetched());
            assertEquals(1, result.recordsFetched());
            assertEquals("NEXT_CURSOR", result.after());
            assertEquals(new BigDecimal("10.00"), result.totals().spend());
            assertEquals(1, calls.get());
            assertFalse(result.toString().contains("DO-NOT-LEAK"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsDuplicateCursorInsteadOfInfiniteLoop() throws Exception {
        var calls = new AtomicInteger();
        var server = server(raw -> FIRST, calls, new AtomicReference<>());
        try {
            var error = assertThrows(MetaApiException.class,
                    () -> clientFor(server).summary(START, END, 100, 20));
            assertEquals("META_INVALID_PAGINATION", error.errorCode());
            assertEquals(2, calls.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void acceptsEmptyResponseAsCompleteWithoutMetrics() throws Exception {
        var calls = new AtomicInteger();
        var server = server(raw -> "{\"data\":[]}", calls, new AtomicReference<>());
        try {
            var result = clientFor(server).summary(START, END, 100, 20);
            assertTrue(result.complete());
            assertEquals(0, result.recordsFetched());
            assertEquals(BigDecimal.ZERO, result.totals().spend());
            assertNull(result.totals().roas());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsExcessivePageCountsBeforeCallingMeta() {
        var settings = new MetaSettings("v26.0", "497208073675481", "test-token",
                "http://localhost:9");
        var service = new MetaInsightsSummaryService(
                new MetaAdsClient(RestClient.builder(), settings));
        var error = assertThrows(MetaApiException.class,
                () -> service.summary(START, END, 100, 21));
        assertEquals("INVALID_PAGINATION", error.errorCode());
    }
}
