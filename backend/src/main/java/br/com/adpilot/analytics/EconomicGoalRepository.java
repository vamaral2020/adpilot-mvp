package br.com.adpilot.analytics;

import br.com.adpilot.analytics.EconomicGoalModels.Campaign;
import br.com.adpilot.analytics.EconomicGoalModels.Goal;
import br.com.adpilot.analytics.RecommendationModels.LinkedGoal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** All planning data is local to AdPilot, workspace-scoped, and unrelated to ad writes. */
@Repository
public class EconomicGoalRepository {
    private final JdbcTemplate jdbc;

    public EconomicGoalRepository(JdbcTemplate jdbc) {
        this.jdbc=jdbc;
    }

    @Transactional
    public Goal create(Goal goal) {
        jdbc.update("""
                INSERT INTO analytics_product_goal
                (id,workspace_id,product_label,price_cents,product_cost_cents,
                 tax_basis_points,payment_basis_points,payment_fixed_cents,
                 shipping_subsidy_cents,packaging_cents,other_costs_cents,
                 desired_profit_basis_points,target_cpa_cents,target_roas_hundredths,created_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,goal.id(),AnalyticsSyncStore.WORKSPACE,goal.productLabel(),
                goal.priceCents(),goal.productCostCents(),goal.taxBasisPoints(),
                goal.paymentBasisPoints(),goal.paymentFixedCents(),
                goal.shippingSubsidyCents(),goal.packagingCents(),goal.otherCostsCents(),
                goal.desiredProfitBasisPoints(),goal.targetCpaCents(),
                goal.targetRoasHundredths(),Timestamp.from(goal.createdAt()));
        return goal;
    }

    @Transactional(readOnly=true)
    public List<Goal> goals() {
        return jdbc.query("""
                SELECT id,product_label,price_cents,product_cost_cents,
                       tax_basis_points,payment_basis_points,payment_fixed_cents,
                       shipping_subsidy_cents,packaging_cents,other_costs_cents,
                       desired_profit_basis_points,target_cpa_cents,
                       target_roas_hundredths,created_at
                FROM analytics_product_goal WHERE workspace_id=?
                ORDER BY created_at DESC,id DESC LIMIT 200
                """,(rs,n)->new Goal(
                rs.getObject("id",UUID.class),rs.getString("product_label"),
                rs.getLong("price_cents"),rs.getLong("product_cost_cents"),
                rs.getInt("tax_basis_points"),rs.getInt("payment_basis_points"),
                rs.getLong("payment_fixed_cents"),rs.getLong("shipping_subsidy_cents"),
                rs.getLong("packaging_cents"),rs.getLong("other_costs_cents"),
                rs.getInt("desired_profit_basis_points"),rs.getLong("target_cpa_cents"),
                rs.getInt("target_roas_hundredths"),rs.getTimestamp("created_at").toInstant()),
                AnalyticsSyncStore.WORKSPACE);
    }

    @Transactional(readOnly=true)
    public boolean goalExists(UUID goalId) {
        Integer count=jdbc.queryForObject("""
                SELECT count(*) FROM analytics_product_goal WHERE workspace_id=? AND id=?
                """,Integer.class,AnalyticsSyncStore.WORKSPACE,goalId);
        return count!=null && count>0;
    }

    /** A BRL account must be evidenced by collected insights; no guessed currency. */
    @Transactional(readOnly=true)
    public boolean eligibleCampaign(String accountId,String campaignId) {
        Integer count=jdbc.queryForObject("""
                SELECT count(*) FROM (
                    SELECT account_id,campaign_id FROM analytics_meta_campaign_snapshot
                     WHERE workspace_id=?
                    UNION
                    SELECT account_id,campaign_id FROM analytics_meta_insight_daily
                     WHERE workspace_id=?
                ) k
                WHERE k.account_id=? AND k.campaign_id=?
                  AND EXISTS (
                    SELECT 1 FROM analytics_meta_insight_daily d
                    WHERE d.workspace_id=? AND d.account_id=k.account_id
                    GROUP BY d.account_id
                    HAVING count(DISTINCT d.currency)=1 AND max(d.currency)='BRL'
                  )
                """,Integer.class,AnalyticsSyncStore.WORKSPACE,AnalyticsSyncStore.WORKSPACE,
                accountId,campaignId,AnalyticsSyncStore.WORKSPACE);
        return count!=null && count==1;
    }

    @Transactional
    public void link(String accountId,String campaignId,UUID goalId) {
        jdbc.update("""
                INSERT INTO analytics_campaign_goal
                (workspace_id,account_id,campaign_id,goal_id,single_product_confirmed)
                VALUES (?,?,?,?,true)
                ON CONFLICT (workspace_id,account_id,campaign_id)
                DO UPDATE SET goal_id=EXCLUDED.goal_id,
                              single_product_confirmed=true,linked_at=now()
                """,AnalyticsSyncStore.WORKSPACE,accountId,campaignId,goalId);
    }

    @Transactional
    public int unlink(String accountId,String campaignId) {
        return jdbc.update("""
                DELETE FROM analytics_campaign_goal
                WHERE workspace_id=? AND account_id=? AND campaign_id=?
                """,AnalyticsSyncStore.WORKSPACE,accountId,campaignId);
    }

    /** Latest cached catalog snapshots only; never calls the Meta Ads API. */
    @Transactional(readOnly=true)
    public List<Campaign> campaigns() {
        return jdbc.query("""
                WITH known AS (
                    SELECT account_id,campaign_id FROM analytics_meta_campaign_snapshot
                    WHERE workspace_id=?
                    UNION
                    SELECT account_id,campaign_id FROM analytics_meta_insight_daily
                    WHERE workspace_id=?
                ), brl_account AS (
                    SELECT account_id, max(currency) AS currency
                    FROM analytics_meta_insight_daily WHERE workspace_id=?
                    GROUP BY account_id
                    HAVING count(DISTINCT currency)=1 AND max(currency)='BRL'
                )
                SELECT k.account_id,k.campaign_id,
                       COALESCE(NULLIF(c.campaign_name,''),k.campaign_id) campaign_name,
                       b.currency,l.goal_id,g.product_label,g.target_cpa_cents,
                       g.target_roas_hundredths,l.linked_at
                FROM known k
                JOIN brl_account b ON b.account_id=k.account_id
                LEFT JOIN LATERAL (
                    SELECT campaign_name FROM analytics_meta_campaign_snapshot s
                    WHERE s.workspace_id=? AND s.account_id=k.account_id
                      AND s.campaign_id=k.campaign_id
                    ORDER BY s.observed_at DESC LIMIT 1
                ) c ON TRUE
                LEFT JOIN analytics_campaign_goal l
                  ON l.workspace_id=? AND l.account_id=k.account_id
                    AND l.campaign_id=k.campaign_id
                LEFT JOIN analytics_product_goal g
                  ON g.workspace_id=l.workspace_id AND g.id=l.goal_id
                ORDER BY campaign_name,k.campaign_id LIMIT 500
                """,(rs,n)->new Campaign(
                rs.getString("account_id"),rs.getString("campaign_id"),
                rs.getString("campaign_name"),rs.getString("currency"),
                rs.getObject("goal_id",UUID.class),rs.getString("product_label"),
                rs.getObject("target_cpa_cents",Long.class),
                rs.getObject("target_roas_hundredths",Integer.class),
                rs.getTimestamp("linked_at")==null?null:
                        rs.getTimestamp("linked_at").toInstant()),
                AnalyticsSyncStore.WORKSPACE,AnalyticsSyncStore.WORKSPACE,
                AnalyticsSyncStore.WORKSPACE,AnalyticsSyncStore.WORKSPACE,
                AnalyticsSyncStore.WORKSPACE);
    }

    /** A single query gives each recommendation an immutable per-campaign plan. */
    @Transactional(readOnly=true)
    public List<LinkedGoal> linkedGoals() {
        return jdbc.query("""
                SELECT l.goal_id,l.account_id,l.campaign_id,g.product_label,
                       g.price_cents,g.target_cpa_cents,g.target_roas_hundredths,
                       l.linked_at
                FROM analytics_campaign_goal l
                JOIN analytics_product_goal g
                  ON g.workspace_id=l.workspace_id AND g.id=l.goal_id
                WHERE l.workspace_id=? AND l.single_product_confirmed=TRUE
                ORDER BY l.account_id,l.campaign_id
                """,(rs,n)->new LinkedGoal(rs.getObject("goal_id",UUID.class),
                        rs.getString("account_id"),rs.getString("campaign_id"),
                        rs.getString("product_label"),rs.getLong("price_cents"),
                        java.math.BigDecimal.valueOf(rs.getLong("target_cpa_cents"),2),
                        java.math.BigDecimal.valueOf(rs.getInt("target_roas_hundredths"),2),
                        rs.getTimestamp("linked_at").toInstant()),
                AnalyticsSyncStore.WORKSPACE);
    }
}
