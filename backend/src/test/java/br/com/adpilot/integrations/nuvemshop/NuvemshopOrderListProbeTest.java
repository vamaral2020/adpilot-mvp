package br.com.adpilot.integrations.nuvemshop;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class NuvemshopOrderListProbeTest {
    @Test void usesSameOrderListPathAndFiltersButNeverReturnsProviderBodies() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var seen=new CopyOnWriteArrayList<String>();
        server.createContext("/",exchange->{
            seen.add(exchange.getRequestURI().toString());
            // Distinguish exactly the requests with both updated-at filters.
            var query=exchange.getRequestURI().getRawQuery();
            int status=query.contains("updated_at_min") && query.contains("updated_at_max")
                    ? 404 : 200;
            exchange.getResponseHeaders().set("Content-Type","application/json");
            byte[] body="{\"contact_email\":\"secret@example.com\",\"token\":\"secret\"}".getBytes();
            exchange.sendResponseHeaders(status,body.length);
            try(var out=exchange.getResponseBody()){out.write(body);}
        });
        server.start();
        try {
            var settings=mock(NuvemshopSettings.class);
            when(settings.apiVersion()).thenReturn("2025-03");
            when(settings.apiBase()).thenReturn("http://127.0.0.1:"+server.getAddress().getPort());
            when(settings.userAgent()).thenReturn("AdPilot diagnostic test");
            var repository=mock(NuvemshopRepository.class);
            when(repository.installation()).thenReturn(Optional.of(
                    new NuvemshopRepository.Installation("123","read_orders",
                            new byte[]{1,2},Instant.EPOCH)));
            var crypto=mock(NuvemshopCrypto.class);
            when(crypto.decrypt(any(byte[].class))).thenReturn("mock-token-not-for-real-use");
            var client=new NuvemshopClient(settings,repository,crypto,RestClient.builder());

            var results=client.probeOrderList(
                    Instant.parse("2026-10-22T21:00:16.934565Z"),
                    Instant.parse("2026-10-22T21:15:16.934565Z"));

            assertEquals(7,results.size());
            assertEquals(List.of("BASELINE_SAME_FIELDS","RANGE_EXACT","LOWER_ONLY",
                    "UPPER_ONLY","RANGE_SECONDS","RANGE_MINIMAL_FIELDS",
                    "RANGE_PER_PAGE_ONE"),
                    results.stream().map(NuvemshopClient.OrderListProbe::scenario).toList());
            assertEquals(List.of(200,404,200,200,404,404,404),
                    results.stream().map(NuvemshopClient.OrderListProbe::httpStatus).toList());
            assertTrue(results.stream().allMatch(r->"HTTP_STATUS".equals(r.outcome())));
            String all=results.toString();
            assertFalse(all.contains("contact_email"));
            assertFalse(all.contains("secret@example.com"));
            assertFalse(all.contains("mock-token"));
            assertFalse(all.contains("123/"));
            assertEquals(7,seen.size());
            assertTrue(seen.stream().allMatch(uri->uri.startsWith("/2025-03/123/orders?")));
            assertTrue(seen.get(1).contains("updated_at_min="));
            assertTrue(seen.get(1).contains("updated_at_max="));
            assertTrue(seen.get(1).contains("per_page=100"));
            assertTrue(seen.get(4).contains("2026-10-22T21%3A00%3A16Z"));
            assertTrue(seen.get(5).contains("fields=id%2Cupdated_at"));
            assertTrue(seen.get(6).contains("per_page=1"));
        } finally {
            server.stop(0);
        }
    }

    @Test void invalidWindowDoesNotLookUpCredentialsOrCallProvider() {
        var settings=mock(NuvemshopSettings.class);
        var repository=mock(NuvemshopRepository.class);
        var crypto=mock(NuvemshopCrypto.class);
        var client=new NuvemshopClient(settings,repository,crypto,RestClient.builder());
        var instant=Instant.parse("2026-10-22T21:00:00Z");
        assertThrows(NuvemshopException.class,
                ()->client.probeOrderList(instant,instant));
        verifyNoInteractions(repository,crypto);
    }

    @Test void followupSeparatesTimestampFailureFromAdvertisedPageTwoFailure() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var seen=new CopyOnWriteArrayList<String>();
        server.createContext("/",exchange->{
            String query=exchange.getRequestURI().getRawQuery();
            seen.add(query);
            boolean secondPage=query.contains("page=2&");
            boolean fractional=query.contains(".934565Z");
            int status=secondPage || !fractional ? 404 : 200;
            if (!secondPage && fractional) {
                exchange.getResponseHeaders().set("Link",
                        "<http://127.0.0.1/some-shop/orders?page=2>; rel=\"next\"");
                exchange.getResponseHeaders().set("x-total-count","115");
            }
            byte[] body="{\"email\":\"private@example.com\",\"token\":\"private\"}".getBytes();
            exchange.sendResponseHeaders(status,body.length);
            try(var out=exchange.getResponseBody()){out.write(body);}
        });
        server.start();
        try {
            var settings=mock(NuvemshopSettings.class);
            when(settings.apiVersion()).thenReturn("2025-03");
            when(settings.apiBase()).thenReturn("http://127.0.0.1:"+server.getAddress().getPort());
            when(settings.userAgent()).thenReturn("AdPilot followup test");
            var repository=mock(NuvemshopRepository.class);
            when(repository.installation()).thenReturn(Optional.of(
                    new NuvemshopRepository.Installation("123","read_orders",
                            new byte[]{1,2},Instant.EPOCH)));
            var crypto=mock(NuvemshopCrypto.class);
            when(crypto.decrypt(any(byte[].class))).thenReturn("mock-token");
            var client=new NuvemshopClient(settings,repository,crypto,RestClient.builder());

            var result=client.probeOrderListFollowup(
                    Instant.parse("2026-10-22T21:00:16.934565Z"),
                    Instant.parse("2026-10-22T21:15:16.934565Z"));

            assertTrue(result.nextPageAdvertised());
            assertTrue(result.totalExceedsPage());
            assertEquals(5,result.checks().size());
            assertEquals(List.of("EXACT_PAGE_1","SECONDS_PAGE_1",
                    "EXACT_PAGE_1_REPEAT","SECONDS_PAGE_1_REPEAT","EXACT_PAGE_2"),
                    result.checks().stream().map(NuvemshopClient.OrderListProbe::scenario).toList());
            assertEquals(List.of(200,404,200,404,404),
                    result.checks().stream().map(NuvemshopClient.OrderListProbe::httpStatus).toList());
            assertFalse(result.toString().contains("private@example.com"));
            assertFalse(result.toString().contains("mock-token"));
            assertFalse(result.toString().contains("some-shop"));
            assertEquals(5,seen.size());
            assertTrue(seen.get(4).contains("page=2&per_page=100"));
        } finally {
            server.stop(0);
        }
    }

    @Test void followupDoesNotProbeUnadvertisedSecondPage() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var seen=new CopyOnWriteArrayList<String>();
        server.createContext("/",exchange->{
            seen.add(exchange.getRequestURI().toString());
            byte[] body="[]".getBytes();
            exchange.sendResponseHeaders(200,body.length);
            try(var out=exchange.getResponseBody()){out.write(body);}
        });
        server.start();
        try {
            var settings=mock(NuvemshopSettings.class);
            when(settings.apiVersion()).thenReturn("2025-03");
            when(settings.apiBase()).thenReturn("http://127.0.0.1:"+server.getAddress().getPort());
            when(settings.userAgent()).thenReturn("AdPilot followup test");
            var repository=mock(NuvemshopRepository.class);
            when(repository.installation()).thenReturn(Optional.of(
                    new NuvemshopRepository.Installation("123","read_orders",
                            new byte[]{1,2},Instant.EPOCH)));
            var crypto=mock(NuvemshopCrypto.class);
            when(crypto.decrypt(any(byte[].class))).thenReturn("mock-token");
            var client=new NuvemshopClient(settings,repository,crypto,RestClient.builder());
            var result=client.probeOrderListFollowup(
                    Instant.parse("2026-10-22T21:00:16.934565Z"),
                    Instant.parse("2026-10-22T21:15:16.934565Z"));
            assertFalse(result.nextPageAdvertised());
            assertFalse(result.totalExceedsPage());
            assertEquals(4,result.checks().size());
            assertEquals(4,seen.size());
            assertTrue(seen.stream().allMatch(uri->uri.contains("page=1&")));
        } finally {
            server.stop(0);
        }
    }

}
