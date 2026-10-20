-- PR 015: isolated, first-party analytics snapshots. No raw tokens, PII or user event identifiers.
-- Workspace scope is mandatory even while the product has only one local workspace.
CREATE TABLE analytics_sync_state (
    workspace_id UUID NOT NULL REFERENCES workspace(id),
    provider VARCHAR(20) NOT NULL CHECK (provider IN ('META_ADS','NUVEMSHOP')),
    resource VARCHAR(32) NOT NULL CHECK (resource IN ('ORDERS','INSIGHTS_DAILY','CAMPAIGNS')),
    cursor_at TIMESTAMPTZ,
    last_completed_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (workspace_id, provider, resource)
);

CREATE TABLE analytics_sync_run (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    workspace_id UUID NOT NULL REFERENCES workspace(id),
    provider VARCHAR(20) NOT NULL,
    resource VARCHAR(32) NOT NULL,
    started_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at TIMESTAMPTZ,
    status VARCHAR(16) NOT NULL CHECK (status IN ('RUNNING','SUCCEEDED','FAILED','INCOMPLETE')),
    records_fetched INTEGER NOT NULL DEFAULT 0,
    records_stored INTEGER NOT NULL DEFAULT 0,
    error_code VARCHAR(64),
    CHECK (records_fetched >= 0 AND records_stored >= 0)
);
CREATE INDEX ix_analytics_sync_run_latest ON analytics_sync_run(workspace_id,provider,resource,started_at DESC);

CREATE TABLE analytics_order_snapshot (
    workspace_id UUID NOT NULL REFERENCES workspace(id),
    order_id VARCHAR(30) NOT NULL,
    order_number VARCHAR(30),
    order_status VARCHAR(32) NOT NULL,
    payment_status VARCHAR(32) NOT NULL,
    paid_at TIMESTAMPTZ,
    payment_date_source VARCHAR(12) NOT NULL CHECK (payment_date_source IN ('LIST','DETAIL','UNKNOWN')),
    created_at TIMESTAMPTZ,
    source_updated_at TIMESTAMPTZ NOT NULL,
    total NUMERIC(18,2),
    currency CHAR(3),
    eligible_paid_sale BOOLEAN NOT NULL DEFAULT FALSE,
    ingested_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (workspace_id, order_id),
    CHECK (total IS NULL OR total >= 0),
    CHECK (NOT eligible_paid_sale OR (paid_at IS NOT NULL AND total IS NOT NULL AND currency IS NOT NULL
        AND payment_status='paid' AND order_status <> 'cancelled'))
);
CREATE INDEX ix_analytics_order_paid ON analytics_order_snapshot(workspace_id,paid_at)
    WHERE eligible_paid_sale = TRUE;
CREATE INDEX ix_analytics_order_updates ON analytics_order_snapshot(workspace_id,source_updated_at);

-- Daily snapshots of campaign metadata reflect the state observed on the
-- collection day; status must not be presented as a historical Meta status.
CREATE TABLE analytics_meta_campaign_snapshot (
    workspace_id UUID NOT NULL REFERENCES workspace(id),
    account_id VARCHAR(40) NOT NULL,
    campaign_id VARCHAR(40) NOT NULL,
    observed_day DATE NOT NULL,
    campaign_name VARCHAR(160),
    objective VARCHAR(80),
    status VARCHAR(40),
    effective_status VARCHAR(40),
    observed_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (workspace_id,account_id,campaign_id,observed_day)
);
CREATE INDEX ix_analytics_campaign_observed
    ON analytics_meta_campaign_snapshot(workspace_id,account_id,observed_day);

CREATE TABLE analytics_meta_insight_daily (
    workspace_id UUID NOT NULL REFERENCES workspace(id),
    account_id VARCHAR(40) NOT NULL,
    campaign_id VARCHAR(40) NOT NULL,
    insight_day DATE NOT NULL,
    spend NUMERIC(18,4) NOT NULL CHECK (spend >= 0),
    impressions BIGINT NOT NULL CHECK (impressions >= 0),
    clicks BIGINT NOT NULL CHECK (clicks >= 0),
    attributed_purchases NUMERIC(18,4) NOT NULL CHECK (attributed_purchases >= 0),
    attributed_revenue NUMERIC(18,4) NOT NULL CHECK (attributed_revenue >= 0),
    currency CHAR(3) NOT NULL,
    ingested_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (workspace_id,account_id,campaign_id,insight_day)
);
CREATE INDEX ix_analytics_meta_day ON analytics_meta_insight_daily(workspace_id,insight_day);

-- Segments are definitions, not lists of customers. No customer identity is stored.
-- Definitions may be activated only after distinct privacy, eligibility and approval reviews.
CREATE TABLE analytics_audience_definition (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL REFERENCES workspace(id),
    label VARCHAR(120) NOT NULL,
    source_type VARCHAR(32) NOT NULL,
    definition_version INTEGER NOT NULL DEFAULT 1 CHECK (definition_version > 0),
    criteria JSONB NOT NULL DEFAULT '{}'::jsonb,
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT','REVIEW','APPROVED','ARCHIVED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_analytics_audience_workspace ON analytics_audience_definition(workspace_id,status);

-- Each recommendation has traceable aggregate evidence; execution is deliberately absent.
CREATE TABLE analytics_recommendation (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL REFERENCES workspace(id),
    audience_id UUID REFERENCES analytics_audience_definition(id),
    recommendation_type VARCHAR(40) NOT NULL,
    justification TEXT NOT NULL,
    confidence VARCHAR(20) NOT NULL CHECK (confidence IN ('INSUFFICIENT','LOW','MEDIUM','HIGH')),
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT','REVIEW','APPROVED','REJECTED','ARCHIVED')),
    source_period_start DATE NOT NULL,
    source_period_end DATE NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (source_period_start <= source_period_end)
);
CREATE TABLE analytics_recommendation_evidence (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    recommendation_id UUID NOT NULL REFERENCES analytics_recommendation(id) ON DELETE CASCADE,
    evidence_type VARCHAR(48) NOT NULL,
    metric_name VARCHAR(64) NOT NULL,
    metric_value NUMERIC(20,6),
    observed_at TIMESTAMPTZ NOT NULL,
    source_run_id BIGINT REFERENCES analytics_sync_run(id),
    limitation VARCHAR(240)
);
