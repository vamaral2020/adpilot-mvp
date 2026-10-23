-- PR 022: immutable, manually named per-product economic plans in BRL.
-- No customer identifiers, provider tokens or modifications to Meta/Nuvemshop.
-- An existing campaign can explicitly reference one (and only one) saved plan.
-- Saving a new calculation creates a new immutable version, never updates old costs.
CREATE TABLE analytics_product_goal (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL REFERENCES workspace(id),
    product_label VARCHAR(120) NOT NULL,
    price_cents BIGINT NOT NULL CHECK (price_cents BETWEEN 1 AND 100000000),
    product_cost_cents BIGINT NOT NULL CHECK (product_cost_cents BETWEEN 0 AND 100000000),
    tax_basis_points INTEGER NOT NULL CHECK (tax_basis_points BETWEEN 0 AND 10000),
    payment_basis_points INTEGER NOT NULL CHECK (payment_basis_points BETWEEN 0 AND 10000),
    payment_fixed_cents BIGINT NOT NULL CHECK (payment_fixed_cents BETWEEN 0 AND 100000000),
    shipping_subsidy_cents BIGINT NOT NULL CHECK (shipping_subsidy_cents BETWEEN 0 AND 100000000),
    packaging_cents BIGINT NOT NULL CHECK (packaging_cents BETWEEN 0 AND 100000000),
    other_costs_cents BIGINT NOT NULL CHECK (other_costs_cents BETWEEN 0 AND 100000000),
    desired_profit_basis_points INTEGER NOT NULL
        CHECK (desired_profit_basis_points BETWEEN 0 AND 10000),
    target_cpa_cents BIGINT NOT NULL CHECK (target_cpa_cents BETWEEN 1 AND 100000000),
    target_roas_hundredths INTEGER NOT NULL CHECK (target_roas_hundredths BETWEEN 1 AND 10000),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (workspace_id,id),
    CHECK (length(trim(product_label)) BETWEEN 2 AND 120)
);
CREATE INDEX ix_analytics_product_goal_workspace
    ON analytics_product_goal(workspace_id,created_at DESC);

CREATE TABLE analytics_campaign_goal (
    workspace_id UUID NOT NULL REFERENCES workspace(id),
    account_id VARCHAR(40) NOT NULL CHECK (account_id ~ '^[0-9]{1,40}$'),
    campaign_id VARCHAR(40) NOT NULL CHECK (campaign_id ~ '^[0-9]{1,40}$'),
    goal_id UUID NOT NULL,
    single_product_confirmed BOOLEAN NOT NULL CHECK (single_product_confirmed),
    linked_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (workspace_id,account_id,campaign_id),
    FOREIGN KEY (workspace_id,goal_id)
        REFERENCES analytics_product_goal(workspace_id,id)
);
CREATE INDEX ix_analytics_campaign_goal_plan
    ON analytics_campaign_goal(workspace_id,goal_id);

-- A saved recommendation remains traceable to the immutable economic version.
-- Nullable: recommendations with manually entered targets may have no saved plan.
ALTER TABLE analytics_recommendation
    ADD COLUMN product_goal_id UUID REFERENCES analytics_product_goal(id);
