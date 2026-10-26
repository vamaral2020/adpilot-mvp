# Fix PR055.1 — Sparse Meta Insights for paused campaigns

## Evidence in local production-like integration

For Meta campaign `52596120828588` (PAUSED):
- 2026-10-17..2026-10-23: delivery and funnel both HTTP 200;
- 2026-10-18..2026-10-25: both HTTP 502 `META_DELIVERY_INVALID_DATA`;
- 2026-10-19: Meta daily account campaign-level Insights show 2
  attributed purchases, R$ 497 attributed revenue, and spend 0.
- A broader window confirms 3 attributed purchases and R$ 698.05
  attributed revenue. These are Meta signals, not settled store orders.

The narrower range with conversions but no delivery strongly suggests
sparse Insights rows. **We have not seen the raw adset/ad rows in the
failing window**; exact provider omission remains a hypothesis pending
local validation.

## Conservative backend change

- The delivery interpreter no longer rejects an Insight merely because
  one of `spend`, `impressions` or `clicks` is absent: these become
  Java null, not numeric zero.
- Explicit Meta values `0` remain zero; when an attribution window
  contains zero spend but attributed Purchase > 0, we intentionally
  withhold CPA and cost ratios (null), rather than claiming free sales.
  A warning explains the attribution/spend mismatch.
- Numeric invalid values,
  duplicate rows, foreign campaign data and invalid IDs still fail
  closed with HTTP 502.
- Ratios depending on missing metrics become `null`, not invented
  zeros, division-by-zero or purchases without spend.
- `attributedPurchases`, `attributedPurchaseValue`,
  `initiatedCheckouts` and other available actions remain readable
  independently of missing delivery values.
- The funnel aggregates only when **every** row reports a metric;
  otherwise the campaign total for that metric is null.
- `complete` continues to describe **pagination only**.
- `META_DELIVERY_METRICS_MISSING_NOT_ZERO`,
  `DELIVERY_METRICS_UNAVAILABLE_NOT_ZERO`, and row-level warning
  fields expose the limitation. The UI types accept nullable metrics.
- Distinct sanitized error codes `META_DELIVERY_IDENTITY_MISMATCH`
  and `META_DELIVERY_ENTITY_ID_MISSING` help diagnose any remaining
  502 without exposing raw Meta data, credentials or IDs.

## Tests

JUnit: conversion-only Insights with attributed Purchase but missing
core metrics; explicit zero spend with attributed conversions; invalid
entity IDs; null-safe funnel aggregation over multiple adsets; existing
coverage, auth and permission tests.

## Local homologation after review/merge

```bash
git checkout main
git pull --ff-only origin main
docker compose up -d --build --force-recreate api web

BASE=http://localhost:18080/api/v1/meta/campaigns/52596120828588
for endpoint in delivery-diagnostics funnel-diagnostics; do
  curl -sS -u admin \
    -o /tmp/adpilot-paused-diagnostic.json \
    -w 'HTTP_STATUS=%{http_code}\n' \
    "$BASE/$endpoint?since=2026-10-18&until=2026-10-25"
  jq -c 'if .code then {code,message} else
      {campaignId,complete,analysisMode,
       adsetInsights:([.adsetInsights[]? |
         {spend,impressions,allClicks,attributedPurchases}]),
       campaign:(.campaign // null),warnings}
      end' /tmp/adpilot-paused-diagnostic.json
done
```

If a request still returns 502, report the new `code`. We must not
suppress invalid campaign IDs or pretend sparse data are zero.

This targeted fix does not alter campaigns, attribution settings,
Nuvemshop data, persisted analytics, or the upcoming PR056 UI scope.
