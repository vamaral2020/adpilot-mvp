package br.com.adpilot.integrations.nuvemshop;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(OutputCaptureExtension.class)
class NuvemshopOAuthResponseTest {
    private static final String STATE = "s".repeat(43);
    private static final String CODE = "confidential-authorization-code";
    private static final String TOKEN = "confidential-access-token";
    private static final String SECRET = "confidential-client-secret";
    private static final String VALID = """
            {"access_token":"confidential-access-token","user_id":456,
             "scope":"read_products,read_orders","token_type":"bearer"}
            """;

    private record Fixture(NuvemshopAuthService auth, NuvemshopRepository repo,
                           NuvemshopCrypto crypto) { }

    private void serve(int status, String type, String body, boolean challenge,
                       Consumer<Fixture> check) throws Exception {
        var server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/apps/authorize/token", req -> {
            req.getRequestBody().readAllBytes();
            byte[] data = body.getBytes(StandardCharsets.UTF_8);
            req.getResponseHeaders().set("Content-Type", type);
            if (challenge) req.getResponseHeaders().set("cf-mitigated", "challenge");
            if (data.length == 0) {
                req.sendResponseHeaders(status, -1);
                req.close();
            } else {
                req.sendResponseHeaders(status, data.length);
                try (var out = req.getResponseBody()) { out.write(data); }
            }
        });
        server.start();
        try {
            var settings = new NuvemshopSettings("1234", SECRET,
                    "https://callback.example.test/oauth", "AdPilot (test@example.com)",
                    Base64.getEncoder().encodeToString(
                        "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8)),
                    "https://api.nuvemshop.com.br", "2025-03",
                    "https://www.nuvemshop.com.br",
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/apps/authorize/token",
                    "http://localhost:13000/");
            var repo = mock(NuvemshopRepository.class);
            var crypto = new NuvemshopCrypto(settings);
            var auth = new NuvemshopAuthService(settings, repo, crypto,
                    RestClient.builder(), JsonMapper.builder().build());
            check.accept(new Fixture(auth, repo, crypto));
        } finally {
            server.stop(0);
        }
    }

    private void success(String type) throws Exception {
        serve(200, type, VALID, false, f -> {
            f.auth.complete(CODE, STATE);
            var bytes = ArgumentCaptor.forClass(byte[].class);
            verify(f.repo).consumeState(NuvemshopAuthService.digest(STATE));
            verify(f.repo).saveInstallation(eq("456"), eq("read_products,read_orders"),
                    bytes.capture());
            assertEquals(TOKEN, f.crypto.decrypt(bytes.getValue()));
            assertFalse(new String(bytes.getValue(), StandardCharsets.ISO_8859_1).contains(TOKEN));
        });
    }

    private void failure(int status, String type, String body, String code) throws Exception {
        serve(status, type, body, false, f -> {
            var e = assertThrows(NuvemshopException.class, () -> f.auth.complete(CODE, STATE));
            assertEquals(code, e.code());
            verify(f.repo).consumeState(NuvemshopAuthService.digest(STATE));
            verify(f.repo, never()).saveInstallation(anyString(), anyString(), any());
        });
    }

    @Test void acceptsJsonLabeledHtml() throws Exception { success("text/html; charset=UTF-8"); }
    @Test void acceptsNormalJson() throws Exception { success("application/json"); }

    @Test void rejectsOAuthErrorInHttp200() throws Exception {
        failure(200, "text/html", """
                {"error":"invalid_client","error_description":"upstream-private-detail"}
                """, "NUVEMSHOP_OAUTH_REJECTED");
    }

    @Test void rejectsActualHtml() throws Exception {
        failure(200, "text/html", "<html>not-json</html>", "NUVEMSHOP_INVALID_OAUTH_RESPONSE");
    }

    @Test void rejectsMalformedAndTrailingJson() throws Exception {
        failure(200, "text/html", "{not-json", "NUVEMSHOP_INVALID_OAUTH_RESPONSE");
        failure(200, "text/html", VALID + "<html>trailing</html>",
                "NUVEMSHOP_INVALID_OAUTH_RESPONSE");
    }

    @Test void rejectsEmptyBody() throws Exception {
        failure(200, "text/html", "", "NUVEMSHOP_INVALID_OAUTH_RESPONSE");
    }

    @Test void rejectsNonObjectJson() throws Exception {
        failure(200, "text/html", "[1,2]", "NUVEMSHOP_INVALID_OAUTH_RESPONSE");
        failure(200, "text/html", "null", "NUVEMSHOP_INVALID_OAUTH_RESPONSE");
    }

    @Test void rejectsHttp400And401() throws Exception {
        for (var status : new int[] {400, 401}) {
            failure(status, "application/json", "{\"error\":\"invalid_client\"}",
                    "NUVEMSHOP_OAUTH_REJECTED");
        }
    }

    @Test void rejectsIncompleteTokenResponse() throws Exception {
        failure(200, "application/json",
                "{\"user_id\":456,\"scope\":\"read_products,read_orders\"}",
                "NUVEMSHOP_INVALID_OAUTH_RESPONSE");
        failure(200, "application/json",
                "{\"access_token\":\"x\",\"scope\":\"read_products,read_orders\"}",
                "NUVEMSHOP_INVALID_OAUTH_RESPONSE");
        failure(200, "application/json",
                "{\"user_id\":456,\"access_token\":\"x\"}",
                "NUVEMSHOP_INVALID_OAUTH_RESPONSE");
    }

    @Test void rejectsInsufficientOrWriteScopes() throws Exception {
        failure(200, "application/json",
                "{\"user_id\":456,\"access_token\":\"x\",\"scope\":\"read_products\"}",
                "NUVEMSHOP_INVALID_OAUTH_RESPONSE");
        failure(200, "application/json",
                "{\"user_id\":456,\"access_token\":\"x\",\"scope\":\"read_products,read_orders,write_products\"}",
                "NUVEMSHOP_INVALID_OAUTH_RESPONSE");
    }

    @Test void classifiesCloudflareChallenge() throws Exception {
        serve(200, "text/html", "<html>blocked</html>", true, f -> {
            var e = assertThrows(NuvemshopException.class, () -> f.auth.complete(CODE, STATE));
            assertEquals("NUVEMSHOP_OAUTH_CHALLENGE", e.code());
            verify(f.repo, never()).saveInstallation(anyString(), anyString(), any());
        });
    }

    @Test void neverLogsSensitiveData(CapturedOutput output) throws Exception {
        failure(200, "text/html",
                "{\"error\":\"invalid_client\",\"detail\":\"upstream-private-detail\"}",
                "NUVEMSHOP_OAUTH_REJECTED");
        String logs = output.toString();
        assertFalse(logs.contains(CODE));
        assertFalse(logs.contains(STATE));
        assertFalse(logs.contains(SECRET));
        assertFalse(logs.contains(TOKEN));
        assertFalse(logs.contains("upstream-private-detail"));
    }
}
