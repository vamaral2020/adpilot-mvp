package br.com.adpilot.analytics;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import br.com.adpilot.integrations.meta.MetaAdsClient;
import br.com.adpilot.integrations.meta.MetaPage;
import br.com.adpilot.integrations.meta.MetaSettings;
import br.com.adpilot.integrations.nuvemshop.NuvemshopClient;
import br.com.adpilot.integrations.nuvemshop.NuvemshopPage;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AnalyticsCollectorTest {
    private final Instant now = Instant.parse("2026-10-20T12:00:00Z");
    private NuvemshopClient shop;
    private MetaAdsClient meta;
    private AnalyticsCollector collector;

    @BeforeEach void setup() {
        shop = mock(NuvemshopClient.class);
        meta = mock(MetaAdsClient.class);
        collector = new AnalyticsCollector(shop,meta,
                new MetaSettings("v26.0","123456","dummy","https://graph.facebook.com"));
    }

    private static Map<String,Object> order(int id, String status, String payment,
            String paidAt, String updatedAt) {
        var row = new HashMap<String,Object>();
        row.put("id",id); row.put("number",id + 100);
        row.put("status",status); row.put("payment_status",payment);
        row.put("created_at","2026-10-18T10:00:00+00:00");
        row.put("updated_at",updatedAt); row.put("paid_at",paidAt);
        row.put("total","201.05"); row.put("currency","BRL");
        return row;
    }
    private static NuvemshopPage page(List<Map<String,Object>> rows, boolean hasNext) {
        return new NuvemshopPage(rows,1,100,null,hasNext);
    }

    @Test void initialOrderBackfillRecoversPaymentButIgnoresCancelledAndNoPii() {
        var rows = List.of(
                order(10,"open","paid",null,"2026-10-18T11:00:00+00:00"),
                order(11,"cancelled","paid",null,"2026-10-18T11:00:00+00:00"),
                order(12,"open","pending",null,"2026-10-18T11:00:00+00:00"));
        when(shop.ordersUpdatedBetween(any(),any(),eq(1),eq(100))).thenReturn(page(rows,false));
        when(shop.paidOrderDetails(10)).thenReturn(Map.of("id",10,"number",110,
                "status","open","payment_status","paid","paid_at","2026-10-18T10:30:00+00:00",
                "total","201.05","currency","BRL"));
        var result = collector.orders(null, now);
        assertEquals(3,result.fetched());
        assertEquals(3,result.rows().size());
        assertEquals("DETAIL",result.rows().getFirst().paidDateSource());
        assertTrue(result.rows().getFirst().eligibleSale());
        assertEquals(Instant.parse("2026-10-18T10:30:00Z"),result.rows().getFirst().paidAt());
        assertFalse(result.rows().get(1).eligibleSale());
        assertFalse(result.rows().get(2).eligibleSale());
        assertEquals(now.minusSeconds(300),result.upper());
        assertEquals(now.minusSeconds(300 + 30L*86400),result.lower());
        verify(shop,times(1)).paidOrderDetails(anyLong());
        assertFalse(result.toString().contains("contact_email"));
    }

    @Test void orderIncrementalUsesCheckpointOverlap() {
        when(shop.ordersUpdatedBetween(any(),any(),eq(1),eq(100))).thenReturn(page(List.of(),false));
        var cursor = now.minusSeconds(3600);
        var result = collector.orders(cursor,now);
        assertEquals(cursor.minusSeconds(600),result.lower());
        verify(shop).ordersUpdatedBetween(eq(cursor.minusSeconds(600)),
                eq(now.minusSeconds(300)),eq(1),eq(100));
    }

    @Test void missingDetailMustNotProduceCompleteOrderWindow() {
        when(shop.ordersUpdatedBetween(any(),any(),eq(1),eq(100))).thenReturn(page(
                List.of(order(10,"open","paid",null,"2026-10-18T11:00:00+00:00")),false));
        when(shop.paidOrderDetails(10)).thenReturn(Map.of("id",10,"number",110,"status","open",
                "payment_status","paid","total","201.05","currency","BRL"));
        assertEquals("ORDER_PAID_AT_UNKNOWN",
                assertThrows(IllegalStateException.class,()->collector.orders(null,now)).getMessage());
    }

    @Test void inconsistentDetailMustFailWithoutFallingBackToCreatedAt() {
        when(shop.ordersUpdatedBetween(any(),any(),eq(1),eq(100))).thenReturn(page(
                List.of(order(10,"open","paid",null,"2026-10-18T11:00:00+00:00")),false));
        when(shop.paidOrderDetails(10)).thenReturn(Map.of("id",10,"number",110,
                "status","cancelled","payment_status","paid","total","201.05",
                "currency","BRL","paid_at","2026-10-18T10:30:00+00:00"));
        assertEquals("ORDER_DETAIL_MISMATCH",
                assertThrows(IllegalStateException.class,()->collector.orders(null,now)).getMessage());
    }

    @Test void partialOrderPageMustFailAndNeverProduceSnapshot() {
        when(shop.ordersUpdatedBetween(any(),any(),eq(1),eq(100)))
                .thenReturn(page(List.of(),true));
        assertEquals("INVALID_SHOP_PAGINATION",
                assertThrows(IllegalStateException.class,()->collector.orders(null,now)).getMessage());
    }

    @Test void metaRefreshesThirtyCompleteDaysAndPreservesPurchasePrecedence() {
        when(meta.account()).thenReturn(Map.of("currency","BRL"));
        var insight = new HashMap<String,Object>();
        insight.put("campaign_id","123");insight.put("date_start","2026-10-18");
        insight.put("date_stop","2026-10-18");insight.put("spend","130.88");
        insight.put("impressions","8239");insight.put("clicks","330");
        insight.put("actions",List.of(Map.of("action_type","purchase","value","1"),
                Map.of("action_type","omni_purchase","value","1")));
        insight.put("action_values",List.of(Map.of("action_type","purchase","value","201.05"),
                Map.of("action_type","omni_purchase","value","201.05")));
        when(meta.insights(any(),any(),eq(100),isNull())).thenReturn(new MetaPage(
                List.of(insight),null));
        var result=collector.insights(now);
        assertEquals(LocalDate.of(2026,9,20),result.from());
        assertEquals(LocalDate.of(2026,10,19),result.through());
        assertEquals(new BigDecimal("1"),result.rows().getFirst().purchases());
        assertEquals(new BigDecimal("201.05"),result.rows().getFirst().attributedRevenue());
        assertEquals(new BigDecimal("130.88"),result.rows().getFirst().spend());
        assertEquals("BRL",result.rows().getFirst().currency());
    }

    @Test void repeatedMetaCursorFailsRatherThanPublishingIncompleteWindow() {
        when(meta.account()).thenReturn(Map.of("currency","BRL"));
        when(meta.insights(any(),any(),eq(100),isNull())).thenReturn(
                new MetaPage(List.of(),"repeat"));
        assertEquals("INVALID_META_PAGINATION",
                assertThrows(IllegalStateException.class,()->collector.insights(now)).getMessage());
    }
}
