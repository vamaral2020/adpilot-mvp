-- Multi-objective Meta reporting. Null signifies a metric not reported by Meta,
-- not a measured zero. Historical snapshots are intentionally not fabricated.
ALTER TABLE analytics_meta_insight_daily
  ADD COLUMN link_clicks NUMERIC(18,4) CHECK (link_clicks IS NULL OR link_clicks >= 0),
  ADD COLUMN landing_page_views NUMERIC(18,4) CHECK (landing_page_views IS NULL OR landing_page_views >= 0),
  ADD COLUMN post_engagements NUMERIC(18,4) CHECK (post_engagements IS NULL OR post_engagements >= 0),
  ADD COLUMN add_to_carts NUMERIC(18,4) CHECK (add_to_carts IS NULL OR add_to_carts >= 0);
