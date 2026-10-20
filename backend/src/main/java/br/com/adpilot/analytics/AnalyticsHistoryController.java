package br.com.adpilot.analytics;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Historical snapshots only; never calls Meta or Nuvemshop. All reads are workspace-scoped.
 * Snapshot coverage is not equivalent to a fully reconciled financial ledger.
 */
@RestController
@RequestMapping("/api/v1/analytics/history")
public class AnalyticsHistoryController {
    private final JdbcTemplate jdbc;

    public AnalyticsHistoryController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record MetaDay(LocalDate date, String currency, BigDecimal spend,
            long impressions, long clicks, BigDecimal attributedPurchases,
            BigDecimal attributedRevenue) {}
    public record StoreDay(LocalDate date, String currency, long paidOrders,
            BigDecimal paidRevenue) {}
    public record SourceState(String provider, String resource, String lastStatus,
            Instant lastSuccess, Instant cursorAt) {}
    public record History(LocalDate since, LocalDate until, List<MetaDay> meta,
            List<StoreDay> store, List<SourceState> sources, boolean complete,
            List<String> warnings) {}

    @GetMapping
    public ResponseEntity<History> history(@RequestParam LocalDate since,
            @RequestParam LocalDate until) {
        if (since.isAfter(until) || ChronoUnit.DAYS.between(since, until) >= 90
                || until.isAfter(LocalDate.now(ZoneOffset.UTC))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Informe um periodo valido de ate 90 dias, sem datas futuras.");
        }

        List<MetaDay> meta = jdbc.query("""
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

        // Only paid, non-cancelled orders with verified payment instants.
        // Grouped by UTC payment date (Meta dates use the account timezone).
        List<StoreDay> store = jdbc.query("""
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
                java.sql.Timestamp.from(since.atStartOfDay(ZoneOffset.UTC).toInstant()),
                java.sql.Timestamp.from(until.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant()));

        List<SourceState> sources = jdbc.query("""
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

        // A successful last run does not establish full coverage for arbitrary dates.
        // A missing day may mean zero activity or no ingestion; never guess.
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new History(since, until, meta, store, sources, false,
                        List.of("HISTORICAL_COVERAGE_NOT_VERIFIED",
                                "META_ACCOUNT_TIMEZONE_VS_STORE_UTC",
                                "ORDER_SNAPSHOTS_NOT_EVENT_LEDGER")));
    }
}
