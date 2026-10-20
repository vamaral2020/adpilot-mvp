package br.com.adpilot.analytics;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class AnalyticsSyncStore {
    public static final UUID WORKSPACE = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private final JdbcTemplate jdbc;

    public AnalyticsSyncStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Instant cursor(String provider, String resource) {
        var dates = jdbc.query(
                "SELECT cursor_at FROM analytics_sync_state WHERE workspace_id=? AND provider=? AND resource=?",
                (rs, n) -> rs.getTimestamp(1) == null ? null : rs.getTimestamp(1).toInstant(),
                WORKSPACE, provider, resource);
        return dates.isEmpty() ? null : dates.getFirst();
    }

    public long begin(String provider, String resource) {
        return jdbc.queryForObject(
                "INSERT INTO analytics_sync_run (workspace_id,provider,resource,status) VALUES (?,?,?,'RUNNING') RETURNING id",
                Long.class, WORKSPACE, provider, resource);
    }

    public void failed(long runId, String statusCode) {
        jdbc.update("UPDATE analytics_sync_run SET status='FAILED', error_code=?, finished_at=now() WHERE id=? AND status='RUNNING'",
                statusCode, runId);
    }

    @Transactional
    public void saveOrders(long runId, List<AnalyticsRecords.Order> rows,
            Instant cursor, int fetched) {
        for (var row : rows) {
            jdbc.update("""
                    INSERT INTO analytics_order_snapshot
                    (workspace_id,order_id,order_number,order_status,payment_status,paid_at,
                     payment_date_source,created_at,source_updated_at,total,currency,eligible_paid_sale)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
                    ON CONFLICT (workspace_id,order_id) DO UPDATE SET
                    order_number=EXCLUDED.order_number,
                    order_status=EXCLUDED.order_status,
                    payment_status=EXCLUDED.payment_status,
                    paid_at=EXCLUDED.paid_at,
                    payment_date_source=EXCLUDED.payment_date_source,
                    created_at=EXCLUDED.created_at,
                    source_updated_at=EXCLUDED.source_updated_at,
                    total=EXCLUDED.total,
                    currency=EXCLUDED.currency,
                    eligible_paid_sale=EXCLUDED.eligible_paid_sale,
                    ingested_at=now()
                    WHERE analytics_order_snapshot.source_updated_at <= EXCLUDED.source_updated_at
                    """,
                    WORKSPACE,row.id(),row.number(),row.status(),row.paymentStatus(),
                    timestamp(row.paidAt()),row.paidDateSource(),timestamp(row.createdAt()),
                    timestamp(row.updatedAt()),row.total(),row.currency(),row.eligibleSale());
        }
        markSuccess(runId, "NUVEMSHOP", "ORDERS", cursor, fetched, rows.size());
    }

    @Transactional
    public void saveCampaigns(long runId, List<AnalyticsRecords.Campaign> rows,
            Instant observedAt, int fetched) {
        for (var row : rows) {
            jdbc.update("""
                    INSERT INTO analytics_meta_campaign_snapshot
                    (workspace_id,account_id,campaign_id,observed_day,campaign_name,objective,
                     status,effective_status,observed_at)
                    VALUES (?,?,?,?,?,?,?,?,?)
                    ON CONFLICT (workspace_id,account_id,campaign_id,observed_day)
                    DO UPDATE SET campaign_name=EXCLUDED.campaign_name,
                    objective=EXCLUDED.objective,status=EXCLUDED.status,
                    effective_status=EXCLUDED.effective_status,
                    observed_at=EXCLUDED.observed_at
                    WHERE analytics_meta_campaign_snapshot.observed_at<=EXCLUDED.observed_at
                    """, WORKSPACE,row.accountId(),row.id(),row.observedDay(),row.name(),
                    row.objective(),row.status(),row.effectiveStatus(),
                    Timestamp.from(row.observedAt()));
        }
        markSuccess(runId,"META_ADS","CAMPAIGNS",observedAt,fetched,rows.size());
    }

    @Transactional
    public void saveMeta(long runId, List<AnalyticsRecords.Meta> rows,
            String accountId, LocalDate from, LocalDate through, Instant cursor,
            int fetched) {
        // Replace the entire refreshed window atomically: delayed Meta attribution
        // can revise or remove previously reported campaign/day rows.
        jdbc.update("""
                DELETE FROM analytics_meta_insight_daily
                 WHERE workspace_id=? AND account_id=? AND insight_day BETWEEN ? AND ?
                """, WORKSPACE, accountId, from, through);
        for (var row : rows) {
            jdbc.update("""
                    INSERT INTO analytics_meta_insight_daily
                    (workspace_id,account_id,campaign_id,insight_day,spend,impressions,clicks,
                     attributed_purchases,attributed_revenue,currency)
                    VALUES (?,?,?,?,?,?,?,?,?,?)
                    """, WORKSPACE,row.accountId(),row.campaignId(),row.day(),
                    row.spend(),row.impressions(),row.clicks(),row.purchases(),
                    row.attributedRevenue(),row.currency());
        }
        markSuccess(runId,"META_ADS","INSIGHTS_DAILY",cursor,fetched,rows.size());
    }

    private void markSuccess(long id, String provider, String resource,
            Instant cursor, int fetched, int stored) {
        jdbc.update("""
                INSERT INTO analytics_sync_state
                (workspace_id,provider,resource,cursor_at,last_completed_at)
                VALUES (?,?,?,?,now())
                ON CONFLICT (workspace_id,provider,resource) DO UPDATE
                SET cursor_at=EXCLUDED.cursor_at,last_completed_at=now(),updated_at=now()
                """,WORKSPACE,provider,resource,Timestamp.from(cursor));
        jdbc.update("""
                UPDATE analytics_sync_run SET status='SUCCEEDED',finished_at=now(),
                records_fetched=?,records_stored=? WHERE id=? AND status='RUNNING'
                """,fetched,stored,id);
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }
}
