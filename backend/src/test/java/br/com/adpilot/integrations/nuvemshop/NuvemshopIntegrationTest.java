package br.com.adpilot.integrations.nuvemshop;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

class NuvemshopIntegrationTest {
    private static final String AES_KEY = Base64.getEncoder()
            .encodeToString("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));
    private static final String UA = "AdPilot (test@example.com)";

    private NuvemshopSettings settings(String apiBase, String oauthBase) {
        return settings(apiBase, oauthBase, oauthBase + "/apps/authorize/token");
    }

    private NuvemshopSettings settings(String apiBase, String oauthBase, String tokenUrl) {
        return new NuvemshopSettings("1234", "app-secret", "https://example.test/callback",
                UA, AES_KEY, apiBase, "2025-03", oauthBase, tokenUrl, "http://localhost:13000/");
    }

    @Test void cryptoEncryptsRandomlyAndDetectsTampering() {
        var crypto = new NuvemshopCrypto(settings("https://api.nuvemshop.com.br", "https://www.nuvemshop.com.br"));
        var first = crypto.encrypt("secret-access-token");
        var second = crypto.encrypt("secret-access-token");
        assertFalse(java.util.Arrays.equals(first, second));
        assertFalse(new String(first, StandardCharsets.ISO_8859_1).contains("secret-access-token"));
        assertEquals("secret-access-token", crypto.decrypt(first));
        assertEquals("secret-access-token", crypto.decrypt(second));
        first[first.length - 1] ^= 1;
        assertEquals("NUVEMSHOP_DECRYPTION_ERROR",
                assertThrows(NuvemshopException.class, () -> crypto.decrypt(first)).code());
    }

    @Test void oauthSavesOnlyEncryptedTokenAfterValidOneTimeState() throws Exception {
        var jsonSeen = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/apps/authorize/token", exchange -> {
            assertEquals("POST", exchange.getRequestMethod());
            assertEquals("application/json", exchange.getRequestHeaders().getFirst("Content-Type"));
            assertEquals("application/json", exchange.getRequestHeaders().getFirst("Accept"));
            assertEquals(UA, exchange.getRequestHeaders().getFirst("User-Agent"));
            jsonSeen.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = """
                    {"access_token":"nuvemshop-test-token","scope":"read_products,read_orders",
                     "user_id":789,"token_type":"bearer"}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (var out = exchange.getResponseBody()) { out.write(response); }
        });
        server.start();
        try {
            var repo = mock(NuvemshopRepository.class);
            var props = settings("https://api.nuvemshop.com.br", "https://www.nuvemshop.com.br",
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/apps/authorize/token");
            assertTrue(props.validTokenUrl());
            var crypto = new NuvemshopCrypto(props);
            var oauth = new NuvemshopAuthService(props, repo, crypto, RestClient.builder(), JsonMapper.builder().build());
            var begin = oauth.begin();
            assertEquals(900, begin.expiresInSeconds());
            assertTrue(begin.authorizationUrl().startsWith("https://www.nuvemshop.com.br/apps/1234/authorize?state="));
            String state = begin.authorizationUrl().split("state=")[1];
            assertEquals(43, state.length());
            var digestCaptor = ArgumentCaptor.forClass(byte[].class);
            verify(repo).createState(digestCaptor.capture());
            assertEquals(32, digestCaptor.getValue().length);
            assertArrayEquals(digestCaptor.getValue(), NuvemshopAuthService.digest(state));

            oauth.complete("single-use-authorization-code", state);
            verify(repo).consumeState(digestCaptor.getValue());
            var tokenCaptor = ArgumentCaptor.forClass(byte[].class);
            verify(repo).saveInstallation(eq("789"), eq("read_products,read_orders"),
                    tokenCaptor.capture());
            assertEquals("nuvemshop-test-token", crypto.decrypt(tokenCaptor.getValue()));
            assertFalse(new String(tokenCaptor.getValue(), StandardCharsets.ISO_8859_1)
                    .contains("nuvemshop-test-token"));
            assertTrue(jsonSeen.get().contains("\"grant_type\":\"authorization_code\""));
            assertTrue(jsonSeen.get().contains("\"code\":\"single-use-authorization-code\""));
        } finally {
            server.stop(0);
        }
    }

    @Test void readProductsAndOrdersSendsBearerAndSanitizesCustomerPersonalData() throws Exception {
        var header = new AtomicReference<String>();
        var agent = new AtomicReference<String>();
        var pathSeen = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            header.set(exchange.getRequestHeaders().getFirst("Authorization"));
            agent.set(exchange.getRequestHeaders().getFirst("User-Agent"));
            pathSeen.set(exchange.getRequestURI().toString());
            boolean orders = exchange.getRequestURI().getPath().endsWith("/orders");
            String body = orders ?
                    """
                    [{"id":11,"number":123,"payment_status":"paid","total":"201.05",
                      "customer":{"email":"private@example.com","phone":"5555"},
                      "shipping_address":{"address":"private-street"},
                      "products":[{"product_id":77,"variant_id":88,"name":"Blusa",
                                   "quantity":2,"price":"100.00",
                                   "customer":{"email":"hidden@example.com"}}]}]
                    """ :
                    """
                    [{"id":77,"name":{"pt":"Blusa"},"handle":{"pt":"blusa"},
                      "private_customer_email":"private@example.com",
                      "variants":[{"id":88,"sku":"ABC","price":"100.00","stock":9,
                                   "supplier_private_data":"must-not-leak"}]}]
                    """;
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.getResponseHeaders().set("x-total-count", "2");
            exchange.getResponseHeaders().set("Link", "<https://api.nuvemshop.com.br/2025-03/789/products?page=2>; rel=\"next\"");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var out = exchange.getResponseBody()) { out.write(bytes); }
        });
        server.start();
        try {
            var repo = mock(NuvemshopRepository.class);
            var props = settings("http://127.0.0.1:" + server.getAddress().getPort(),
                    "https://www.nuvemshop.com.br");
            var crypto = new NuvemshopCrypto(props);
            when(repo.installation()).thenReturn(Optional.of(
                    new NuvemshopRepository.Installation("789", "read_products,read_orders",
                            crypto.encrypt("test-bearer"), java.time.Instant.now())));
            var client = new NuvemshopClient(props, repo, crypto, RestClient.builder());

            var products = client.products(1, 30);
            assertEquals(1, products.data().size());
            assertEquals(2L, products.totalCount());
            assertTrue(products.hasNext());
            assertFalse(products.toString().contains("private@example.com"));
            assertFalse(products.toString().contains("must-not-leak"));
            assertEquals("Bearer test-bearer", header.get());
            assertEquals(UA, agent.get());
            assertTrue(pathSeen.get().startsWith("/2025-03/789/products?"));

            var orders = client.orders(1, 30);
            assertEquals("paid", orders.data().getFirst().get("payment_status"));
            assertFalse(orders.toString().contains("private@example.com"));
            assertFalse(orders.toString().contains("private-street"));
            assertFalse(orders.toString().contains("hidden@example.com"));
            assertTrue(pathSeen.get().startsWith("/2025-03/789/orders?"));
        } finally {
            server.stop(0);
        }
    }

    @Test void auditOrderProjectionIsReadOnlyAndNeverIncludesCustomerOrCardData()
            throws Exception {
        var seenUri = new AtomicReference<String>();
        var seenMethod = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            seenUri.set(exchange.getRequestURI().toString());
            seenMethod.set(exchange.getRequestMethod());
            assertEquals("Bearer test-bearer", exchange.getRequestHeaders().getFirst("Authorization"));
            String body = """
                    [{"id":99,"payment_status":"paid","paid_at":null,
                      "created_at":"2026-10-17T15:00:00Z",
                      "gateway":"offline","storefront":"api","app_id":1001,
                      "status":"open","shipping_status":"unpacked",
                      "customer":{"email":"must-not-leak@example.com"},
                      "payment_details":{"card":"do-not-return-card-data"},
                      "total":"9999.99"}]
                    """;
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.getResponseHeaders().set("x-total-count", "1");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var out = exchange.getResponseBody()) { out.write(bytes); }
        });
        server.start();
        try {
            var repo = mock(NuvemshopRepository.class);
            var props = settings("http://127.0.0.1:" + server.getAddress().getPort(),
                    "https://www.nuvemshop.com.br");
            var crypto = new NuvemshopCrypto(props);
            when(repo.installation()).thenReturn(Optional.of(
                    new NuvemshopRepository.Installation("789", "read_orders",
                            crypto.encrypt("test-bearer"), java.time.Instant.now())));
            var client = new NuvemshopClient(props, repo, crypto, RestClient.builder());
            var result = client.paymentAuditOrders(1, 30);
            assertEquals("GET", seenMethod.get());
            assertTrue(seenUri.get().startsWith("/2025-03/789/orders?"));
            assertTrue(seenUri.get().contains("fields="));
            assertFalse(seenUri.get().contains("customer"));
            assertFalse(seenUri.get().contains("payment_details"));
            assertFalse(seenUri.get().contains("total"));
            assertEquals(1, result.data().size());
            assertEquals("offline", result.data().getFirst().get("gateway"));
            assertNull(result.data().getFirst().get("paid_at"));
            var serialized = result.toString();
            assertFalse(serialized.contains("must-not-leak@example.com"));
            assertFalse(serialized.contains("do-not-return-card-data"));
            assertFalse(serialized.contains("9999.99"));
        } finally {
            server.stop(0);
        }
    }

    @Test void diagnosticsFetchFilteredAndUnfilteredJsonButCallerControlsLogging()
            throws Exception {
        var seenUris = new java.util.ArrayList<String>();
        var server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            assertEquals("GET", exchange.getRequestMethod());
            assertEquals("Bearer test-bearer", exchange.getRequestHeaders().getFirst("Authorization"));
            seenUris.add(exchange.getRequestURI().toString());
            String data = """
                    {"id":987654321,"number":158,"payment_status":"paid","paid_at":null,
                     "customer":{"email":"private@example.com"},
                     "payment_details":{"token":"PRIVATE-CARD-TOKEN"}}
                    """;
            byte[] bytes = data.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var out = exchange.getResponseBody()) { out.write(bytes); }
        });
        server.start();
        try {
            var repo = mock(NuvemshopRepository.class);
            var props = settings("http://127.0.0.1:" + server.getAddress().getPort(),
                    "https://www.nuvemshop.com.br");
            var crypto = new NuvemshopCrypto(props);
            when(repo.installation()).thenReturn(Optional.of(
                    new NuvemshopRepository.Installation("789", "read_orders",
                            crypto.encrypt("test-bearer"), java.time.Instant.now())));
            var client = new NuvemshopClient(props, repo, crypto, RestClient.builder());
            var filtered = client.diagnosticOrder(987654321L, true);
            var full = client.diagnosticOrder(987654321L, false);
            assertEquals(2, seenUris.size());
            assertTrue(seenUris.get(0).startsWith("/2025-03/789/orders/987654321?fields="));
            assertEquals("/2025-03/789/orders/987654321", seenUris.get(1));
            assertEquals("paid", filtered.get("payment_status"));
            assertTrue(filtered.containsKey("paid_at"));
            assertNull(filtered.get("paid_at"));
            assertEquals("paid", full.get("payment_status"));
            // Full upstream payload can include PII: NEVER serialize it directly to logs.
            var safe = NuvemshopPaymentDiagnosticService.snapshot(full);
            String visible = JsonMapper.builder().build().writeValueAsString(safe);
            assertFalse(visible.contains("private@example.com"));
            assertFalse(visible.contains("PRIVATE-CARD-TOKEN"));
        } finally {
            server.stop(0);
        }
    }

    @Test void refusesReadWithoutInstallationAndRejectsInvalidPages() {
        var repo = mock(NuvemshopRepository.class);
        when(repo.installation()).thenReturn(Optional.empty());
        var props = settings("https://api.nuvemshop.com.br", "https://www.nuvemshop.com.br");
        var client = new NuvemshopClient(props, repo, new NuvemshopCrypto(props),
                RestClient.builder());
        assertEquals("NUVEMSHOP_INVALID_PAGINATION",
                assertThrows(NuvemshopException.class, () -> client.products(0, 30)).code());
        assertEquals("NUVEMSHOP_INVALID_PAGINATION",
                assertThrows(NuvemshopException.class, () -> client.orders(1, 201)).code());
        assertEquals("NUVEMSHOP_NOT_CONNECTED",
                assertThrows(NuvemshopException.class, () -> client.products(1, 30)).code());
        assertEquals("NUVEMSHOP_INVALID_CALLBACK",
                assertThrows(NuvemshopException.class, () ->
                        new NuvemshopAuthService(props, repo, new NuvemshopCrypto(props),
                                RestClient.builder(), JsonMapper.builder().build()).complete("code", "forged")).code());
        verify(repo, never()).consumeState(any());
    }

    @Test void rejectsMisconfiguredAppBeforeSendingAuthRequests() {
        var invalid = new NuvemshopSettings("", "", "", "", "",
                "https://api.nuvemshop.com.br", "2025-03",
                "https://www.nuvemshop.com.br", "https://www.tiendanube.com/apps/authorize/token",
                "http://localhost:13000/");
        var repo = mock(NuvemshopRepository.class);
        var oauth = new NuvemshopAuthService(invalid, repo, new NuvemshopCrypto(invalid),
                RestClient.builder(), JsonMapper.builder().build());
        assertEquals("NOT_CONFIGURED", oauth.status().status());
        assertEquals("NUVEMSHOP_NOT_CONFIGURED",
                assertThrows(NuvemshopException.class, oauth::begin).code());
        verifyNoInteractions(repo);
    }

    @Test void diagnosesUnexpectedOAuthContentTypeWithoutExposingResponse() throws Exception {
        var server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/apps/authorize/token", exchange -> {
            byte[] body = "<html>SECRET_INTERNAL_DEBUG_DATA</html>".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/html");
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) { out.write(body); }
        });
        server.start();
        try {
            var repo = mock(NuvemshopRepository.class);
            var props = settings("https://api.nuvemshop.com.br",
                    "http://127.0.0.1:" + server.getAddress().getPort());
            var oauth = new NuvemshopAuthService(props, repo, new NuvemshopCrypto(props),
                    RestClient.builder(), JsonMapper.builder().build());
            var state = "a".repeat(43);
            var error = assertThrows(NuvemshopException.class,
                    () -> oauth.complete("secret-code", state));
            assertEquals("NUVEMSHOP_INVALID_OAUTH_RESPONSE", error.code());
            assertEquals(200, error.upstreamStatus());
            var payload = new NuvemshopExceptionHandler().handle(error).getBody();
            assertNotNull(payload);
            assertEquals(200, payload.get("upstream_http_status"));
            assertFalse(payload.toString().contains("secret-code"));
            assertFalse(payload.toString().contains("SECRET_INTERNAL_DEBUG_DATA"));
        } finally {
            server.stop(0);
        }
    }

    @Test void classifiesMalformedJsonWithSafeExceptionTypes() throws Exception {
        var server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/apps/authorize/token", exchange -> {
            byte[] body = "{unparseable-secret-data".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) { out.write(body); }
        });
        server.start();
        try {
            var repo = mock(NuvemshopRepository.class);
            var props = settings("https://api.nuvemshop.com.br",
                    "http://127.0.0.1:" + server.getAddress().getPort());
            var oauth = new NuvemshopAuthService(props, repo, new NuvemshopCrypto(props),
                    RestClient.builder(), JsonMapper.builder().build());
            var error = assertThrows(NuvemshopException.class,
                    () -> oauth.complete("secret-code", "b".repeat(43)));
            assertEquals("NUVEMSHOP_INVALID_OAUTH_RESPONSE", error.code());
            var serialized = new NuvemshopExceptionHandler().handle(error).getBody().toString();
            assertFalse(serialized.contains("secret-code"));
            assertFalse(serialized.contains("unparseable-secret-data"));
        } finally {
            server.stop(0);
        }
    }

    @Test void restrictsTokenEndpointToOfficialDomainsOrIsolatedLoopback() {
        assertTrue(settings("https://api.nuvemshop.com.br",
                "https://www.nuvemshop.com.br",
                "https://www.tiendanube.com/apps/authorize/token").validTokenUrl());
        assertTrue(settings("https://api.nuvemshop.com.br",
                "https://www.nuvemshop.com.br",
                "https://www.nuvemshop.com.br/apps/authorize/token").validTokenUrl());
        assertFalse(settings("https://api.nuvemshop.com.br",
                "https://www.nuvemshop.com.br",
                "https://not-nuvemshop.example/apps/authorize/token").configured());
        assertFalse(settings("https://api.nuvemshop.com.br",
                "https://www.nuvemshop.com.br",
                "http://www.tiendanube.com/apps/authorize/token").configured());
        assertFalse(settings("https://api.nuvemshop.com.br",
                "https://www.nuvemshop.com.br",
                "https://www.tiendanube.com/redirect?token=secret").configured());
    }

    @Test void classifiesCloudflareChallengeWithoutReadingHtmlOrSecrets() throws Exception {
        var server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/apps/authorize/token", exchange -> {
            byte[] body = "<html>DO-NOT-DISCLOSE</html>".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/html");
            exchange.getResponseHeaders().set("cf-mitigated", "challenge");
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) { out.write(body); }
        });
        server.start();
        try {
            var repo = mock(NuvemshopRepository.class);
            var props = settings("https://api.nuvemshop.com.br",
                    "https://www.nuvemshop.com.br",
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/apps/authorize/token");
            var oauth = new NuvemshopAuthService(props, repo, new NuvemshopCrypto(props),
                    RestClient.builder(), JsonMapper.builder().build());
            var error = assertThrows(NuvemshopException.class,
                    () -> oauth.complete("sensitive-code", "c".repeat(43)));
            assertEquals("NUVEMSHOP_OAUTH_CHALLENGE", error.code());
            var sanitized = new NuvemshopExceptionHandler().handle(error).getBody().toString();
            assertFalse(sanitized.contains("DO-NOT-DISCLOSE"));
            assertFalse(sanitized.contains("sensitive-code"));
        } finally {
            server.stop(0);
        }
    }
}
