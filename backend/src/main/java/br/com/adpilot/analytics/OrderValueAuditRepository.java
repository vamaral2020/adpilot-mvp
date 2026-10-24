package br.com.adpilot.analytics;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads existing local projections by a bounded batch of order IDs.
 * IDs and raw amounts are only used in-memory; workspace isolation is mandatory.
 */
@Repository
public class OrderValueAuditRepository {
    private final JdbcTemplate jdbc;

    public OrderValueAuditRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Snapshot(String id, String orderStatus, String paymentStatus,
            BigDecimal total, String currency, boolean eligiblePaidSale) {}

    @Transactional(readOnly = true)
    public Map<String, Snapshot> find(List<String> orderIds) {
        if (orderIds.isEmpty()) return Map.of();
        if (orderIds.size() > 100) throw new IllegalArgumentException("INVALID_BATCH_SIZE");
        String sql = """
                SELECT order_id,order_status,payment_status,total,currency,eligible_paid_sale
                FROM analytics_order_snapshot
                WHERE workspace_id=? AND order_id IN (""" +
                String.join(",",Collections.nCopies(orderIds.size(),"?")) + ")";
        var params = new ArrayList<Object>();
        params.add(AnalyticsSyncStore.WORKSPACE);
        params.addAll(orderIds);
        var found = jdbc.query(sql, (rs,n) -> new Snapshot(
                rs.getString("order_id"),rs.getString("order_status"),
                rs.getString("payment_status"),rs.getBigDecimal("total"),
                rs.getString("currency"),rs.getBoolean("eligible_paid_sale")),params.toArray());
        Map<String,Snapshot> byId=new HashMap<>();
        for(var row:found) byId.put(row.id(),row);
        return byId;
    }
}
