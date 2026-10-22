-- PR 017: local recommendation evidence only. No external ad writes or customer data.
-- Preserve existing recommendation tables for future approval workflows.
CREATE TABLE analytics_recommendation_evaluation (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL REFERENCES workspace(id),
    source_period_start DATE NOT NULL,
    source_period_end DATE NOT NULL,
    target_roas NUMERIC(12,4),
    target_cpa NUMERIC(18,4),
    coverage_verified BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (source_period_start <= source_period_end),
    CHECK (target_roas IS NULL OR target_roas > 0),
    CHECK (target_cpa IS NULL OR target_cpa > 0)
);
CREATE INDEX ix_analytics_recommendation_evaluation_latest
    ON analytics_recommendation_evaluation(workspace_id,created_at DESC);

ALTER TABLE analytics_recommendation
    ADD COLUMN evaluation_id UUID REFERENCES analytics_recommendation_evaluation(id),
    ADD COLUMN account_id VARCHAR(40),
    ADD COLUMN campaign_id VARCHAR(40),
    ADD COLUMN campaign_name VARCHAR(160),
    ADD COLUMN currency CHAR(3),
    ADD COLUMN rule_version INTEGER NOT NULL DEFAULT 1 CHECK (rule_version > 0),
    ADD COLUMN action_code VARCHAR(40),
    ADD COLUMN priority VARCHAR(16) NOT NULL DEFAULT 'LOW'
        CHECK (priority IN ('LOW','MEDIUM','HIGH'));

CREATE INDEX ix_analytics_recommendation_evaluation
    ON analytics_recommendation(evaluation_id,created_at);
