package br.com.adpilot.analytics;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import br.com.adpilot.integrations.meta.MetaSettings;
import br.com.adpilot.integrations.nuvemshop.NuvemshopAuthService;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class AnalyticsSyncServiceTest {
    private final MetaSettings meta = new MetaSettings(
            "v26.0","123456","dummy","https://graph.facebook.com");

    @Test void disabledMeansNoExternalReadsOrWrites() {
        var store=mock(AnalyticsSyncStore.class);
        var collector=mock(AnalyticsCollector.class);
        var auth=mock(NuvemshopAuthService.class);
        var service=new AnalyticsSyncService(store,collector,meta,auth,false);
        assertThrows(ResponseStatusException.class,service::run);
        verifyNoInteractions(store,collector,auth);
    }

    @Test void failedShopDoesNotAdvanceCheckpointOrBlockMetaIngestion() {
        var store=mock(AnalyticsSyncStore.class);
        var collector=mock(AnalyticsCollector.class);
        var auth=mock(NuvemshopAuthService.class);
        when(auth.status()).thenReturn(new NuvemshopAuthService.Status("CONNECTED",true,"1","read_orders",Instant.now()));
        when(store.begin("NUVEMSHOP","ORDERS")).thenReturn(1L);
        when(store.begin("META_ADS","INSIGHTS_DAILY")).thenReturn(2L);
        when(store.begin("META_ADS","CAMPAIGNS")).thenReturn(3L);
        when(store.cursor("NUVEMSHOP","ORDERS")).thenReturn(Instant.parse("2026-10-20T00:00:00Z"));
        when(collector.orders(any(),any())).thenThrow(new IllegalStateException("NO_PII_IN_LOGS"));
        when(collector.campaigns(any())).thenReturn(
                new AnalyticsCollector.CampaignWindow(List.of(),0,"123456",Instant.now()));
        when(collector.insights(any())).thenReturn(
                new AnalyticsCollector.MetaWindow(List.of(),0,"123456",
                        LocalDate.of(2026,10,1),LocalDate.of(2026,10,19)));
        var service=new AnalyticsSyncService(store,collector,meta,auth,true);
        var report=service.run();
        assertEquals("FAILED",report.results().get(0).status());
        assertEquals("ORDER_COLLECTION_FAILED",report.results().get(0).code());
        assertEquals("SUCCEEDED",report.results().get(1).status());
        assertEquals("CAMPAIGNS",report.results().get(1).resource());
        assertEquals("SUCCEEDED",report.results().get(2).status());
        assertEquals("INSIGHTS_DAILY",report.results().get(2).resource());
        verify(store).failed(1L,"ORDER_COLLECTION_FAILED");
        verify(store,never()).saveOrders(anyLong(),anyList(),any(),anyInt());
        verify(store).saveCampaigns(eq(3L),anyList(),any(),eq(0));
        verify(store).saveMeta(eq(2L),anyList(),eq("123456"),any(),any(),any(),eq(0));
    }

    @Test void nonConnectedShopSkippedButMetaStillRuns() {
        var store=mock(AnalyticsSyncStore.class);
        var collector=mock(AnalyticsCollector.class);
        var auth=mock(NuvemshopAuthService.class);
        when(auth.status()).thenReturn(new NuvemshopAuthService.Status("NOT_CONNECTED",true,null,null,null));
        when(collector.campaigns(any())).thenReturn(
                new AnalyticsCollector.CampaignWindow(List.of(),0,"123456",Instant.now()));
        when(collector.insights(any())).thenReturn(
                new AnalyticsCollector.MetaWindow(List.of(),0,"123456",
                        LocalDate.of(2026,10,1),LocalDate.of(2026,10,19)));
        var service=new AnalyticsSyncService(store,collector,meta,auth,true);
        var report=service.run();
        assertEquals("SKIPPED",report.results().get(0).status());
        assertEquals("SUCCEEDED",report.results().get(1).status());
        assertEquals("CAMPAIGNS",report.results().get(1).resource());
        assertEquals("SUCCEEDED",report.results().get(2).status());
        assertEquals("INSIGHTS_DAILY",report.results().get(2).resource());
        verify(store,never()).saveOrders(anyLong(),anyList(),any(),anyInt());
    }
    @Test void providerHttpFailureIsCategorizedWithoutLeakingProviderPayload() {
        var store=mock(AnalyticsSyncStore.class);
        var collector=mock(AnalyticsCollector.class);
        var auth=mock(NuvemshopAuthService.class);
        when(auth.status()).thenReturn(new NuvemshopAuthService.Status(
                "CONNECTED",true,"1","read_orders",Instant.now()));
        when(store.begin("NUVEMSHOP","ORDERS")).thenReturn(9L);
        when(store.cursor("NUVEMSHOP","ORDERS")).thenReturn(Instant.parse("2026-10-20T00:00:00Z"));
        when(collector.orders(any(),any())).thenThrow(
                new br.com.adpilot.integrations.nuvemshop.NuvemshopException(
                        org.springframework.http.HttpStatus.BAD_GATEWAY,
                        "NUVEMSHOP_UPSTREAM_ERROR",
                        "potentially-sensitive-upstream-message", 422));
        when(collector.campaigns(any())).thenReturn(new AnalyticsCollector.CampaignWindow(
                List.of(),0,"123456",Instant.now()));
        when(collector.insights(any())).thenReturn(new AnalyticsCollector.MetaWindow(
                List.of(),0,"123456",LocalDate.of(2026,10,1),LocalDate.of(2026,10,19)));

        var report = new AnalyticsSyncService(store,collector,meta,auth,true).run();

        assertEquals("ORDER_API_HTTP_422",report.results().getFirst().code());
        verify(store).failed(9L,"ORDER_API_HTTP_422");
        verify(store,never()).saveOrders(anyLong(),anyList(),any(),anyInt());
        assertFalse(report.toString().contains("potentially-sensitive-upstream-message"));
    }

    @Test void databaseFailureIsSeparatedFromCollectionAndCannotReportSuccess() {
        var store=mock(AnalyticsSyncStore.class);
        var collector=mock(AnalyticsCollector.class);
        var auth=mock(NuvemshopAuthService.class);
        when(auth.status()).thenReturn(new NuvemshopAuthService.Status(
                "CONNECTED",true,"1","read_orders",Instant.now()));
        when(store.begin("NUVEMSHOP","ORDERS")).thenReturn(11L);
        when(store.cursor("NUVEMSHOP","ORDERS")).thenReturn(Instant.parse("2026-10-20T00:00:00Z"));
        var upper=Instant.parse("2026-10-20T12:00:00Z");
        when(collector.orders(any(),any())).thenReturn(new AnalyticsCollector.OrderWindow(
                List.of(),0,upper.minusSeconds(600),upper));
        doThrow(new org.springframework.dao.DataIntegrityViolationException("sensitive-db-detail"))
                .when(store).saveOrders(eq(11L),anyList(),eq(upper),eq(0));
        when(collector.campaigns(any())).thenReturn(new AnalyticsCollector.CampaignWindow(
                List.of(),0,"123456",Instant.now()));
        when(collector.insights(any())).thenReturn(new AnalyticsCollector.MetaWindow(
                List.of(),0,"123456",LocalDate.of(2026,10,1),LocalDate.of(2026,10,19)));

        var report=new AnalyticsSyncService(store,collector,meta,auth,true).run();

        assertEquals("ORDER_DB_WRITE_FAILED",report.results().getFirst().code());
        verify(store).failed(11L,"ORDER_DB_WRITE_FAILED");
        assertFalse(report.toString().contains("sensitive-db-detail"));
    }

}
