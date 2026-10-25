package br.com.adpilot.integrations.stone;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class StoneHistoryClientTest {
    private static final String SECRET="sk_test_nonreal_discovery_secret";

    private static HttpServer serve(int httpStatus,String orderBody,String chargeBody,
            AtomicReference<String> observed) throws Exception {
        var server=HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(),0),0);
        server.createContext("/core/v5/",ex-> {
            var path=ex.getRequestURI().toString();
            observed.set(ex.getRequestMethod()+"|"+path+"|"
                    +ex.getRequestHeaders().getFirst("Authorization"));
            byte[] data=(path.startsWith("/core/v5/orders")
                    ? orderBody:chargeBody).getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type","application/json");
            ex.sendResponseHeaders(httpStatus,data.length);
            try(var output=ex.getResponseBody()) {output.write(data);}
        });
        server.start();
        return server;
    }

    private static StoneHistoryClient client(HttpServer server) {
        return new StoneHistoryClient(SECRET,RestClient.builder(),
                URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/core/v5"));
    }

    @Test void pagesOrdersAndChargesWithoutExposingPrivateFieldsOrFollowingNextUrl()
            throws Exception {
        var observed=new AtomicReference<String>();
        var server=serve(200,"""
                {"data":[{"id":"or_abc12345","status":"paid","code":"ORDER-123",
                    "customer":{"email":"PRIVATE@example.com"},"amount":10000}],
                 "paging":{"total":2,"next":"https://attacker.example/leak"}}
                ""","""
                {"data":[{"id":"ch_abc12345","order_id":"or_abc12345",
                    "status":"paid","payment_method":"credit_card",
                    "amount":10000,"paid_amount":10000,
                    "customer":{"email":"PRIVATE@example.com"},
                    "last_transaction":{"card":{"number":"PRIVATE-CARD-NUMBER"}}}],
                 "paging":{"total":1,"next":null}}
                """,observed);
        try {
            var discovery=client(server);
            assertEquals("SANDBOX",discovery.keyEnvironment());
            var orders=discovery.orders(1,20);
            assertEquals(1,orders.data().size());
            assertEquals("PAID",orders.data().getFirst().status());
            assertTrue(orders.data().getFirst().codePresent());
            assertTrue(orders.hasNext());
            assertEquals(2L,orders.total());
            var auth="Basic "+Base64.getEncoder().encodeToString(
                    (SECRET+":").getBytes(StandardCharsets.UTF_8));
            assertEquals("GET|/core/v5/orders?page=1&size=20|"+auth,observed.get());
            var charges=discovery.charges(1,20);
            assertEquals(1,charges.data().size());
            assertEquals("or_abc12345",charges.data().getFirst().providerOrderId());
            assertEquals("CREDIT_CARD",charges.data().getFirst().method());
            assertEquals("GET|/core/v5/charges?page=1&size=20|"+auth,observed.get());
            String safe=orders.toString()+charges.toString();
            assertFalse(safe.contains("PRIVATE@example.com"));
            assertFalse(safe.contains("ORDER-123"));
            assertFalse(safe.contains("PRIVATE-CARD-NUMBER"));
            assertFalse(safe.contains("10000"));
            assertFalse(safe.contains("attacker.example"));
        } finally {server.stop(0);}
    }

    @Test void rejectsConflictingChargeParentIdsRatherThanMislinking() throws Exception {
        var seen=new AtomicReference<String>();
        var server=serve(200,"{}", """
                {"data":[{"id":"ch_abc12345","order_id":"or_abc12345",
                          "order":{"id":"or_different12"},"status":"paid"}],
                 "paging":{"total":1,"next":null}}
                """,seen);
        try {
            var e=assertThrows(StoneChargeException.class,
                    ()->client(server).charges(1,20));
            assertEquals("STONE_INVALID_COLLECTION_RESPONSE",e.code());
        } finally {server.stop(0);}
    }

    @Test void errorsAreSanitizedAndDoNotContainProviderBodyOrKey() throws Exception {
        for(int status:new int[]{401,403,404,429,500,302}) {
            var seen=new AtomicReference<String>();
            var server=serve(status,
                    "{\"error\":\""+SECRET+" private@example.com\"}",
                    "{}",seen);
            try {
                var e=assertThrows(StoneChargeException.class,
                        ()->client(server).orders(1,20));
                assertFalse(e.getMessage().contains(SECRET));
                assertFalse(e.getMessage().contains("private@example.com"));
                if(status==302)
                    assertEquals("STONE_REDIRECT_REJECTED",e.code());
                else if(status==401||status==403)
                    assertEquals("STONE_CREDENTIALS_REJECTED",e.code());
                else if(status==404)
                    assertEquals("STONE_COLLECTION_UNAVAILABLE",e.code());
                else if(status==429)
                    assertEquals("STONE_RATE_LIMIT",e.code());
                else
                    assertEquals("STONE_UPSTREAM_ERROR",e.code());
            } finally {server.stop(0);}
        }
    }

    @Test void missingSecretAndInvalidBoundsCannotFetch() {
        var noKey=new StoneHistoryClient("",RestClient.builder(),
                URI.create("http://127.0.0.1:1/core/v5"));
        assertEquals("STONE_NOT_CONFIGURED",assertThrows(StoneChargeException.class,
                ()->noKey.orders(1,20)).code());
        var withKey=new StoneHistoryClient(SECRET,RestClient.builder(),
                URI.create("http://127.0.0.1:1/core/v5"));
        for(int[] bad:new int[][]{{0,20},{11,20},{1,0},{1,51}}) {
            assertEquals("STONE_INVALID_PAGINATION",assertThrows(StoneChargeException.class,
                    ()->withKey.charges(bad[0],bad[1])).code());
        }
    }

    @Test void invalidRowsDoNotProducePartialReports() throws Exception {
        var seen=new AtomicReference<String>();
        var server=serve(200, """
                {"data":[{"id":"pii@example.com","status":"paid"}],
                 "paging":{"total":1,"next":null}}
                ""","{}",seen);
        try {
            var e=assertThrows(StoneChargeException.class,
                    ()->client(server).orders(1,20));
            assertEquals("STONE_INVALID_COLLECTION_RESPONSE",e.code());
            assertFalse(e.getMessage().contains("pii@example.com"));
        } finally {server.stop(0);}
    }
    @Test void correlationProjectsOnlyMinimalOrderEvidenceAndUsesGet() throws Exception {
        var observed=new AtomicReference<String>();
        var server=serve(200,"""
                {"data":[{"id":"or_abc12345","code":"158","status":"paid",
                   "amount":12999,"currency":"BRL",
                   "created_at":"2026-10-20T12:00:00Z",
                   "customer":{"email":"PRIVATE@example.com"},
                   "billing_address":{"street":"PRIVATE-STREET"},
                   "metadata":{"secret":"SECRET-METADATA"}}],
                 "paging":{"total":1,"next":null}}
                ""","{}",observed);
        try {
            var page=client(server).correlationOrders(1,20);
            assertEquals(1,page.data().size());
            var result=page.data().getFirst();
            assertEquals("or_abc12345",result.id());
            assertEquals("158",result.code());
            assertEquals("PAID",result.status());
            assertEquals("BRL",result.currency());
            assertEquals(12999L,result.amountCents());
            assertEquals(java.time.Instant.parse("2026-10-20T12:00:00Z"),
                    result.createdAt());
            assertFalse(page.toString().contains("PRIVATE@example.com"));
            assertFalse(page.toString().contains("PRIVATE-STREET"));
            assertFalse(page.toString().contains("SECRET-METADATA"));
            assertTrue(observed.get().startsWith("GET|/core/v5/orders?page=1&size=20|Basic "));
        } finally {server.stop(0);}
    }

    @Test void malformedMonetaryAndDateFieldsRemainUncomparableNotZero() throws Exception {
        var observed=new AtomicReference<String>();
        var server=serve(200,"""
                {"data":[{"id":"or_abc12345","code":"158","status":"paid",
                   "amount":"129.99","currency":"USD",
                   "created_at":"invalid-timestamp"}],
                 "paging":{"total":1,"next":null}}
                ""","{}",observed);
        try {
            var result=client(server).correlationOrders(1,20).data().getFirst();
            assertNull(result.amountCents());
            assertNull(result.createdAt());
            assertEquals("UNKNOWN",result.currency());
        } finally {server.stop(0);}
    }

    @Test void correlationDateEvidenceDistinguishesAbsentAndUnparseableWithoutRawDates()
            throws Exception {
        var seen=new AtomicReference<String>();
        var invalid=serve(200,"""
                {"data":[{"id":"or_abc12345","code":"158","status":"paid",
                   "currency":"BRL","amount":10000,
                   "created_at":"NOT-AN-ISO-DATE-PRIVATE"}],
                 "paging":{"total":1,"next":null}}
                ""","{}",seen);
        try {
            var result=client(invalid).correlationOrders(1,20).data().getFirst();
            assertNull(result.createdAt());
            assertEquals("UNPARSEABLE",result.createdAtFieldStatus());
            assertFalse(result.createdAtFieldStatus().contains("PRIVATE"));
        } finally {invalid.stop(0);}
        var absent=serve(200,"""
                {"data":[{"id":"or_abc12345","code":"158","status":"paid",
                   "currency":"BRL","amount":10000}],
                 "paging":{"total":1,"next":null}}
                ""","{}",seen);
        try {
            var result=client(absent).correlationOrders(1,20).data().getFirst();
            assertNull(result.createdAt());
            assertEquals("ABSENT_OR_NULL",result.createdAtFieldStatus());
        } finally {absent.stop(0);}
    }

    @Test void chargeDetailFetchesOnlyByValidatedIdentifierAndProjectsMoneyWithoutPii()
            throws Exception {
        var observed=new AtomicReference<String>();
        var server=serve(200,"{}", """
                {"id":"ch_abc12345","order_id":"or_abc12345",
                 "status":"paid","currency":"BRL","amount":10000,
                 "paid_amount":9500,"refunded_amount":0,
                 "customer":{"email":"PRIVATE@example.com"},
                 "last_transaction":{"card":{"number":"PRIVATE-CARD-NUMBER"}},
                 "split":[{"recipient_id":"SECRET-RECIPIENT"}]}
                """,observed);
        try {
            var detail=client(server).chargeFinancialDetail("ch_abc12345");
            assertEquals("ch_abc12345",detail.id());
            assertEquals("or_abc12345",detail.providerOrderId());
            assertEquals("PAID",detail.status());
            assertEquals("BRL",detail.currency());
            assertEquals(10000L,detail.amountCents());
            assertEquals(9500L,detail.paidAmountCents());
            assertEquals(0L,detail.refundedAmountCents());
            assertTrue(observed.get().startsWith(
                    "GET|/core/v5/charges/ch_abc12345|Basic "));
            String projected=detail.toString();
            assertFalse(projected.contains("PRIVATE@example.com"));
            assertFalse(projected.contains("PRIVATE-CARD-NUMBER"));
            assertFalse(projected.contains("SECRET-RECIPIENT"));
        } finally {server.stop(0);}
    }

    @Test void missingOrMalformedChargeAmountsDoNotTurnIntoZero() throws Exception {
        var observed=new AtomicReference<String>();
        var server=serve(200,"{}", """
                {"id":"ch_abc12345","order_id":"or_abc12345",
                 "status":"partial_refunded","currency":"BRL",
                 "amount":"10000","paid_amount":null,"refunded_amount":-1}
                """,observed);
        try {
            var detail=client(server).chargeFinancialDetail("ch_abc12345");
            assertEquals("PARTIAL_REFUNDED",detail.status());
            assertNull(detail.amountCents());
            assertNull(detail.paidAmountCents());
            assertNull(detail.refundedAmountCents());
        } finally {server.stop(0);}
    }

    @Test void chargeDetailsAreFailClosedOnWrongIdUnlinkedOrderAndBadReferences()
            throws Exception {
        var observed=new AtomicReference<String>();
        var invalid=serve(200,"{}", """
                {"id":"ch_different99","order_id":"or_abc12345","status":"paid"}
                """,observed);
        try {
            var err=assertThrows(StoneChargeException.class,
                    ()->client(invalid).chargeFinancialDetail("ch_abc12345"));
            assertEquals("STONE_INVALID_COLLECTION_RESPONSE",err.code());
        } finally {invalid.stop(0);}

        var noParent=serve(200,"{}", """
                {"id":"ch_abc12345","status":"paid","currency":"BRL",
                 "paid_amount":10000}
                """,observed);
        try {
            assertEquals("STONE_INVALID_COLLECTION_RESPONSE",
                    assertThrows(StoneChargeException.class,()->
                            client(noParent).chargeFinancialDetail("ch_abc12345")).code());
        } finally {noParent.stop(0);}

        var client=new StoneHistoryClient(SECRET,RestClient.builder(),
                URI.create("http://127.0.0.1:1/core/v5"));
        for(String unsafe:new String[]{"ch_../charges","https://example.com",
                "or_abc12345","ch_","ch_abc12345?expand=customer"}) {
            assertEquals("STONE_INVALID_CHARGE_REFERENCE",
                    assertThrows(StoneChargeException.class,
                            ()->client.chargeFinancialDetail(unsafe)).code());
        }
    }

    @Test void chargeDetailUpstreamErrorsDoNotLeakBodyOrKey() throws Exception {
        for(int status:new int[]{401,403,404,429,500,302}) {
            var seen=new AtomicReference<String>();
            var server=serve(status,"{}",
                    "{\"error\":\"PRIVATE@example.com "+SECRET+"\"}",seen);
            try {
                var e=assertThrows(StoneChargeException.class,()->
                        client(server).chargeFinancialDetail("ch_abc12345"));
                assertFalse(e.getMessage().contains("PRIVATE@example.com"));
                assertFalse(e.getMessage().contains(SECRET));
                assertEquals(status==302?"STONE_REDIRECT_REJECTED":
                        status==404?"STONE_CHARGE_NOT_FOUND":
                        status==429?"STONE_RATE_LIMIT":
                        status==401||status==403?"STONE_CREDENTIALS_REJECTED":
                        "STONE_UPSTREAM_ERROR",e.code());
            } finally {server.stop(0);}
        }
    }

}
