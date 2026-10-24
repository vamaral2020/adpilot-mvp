package br.com.adpilot.analytics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Parameterized, workspace-scoped lookup. Order IDs stay internal. */
@Repository
public class OrderSnapshotDiagnosticRepository {
    private final JdbcTemplate jdbc;

    public OrderSnapshotDiagnosticRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public Map<String, OrderSnapshotDiagnosticService.Snapshot> find(List<String> orderIds) {
        if (orderIds.isEmpty()) return Map.of();
        if (orderIds.size() > 100) throw new IllegalArgumentException("INVALID_BATCH_SIZE");

        String sql = """
                SELECT order_id, payment_status, paid_at
                FROM analytics_order_snapshot
                WHERE workspace_id=? AND order_id IN (""" +
                String.join(",", Collections.nCopies(orderIds.size(), "?")) + ")";
        var params = new ArrayList<Object>();
        params.add(AnalyticsSyncStore.WORKSPACE);
        params.addAll(orderIds);
        var found = jdbc.query(sql, (rs, row) ->
                new OrderSnapshotDiagnosticService.Snapshot(
                        rs.getString("order_id"),
                        rs.getString("payment_status"),
                        rs.getTimestamp("paid_at") == null ? null :
                                rs.getTimestamp("paid_at").toInstant()),
                params.toArray());
        Map<String, OrderSnapshotDiagnosticService.Snapshot> byId = new HashMap<>();
        for (var item : found) byId.put(item.id(), item);
        return byId;
    }
}
