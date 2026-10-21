package br.com.adpilot.analytics;

import br.com.adpilot.analytics.AnalyticsHistoryModels.MetaDay;
import br.com.adpilot.analytics.AnalyticsHistoryModels.SourceState;
import br.com.adpilot.analytics.AnalyticsHistoryModels.StoreDay;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** All historical SQL stays in this data access layer, workspace-scoped. */
@Repository
public class AnalyticsHistoryRepository {
    private final JdbcTemplate jdbc;

    public AnalyticsHistoryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public List<MetaDay> metaDaily(LocalDate since, LocalDate until) {
        return jdbc.query("""
                SELECT insight_day, currency, SUM(spend) spend,
                       SUM(impressions) impressions, SUM(clicks) clicks,
                       SUM(attributed_purchases) purchases,
                       SUM(attributed_revenue) revenue
                FROM analytics_meta_insight_daily
                WHERE workspace_id=? AND insight_day BETWEEN ? AND ?
                GROUP BY insight_day,currency ORDER BY insight_day,currency
                """, (rs, n) -> new MetaDay(rs.getObject("insight_day", LocalDate.class),
                rs.getString("currency"), rs.getBigDecimal("spend"),
                rs.getLong("impressions"), rs.getLong("clicks"),
                rs.getBigDecimal("purchases"), rs.getBigDecimal("revenue")),
                AnalyticsSyncStore.WORKSPACE, since, until);
    }

    @Transactional(readOnly = true)
    public List<StoreDay> paidDaily(LocalDate since, LocalDate until) {
        // Eligible paid sales only, grouped by UTC payment date, never creation date.
        return jdbc.query("""
                SELECT (paid_at AT TIME ZONE 'UTC')::date AS paid_day,
                       currency, COUNT(*) AS paid_orders, SUM(total) AS revenue
                FROM analytics_order_snapshot
                WHERE workspace_id=? AND eligible_paid_sale=TRUE
                  AND paid_at >= ? AND paid_at < ?
                GROUP BY paid_day,currency ORDER BY paid_day,currency
                """, (rs, n) -> new StoreDay(rs.getObject("paid_day", LocalDate.class),
                rs.getString("currency"), rs.getLong("paid_orders"),
                rs.getBigDecimal("revenue")),
                AnalyticsSyncStore.WORKSPACE,
                Timestamp.from(since.atStartOfDay(ZoneOffset.UTC).toInstant()),
                Timestamp.from(until.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant()));
    }

    @Transactional(readOnly = true)
    public List<SourceState> syncStates() {
        return jdbc.query("""
                SELECT s.provider,s.resource,s.last_completed_at,s.cursor_at,
                       (SELECT r.status FROM analytics_sync_run r
                        WHERE r.workspace_id=s.workspace_id AND r.provider=s.provider
                          AND r.resource=s.resource
                        ORDER BY r.started_at DESC,r.id DESC LIMIT 1) last_status
                FROM analytics_sync_state s
                WHERE s.workspace_id=?
                ORDER BY s.provider,s.resource
                """, (rs, n) -> new SourceState(rs.getString("provider"),
                rs.getString("resource"), rs.getString("last_status"),
                rs.getTimestamp("last_completed_at")==null ? null :
                        rs.getTimestamp("last_completed_at").toInstant(),
                rs.getTimestamp("cursor_at")==null ? null :
                        rs.getTimestamp("cursor_at").toInstant()),
                AnalyticsSyncStore.WORKSPACE);
    }
}
