package br.com.adpilot.integrations.nuvemshop;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class NuvemshopCorrelationFinancialProjectionTest {

    @Test void threeFieldsWorkAcrossListPagesAndDetailWithoutShippingOrPrivateData()
            throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var observed=new CopyOnWriteArrayList<String>();
        server.createContext("/",exchange->{
            String uri=exchange.getRequestURI().toString();
            observed.add(uri);
            String query=URLDecoder.decode(exchange.getRequestURI().getRawQuery(),
                    StandardCharsets.UTF_8);
            String raw=uri+query;
            if(raw.contains("shipping_cost_customer")) {
                exchange.sendResponseHeaders(404,-1);
                exchange.close();
                return;
            }
            assertEquals("GET",exchange.getRequestMethod());
            assertEquals("Bearer fake-private-token",
                    exchange.getRequestHeaders().getFirst("Authorization"));
            boolean detail=exchange.getRequestURI().getPath().endsWith("/orders/9001");
            boolean page2=query.contains("page=2");
            String response=detail?"""
                    {"id":9001,"total":"95.00","currency":"BRL",
                     "subtotal":"100.00","discount":"5.00","discount_gateway":"5.00",
                     "customer":{"email":"private@example.com"},
                     "shipping_cost_customer":"PRIVATE_SHIPPING"}
                    """:page2?"""
                    [{"id":9002,"number":159,"payment_status":"paid","total":"100.00",
                      "currency":"BRL","created_at":"2026-10-20T12:00:00+0000",
                      "subtotal":"100.00","discount":"0.00","discount_gateway":"0.00",
                      "payment_details":{"method":"pix","card":"PRIVATE_CARD"}}]
                    """:"""
                    [{"id":9001,"number":158,"payment_status":"paid","total":"95.00",
                      "currency":"BRL","created_at":"2026-10-20T12:00:00+0000",
                      "subtotal":"100.00","discount":"5.00","discount_gateway":"5.00",
                      "customer":{"email":"private@example.com"},
                      "shipping_cost_customer":"PRIVATE_SHIPPING",
                      "payment_details":{"method":"pix","card":"PRIVATE_CARD"}}]
                    """;
            exchange.getResponseHeaders().set("Content-Type","application/json");
            if(!detail) {
                exchange.getResponseHeaders().set("x-total-count","2");
                if(!page2) exchange.getResponseHeaders().set("Link",
                        "</2025-03/123/orders?page=2>; rel=\"next\"");
            }
            byte[] bytes=response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,bytes.length);
            try(var out=exchange.getResponseBody()){out.write(bytes);}
        });
        server.start();
        try {
            var settings=mock(NuvemshopSettings.class);
            when(settings.apiVersion()).thenReturn("2025-03");
            when(settings.apiBase()).thenReturn("http://127.0.0.1:"+server.getAddress().getPort());
            when(settings.userAgent()).thenReturn("AdPilot test");
            var repository=mock(NuvemshopRepository.class);
            when(repository.installation()).thenReturn(Optional.of(
                    new NuvemshopRepository.Installation("123","read_orders",
                            new byte[]{1,2},Instant.EPOCH)));
            var crypto=mock(NuvemshopCrypto.class);
            when(crypto.decrypt(any(byte[].class))).thenReturn("fake-private-token");
            var client=new NuvemshopClient(settings,repository,crypto,RestClient.builder());

            var first=client.orderCrossProviderCandidates(1,1);
            var second=client.orderCrossProviderCandidates(2,1);
            assertTrue(first.hasNext());
            assertFalse(second.hasNext());
            assertEquals(2L,second.totalCount());
            assertEquals("5.00",first.data().getFirst().get("discount"));
            assertEquals("0.00",second.data().getFirst().get("discount"));
            assertFalse(first.data().getFirst().containsKey("shipping_cost_customer"));
            assertEquals("pix",
                    ((Map<?,?>)first.data().getFirst().get("payment_details")).get("method"));
            var detail=client.orderCorrelationFinancialDetails(9001L);
            assertEquals("5.00",detail.get("discount_gateway"));
            assertEquals("95.00",detail.get("total"));
            assertFalse(detail.containsKey("shipping_cost_customer"));
            assertFalse(detail.toString().contains("private@example.com"));
            assertFalse(detail.toString().contains("PRIVATE_SHIPPING"));
            assertFalse(detail.toString().contains("PRIVATE_CARD"));
            assertEquals(3,observed.size());
            for(String uri:observed) {
                String decoded=URLDecoder.decode(uri,StandardCharsets.UTF_8);
                assertTrue(decoded.contains("subtotal"));
                assertTrue(decoded.contains("discount"));
                assertTrue(decoded.contains("discount_gateway"));
                assertFalse(decoded.contains("shipping_cost_customer"));
                assertFalse(decoded.contains("customer"));
                assertFalse(decoded.contains("card"));
                assertFalse(decoded.contains("fake-private-token"));
            }
        } finally {server.stop(0);}
    }

    @Test void shippingIsNotAnAllowedIndependentFinancialProbe() {
        var client=new NuvemshopClient(mock(NuvemshopSettings.class),
                mock(NuvemshopRepository.class),mock(NuvemshopCrypto.class),
                RestClient.builder());
        assertThrows(IllegalArgumentException.class,
                ()->client.probeCorrelationListField("shipping_cost_customer"));
    }
}
