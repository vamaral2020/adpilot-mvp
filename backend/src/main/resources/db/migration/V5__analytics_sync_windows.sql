-- PR 020. Explicitly track fully fetched source windows, not inferred from rows.
-- A successful Meta window can contain no campaign rows for an individual day;
-- this means "no returned rows", NOT a confirmed lack of spend or purchases.
-- Store windows are filtered by updated_at, NOT by paid_at.
-- Old V3/V4 runs do not provide historical coverage proof and are not backfilled.
CREATE TABLE analytics_sync_window (
    run_id BIGINT PRIMARY KEY REFERENCES analytics_sync_run(id),
    workspace_id UUID NOT NULL REFERENCES workspace(id),
    provider VARCHAR(20) NOT NULL,
    resource VARCHAR(32) NOT NULL,
    account_id VARCHAR(40),
    meta_from DATE,
    meta_through DATE,
    shop_updated_from TIMESTAMPTZ,
    shop_updated_through TIMESTAMPTZ,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_analytics_sync_window_type CHECK (
        (provider='META_ADS' AND resource='INSIGHTS_DAILY'
         AND account_id IS NOT NULL AND meta_from IS NOT NULL AND meta_through IS NOT NULL
         AND meta_from <= meta_through
         AND shop_updated_from IS NULL AND shop_updated_through IS NULL)
        OR
        (provider='NUVEMSHOP' AND resource='ORDERS'
         AND account_id IS NULL AND meta_from IS NULL AND meta_through IS NULL
         AND shop_updated_from IS NOT NULL AND shop_updated_through IS NOT NULL
         AND shop_updated_from < shop_updated_through)
    )
);
CREATE INDEX ix_analytics_sync_window_meta
    ON analytics_sync_window(workspace_id,meta_from,meta_through)
    WHERE provider='META_ADS';
CREATE INDEX ix_analytics_sync_window_shop
    ON analytics_sync_window(workspace_id,shop_updated_through DESC)
    WHERE provider='NUVEMSHOP';
