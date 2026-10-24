package br.com.adpilot.integrations.stone;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class StoneChargeClientTest {
    private static final String SECRET="sk_test_nonreal_unit_test_secret";

    private static HttpServer server(int code,String body,AtomicReference<String> seen)
            throws Exception {
        var server=HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(),0),0);
        server.createContext("/",exchange->{
            seen.set(exchange.getRequestMethod()+"|"+exchange.getRequestURI()+"|"
                    +exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] bytes=body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/json");
            exchange.sendResponseHeaders(code,bytes.length);
            try(var out=exchange.getResponseBody()) {out.write(bytes);}
        });
        server.start();
        return server;
    }

    private StoneChargeClient client(HttpServer server) {
        return new StoneChargeClient(SECRET,RestClient.builder(),
                URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/core/v5"));
    }

    @Test void basicAuthUsesSecretAsUsernameAndEmptyPasswordAndOnlyGetCharge() throws Exception {
        var request=new AtomicReference<String>();
        String json="""
            {"id":"ch_abc12345","status":"paid","payment_method":"credit_card",
             "currency":"BRL","amount":12999,"paid_amount":12999,
             "customer":{"email":"pii@example.com"},
             "last_transaction":{"card":{"number":"SECRET-CARD-DETAIL"}},
             "gateway_id":"SECRET-GATEWAY-ID"}
            """;
        var server=server(200,json,request);
        try {
            var signals=client(server).lookup("ch_abc12345");
            String expected="Basic "+Base64.getEncoder().encodeToString(
                    (SECRET+":").getBytes(StandardCharsets.UTF_8));
            assertEquals("GET|/core/v5/charges/ch_abc12345|"+expected,request.get());
            assertEquals("PAID",signals.status());
            assertEquals("CREDIT_CARD",signals.paymentMethod());
            assertEquals("BRL",signals.currency());
            assertTrue(signals.amountPresent());
            assertTrue(signals.paidAmountPresent());
            assertTrue(signals.amountFormatValid());
            assertTrue(signals.paidAmountFormatValid());
            assertFalse(signals.toString().contains("pii@example.com"));
            assertFalse(signals.toString().contains("SECRET-CARD"));
            assertFalse(signals.toString().contains("12999"));
            assertFalse(signals.toString().contains("GATEWAY-ID"));
        } finally {server.stop(0);}
    }

    @Test void handlesChargebackAndUnknownStatusesConservatively() throws Exception {
        var seen=new AtomicReference<String>();
        var server=server(200,"""
            {"id":"ch_abc12345","status":"chargedback",
             "payment_method":"new-private-method","currency":"USD",
             "amount":1500,"paid_amount":"1500"}
            """,seen);
        try {
            var snapshot=client(server).lookup("ch_abc12345");
            assertEquals("CHARGEDBACK",snapshot.status());
            assertEquals("UNKNOWN",snapshot.paymentMethod());
            assertEquals("UNKNOWN",snapshot.currency());
            assertTrue(snapshot.amountFormatValid());
            assertFalse(snapshot.paidAmountFormatValid());
        } finally {server.stop(0);}
    }

    @Test void forbidsUntrustedIdBeforeProviderCall() {
        var client=new StoneChargeClient(SECRET,RestClient.builder(),
                URI.create("http://127.0.0.1:1/core/v5"));
        for(String id:new String[]{"../secret","ch_bad/path","ch_a?query=secret",
                "","ch_","ch_a","ch_abc%2Fxyz","https://example.com/"}) {
            var e=assertThrows(StoneChargeException.class,()->client.lookup(id));
            assertEquals("INVALID_CHARGE_ID",e.code());
        }
        assertEquals("INVALID_CHARGE_ID",
                assertThrows(StoneChargeException.class,()->client.lookup(null)).code());
    }

    @Test void missingSecretAlwaysFailsClosed() {
        for(String key:new String[]{"","pk_test_fakekey","invalid","sk_no"}) {
            var client=new StoneChargeClient(key,RestClient.builder(),
                    URI.create("http://127.0.0.1:1/core/v5"));
            assertFalse(client.configured());
            var error=assertThrows(StoneChargeException.class,
                    ()->client.lookup("ch_abc12345"));
            assertEquals("STONE_NOT_CONFIGURED",error.code());
        }
    }

    @Test void invalidRemoteIdDoesNotIdentifyUnrelatedCharge() throws Exception {
        var seen=new AtomicReference<String>();
        var server=server(200,"""
            {"id":"ch_other1234","status":"paid","amount":100,"paid_amount":100}
            """,seen);
        try {
            var error=assertThrows(StoneChargeException.class,
                    ()->client(server).lookup("ch_abc12345"));
            assertEquals("STONE_INVALID_CHARGE_RESPONSE",error.code());
        } finally {server.stop(0);}
    }

    @Test void errorsNeverIncludeProviderBodyOrCredentials() throws Exception {
        for(int status:new int[]{401,403,404,429,500}) {
            var seen=new AtomicReference<String>();
            var server=server(status,
                    "{\"error\":\"Sensitive "+SECRET+" buyer@example.com\"}",seen);
            try {
                var err=assertThrows(StoneChargeException.class,
                        ()->client(server).lookup("ch_abc12345"));
                assertEquals(status,err.upstreamStatus());
                String expected=status==401 || status==403 ? "STONE_CREDENTIALS_REJECTED"
                        : status==404 ? "STONE_CHARGE_NOT_FOUND"
                        : status==429 ? "STONE_RATE_LIMIT" : "STONE_UPSTREAM_ERROR";
                assertEquals(expected,err.code());
                assertFalse(err.getMessage().contains(SECRET));
                assertFalse(err.getMessage().contains("buyer@example.com"));
            } finally {server.stop(0);}
        }
    }

    @Test void missingNumericFieldsAreUnknownNotFinancialZero() throws Exception {
        var seen=new AtomicReference<String>();
        var server=server(200,"""
            {"id":"ch_abc12345","status":"pending","currency":"BRL"}
            """,seen);
        try {
            var signal=client(server).lookup("ch_abc12345");
            assertFalse(signal.amountPresent());
            assertFalse(signal.paidAmountPresent());
            assertFalse(signal.amountFormatValid());
            assertFalse(signal.paidAmountFormatValid());
            assertEquals("PENDING",signal.status());
        } finally {server.stop(0);}
    }
    @Test void rejectsRedirectsInsteadOfFollowingProviderToAnotherHost() throws Exception {
        var seen=new AtomicReference<String>();
        var server=server(302,
                "{\"id\":\"ch_abc12345\",\"status\":\"paid\"}",seen);
        try {
            var error=assertThrows(StoneChargeException.class,
                    ()->client(server).lookup("ch_abc12345"));
            assertEquals("STONE_REDIRECT_REJECTED",error.code());
            assertEquals(502,error.status().value());
        } finally {server.stop(0);}
    }

}
