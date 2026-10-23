package br.com.adpilot.analytics;

import br.com.adpilot.analytics.RecommendationModels.Advice;
import br.com.adpilot.analytics.RecommendationModels.DailyCampaign;
import br.com.adpilot.analytics.RecommendationModels.Report;
import br.com.adpilot.analytics.RecommendationModels.SyncState;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Parameterized SQL and local evidence storage. No provider calls or ad mutations. */
@Repository
public class RecommendationRepository {
    private final JdbcTemplate jdbc;

    public RecommendationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public List<DailyCampaign> dailyCampaigns(LocalDate since, LocalDate until) {
        return jdbc.query("""
                SELECT d.account_id,d.campaign_id,
                       COALESCE(NULLIF(c.campaign_name,''),d.campaign_id) campaign_name,
                       c.objective,d.currency,d.insight_day,d.spend,d.impressions,d.clicks,
                       d.attributed_purchases,d.attributed_revenue,
                       d.link_clicks,d.landing_page_views,d.post_engagements,d.add_to_carts
                FROM analytics_meta_insight_daily d
                LEFT JOIN LATERAL (
                    SELECT s.campaign_name,s.objective
                    FROM analytics_meta_campaign_snapshot s
                    WHERE s.workspace_id=d.workspace_id AND s.account_id=d.account_id
                      AND s.campaign_id=d.campaign_id
                    ORDER BY s.observed_at DESC
                    LIMIT 1
                ) c ON TRUE
                WHERE d.workspace_id=? AND d.insight_day BETWEEN ? AND ?
                ORDER BY d.account_id,d.campaign_id,d.insight_day
                """, (rs, n) -> new DailyCampaign(
                rs.getString("account_id"), rs.getString("campaign_id"),
                rs.getString("campaign_name"), rs.getString("currency"),
                rs.getObject("insight_day", LocalDate.class), rs.getBigDecimal("spend"),
                rs.getLong("impressions"), rs.getLong("clicks"),
                rs.getBigDecimal("attributed_purchases"),
                rs.getBigDecimal("attributed_revenue"),rs.getString("objective"),
                rs.getBigDecimal("link_clicks"),rs.getBigDecimal("landing_page_views"),
                rs.getBigDecimal("post_engagements"),rs.getBigDecimal("add_to_carts")),
                AnalyticsSyncStore.WORKSPACE, since, until);
    }

    @Transactional(readOnly = true)
    public SyncState metaSyncState() {
        var states = jdbc.query("""
                SELECT s.last_completed_at,
                       (SELECT r.status FROM analytics_sync_run r
                        WHERE r.workspace_id=s.workspace_id AND r.provider='META_ADS'
                          AND r.resource='INSIGHTS_DAILY'
                        ORDER BY r.started_at DESC,r.id DESC LIMIT 1) last_status
                FROM analytics_sync_state s
                WHERE s.workspace_id=? AND s.provider='META_ADS'
                  AND s.resource='INSIGHTS_DAILY'
                """, (rs, n) -> new SyncState(rs.getString("last_status"),
                rs.getTimestamp("last_completed_at") == null ? null :
                        rs.getTimestamp("last_completed_at").toInstant()),
                AnalyticsSyncStore.WORKSPACE);
        return states.isEmpty() ? new SyncState(null, null) : states.getFirst();
    }

    /**
     * Transactional append-only evaluation. Re-running an evaluation creates a
     * new audit batch; older recommendations and evidence stay unchanged.
     */
    @Transactional
    public UUID store(Report report) {
        UUID evaluationId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO analytics_recommendation_evaluation
                (id,workspace_id,source_period_start,source_period_end,target_roas,
                 target_cpa,coverage_verified,created_at)
                VALUES (?,?,?,?,?,?,?,?)
                """, evaluationId, AnalyticsSyncStore.WORKSPACE, report.since(), report.until(),
                report.targetRoas(), report.targetCpa(), report.coverageVerified(),
                Timestamp.from(report.generatedAt()));
        var linked=new java.util.HashMap<String,java.util.UUID>();
        for(var goal:report.linkedGoals())
            linked.put(goal.accountId()+":"+goal.campaignId(),goal.goalId());
        for (Advice advice : report.recommendations()) {
            UUID id = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO analytics_recommendation
                    (id,workspace_id,recommendation_type,justification,confidence,status,
                     source_period_start,source_period_end,created_at,evaluation_id,
                     account_id,campaign_id,campaign_name,currency,rule_version,
                     action_code,priority,product_goal_id)
                    VALUES (?,?,?,?,?,'DRAFT',?,?,?,?,?,?,?,?,?,?,?,?)
                    """, id, AnalyticsSyncStore.WORKSPACE, advice.ruleCode(),
                    advice.justification(), advice.confidence(), report.since(), report.until(),
                    Timestamp.from(report.generatedAt()), evaluationId, advice.accountId(),
                    advice.campaignId(), advice.campaignName(), advice.currency(),
                    advice.ruleVersion(), advice.actionCode(), advice.priority(),
                    advice.accountId()==null || advice.campaignId()==null ||
                    (report.targetCpa()!=null && report.targetRoas()!=null) ? null :
                        linked.get(advice.accountId()+":"+advice.campaignId()));
            for (var metric : advice.evidence()) {
                jdbc.update("""
                        INSERT INTO analytics_recommendation_evidence
                        (recommendation_id,evidence_type,metric_name,metric_value,
                         observed_at,source_run_id,limitation)
                        VALUES (?,'META_CAMPAIGN_AGGREGATE',?,?,?,NULL,?)
                        """, id, metric.name(), metric.value(),
                        Timestamp.from(report.generatedAt()), metric.limitation());
            }
        }
        return evaluationId;
    }
}
