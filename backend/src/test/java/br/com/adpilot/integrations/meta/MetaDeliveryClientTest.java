package br.com.adpilot.integrations.meta;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class MetaDeliveryClientTest {
    @Test void onlyGetAndAllowlistedParamsWithOwnershipAndSanitizedPromotedObject()
            throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var seen=new CopyOnWriteArrayList<String>();
        server.createContext("/",ex->{
            String uri=ex.getRequestURI().toString();
            seen.add(ex.getRequestMethod()+"|"+uri+"|"+
                    ex.getRequestHeaders().getFirst("Authorization"));
            String path=ex.getRequestURI().getPath();
            String body=path.endsWith("/adsets")?"""
                    {"data":[{"id":"111","campaign_id":"123456","name":"Teste",
                     "optimization_goal":"OFFSITE_CONVERSIONS",
                     "promoted_object":{"custom_event_type":"PURCHASE",
                     "pixel_id":"PRIVATE-PIXEL","custom_event_id":"PRIVATE-EVENT"},
                     "targeting":{"interests":[{"id":"PRIVATE-AUDIENCE"}]}}]}
                    """:path.endsWith("/insights")?"""
                    {"data":[{"campaign_id":"123456","adset_id":"111",
                     "ad_id":"999","spend":"10.00","impressions":"100",
                     "clicks":"20","inline_link_clicks":"5",
                     "actions":[{"action_type":"purchase","value":"1"}],
                     "secret":"PRIVATE-CUSTOMER"}]}
                    """:"""
                    {"id":"123456","account_id":"act_1234567",
                     "objective":"OUTCOME_SALES","effective_status":"ACTIVE",
                     "access_token":"PRIVATE-TOKEN"}
                    """;
            byte[] bytes=body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type","application/json");
            ex.sendResponseHeaders(200,bytes.length);
            try(var out=ex.getResponseBody()){out.write(bytes);}
        });
        server.start();
        try {
            var settings=new MetaSettings("v26.0","1234567","secret-for-test",
                    "http://127.0.0.1:"+server.getAddress().getPort());
            var client=new MetaAdsClient(RestClient.builder(),settings);
            var campaign=client.deliveryCampaign("123456");
            assertEquals("OUTCOME_SALES",campaign.get("objective"));
            assertFalse(campaign.containsKey("account_id"));
            var sets=client.deliveryAdsets("123456",100,null);
            assertEquals("PURCHASE",sets.data().getFirst().get("promoted_event"));
            assertFalse(sets.data().getFirst().containsKey("promoted_object"));
            var days=client.deliveryInsights("123456","ad",
                    LocalDate.of(2026,10,1),LocalDate.of(2026,10,7),100,null);
            assertEquals("5",days.data().getFirst().get("inline_link_clicks"));
            String safe=sets.toString()+days.toString()+campaign.toString();
            assertFalse(safe.contains("PRIVATE-PIXEL"));
            assertFalse(safe.contains("PRIVATE-AUDIENCE"));
            assertFalse(safe.contains("PRIVATE-TOKEN"));
            assertFalse(safe.contains("PRIVATE-CUSTOMER"));
            assertEquals(3,seen.size());
            assertTrue(seen.stream().allMatch(x->x.startsWith("GET|")));
            assertTrue(seen.stream().allMatch(x->x.endsWith("|Bearer secret-for-test")));
            assertTrue(seen.stream().noneMatch(x->x.contains("secret-for-test?")));
            String query=URLDecoder.decode(seen.get(2),StandardCharsets.UTF_8);
            assertTrue(query.contains("level=ad"),query);
            assertTrue(query.contains("inline_link_clicks"),query);
            assertTrue(query.contains("time_range={\"since\":\"2026-10-01\",\"until\":\"2026-10-07\"}"),query);
            assertFalse(query.contains("targeting"));
            assertFalse(query.contains("customer"));
        } finally {server.stop(0);}
    }

    @Test void accountOwnershipMismatchFailsBeforeAnyChildQuery() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var seen=new CopyOnWriteArrayList<String>();
        server.createContext("/",ex->{
            seen.add(ex.getRequestURI().toString());
            byte[] bytes="{\"id\":\"123456\",\"account_id\":\"act_9999999\"}".getBytes();
            ex.getResponseHeaders().set("Content-Type","application/json");
            ex.sendResponseHeaders(200,bytes.length);
            try(var out=ex.getResponseBody()){out.write(bytes);}
        });
        server.start();
        try {
            var client=new MetaAdsClient(RestClient.builder(),new MetaSettings(
                    "v26.0","1234567","secret","http://127.0.0.1:"+
                            server.getAddress().getPort()));
            assertEquals("CAMPAIGN_NOT_IN_ACCOUNT",assertThrows(MetaApiException.class,
                    ()->client.deliveryCampaign("123456")).errorCode());
            assertEquals(1,seen.size());
        } finally {server.stop(0);}
    }

    @Test void invalidLevelDateOrCursorAreRejectedBeforeMetaRequest() {
        var client=new MetaAdsClient(RestClient.builder(),
                new MetaSettings("v26.0","","","https://graph.facebook.com"));
        var from=LocalDate.of(2026,10,1);
        var to=LocalDate.of(2026,10,7);
        assertEquals("INVALID_INSIGHTS_LEVEL",assertThrows(MetaApiException.class,
                ()->client.deliveryInsights("123456","campaign",from,to,100,null))
                .errorCode());
        assertEquals("INVALID_DATE_RANGE",assertThrows(MetaApiException.class,
                ()->client.deliveryInsights("123456","ad",from,to.plusDays(40),100,null))
                .errorCode());
        assertEquals("INVALID_PAGINATION",assertThrows(MetaApiException.class,
                ()->client.deliveryInsights("123456","ad",from,to,101,null))
                .errorCode());
    }
}
