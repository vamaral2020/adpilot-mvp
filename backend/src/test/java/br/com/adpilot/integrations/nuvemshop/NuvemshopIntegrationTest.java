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

class NuvemshopIntegrationTest {
    private static final String AES_KEY = Base64.getEncoder()
            .encodeToString("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));
    private static final String UA = "AdPilot (test@example.com)";

    private NuvemshopSettings settings(String apiBase, String oauthBase) {
        return new NuvemshopSettings("1234", "app-secret", "https://example.test/callback",
                UA, AES_KEY, apiBase, "2025-03", oauthBase, "http://localhost:13000/");
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
            var props = settings("https://api.nuvemshop.com.br", "http://127.0.0.1:" + server.getAddress().getPort());
            var crypto = new NuvemshopCrypto(props);
            var oauth = new NuvemshopAuthService(props, repo, crypto, RestClient.builder());
            var begin = oauth.begin();
            assertEquals(300, begin.expiresInSeconds());
            assertTrue(begin.authorizationUrl().contains("/apps/1234/authorize?state="));
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
                                RestClient.builder()).complete("code", "forged")).code());
        verify(repo, never()).consumeState(any());
    }

    @Test void rejectsMisconfiguredAppBeforeSendingAuthRequests() {
        var invalid = new NuvemshopSettings("", "", "", "", "",
                "https://api.nuvemshop.com.br", "2025-03",
                "https://www.nuvemshop.com.br", "http://localhost:13000/");
        var repo = mock(NuvemshopRepository.class);
        var oauth = new NuvemshopAuthService(invalid, repo, new NuvemshopCrypto(invalid),
                RestClient.builder());
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
                    RestClient.builder());
            var state = "a".repeat(43);
            var error = assertThrows(NuvemshopException.class,
                    () -> oauth.complete("secret-code", state));
            assertEquals("NUVEMSHOP_OAUTH_CONTENT_TYPE", error.code());
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
                    RestClient.builder());
            var error = assertThrows(NuvemshopException.class,
                    () -> oauth.complete("secret-code", "b".repeat(43)));
            assertEquals("NUVEMSHOP_OAUTH_UNAVAILABLE", error.code());
            assertNotNull(error.clientException());
            assertNotNull(error.rootException());
            var serialized = new NuvemshopExceptionHandler().handle(error).getBody().toString();
            assertFalse(serialized.contains("secret-code"));
            assertFalse(serialized.contains("unparseable-secret-data"));
        } finally {
            server.stop(0);
        }
    }
}
