import test from "node:test";
import assert from "node:assert/strict";
import { analyzeMedia, compareSources } from "../lib/performance.ts";

const campaign = (id, spend, purchases, revenue, clicks = 10) => ({
  campaignId: id, campaignName: "Campanha " + id,
  metrics: {
    spend, impressions: 1000, clicks, ctr: 1, cpc: spend / Math.max(1, clicks),
    purchases, purchaseValue: revenue, roas: spend ? revenue / spend : null,
  },
});
const insight = (overrides = {}) => ({
  since: "2026-10-12", until: "2026-10-18", complete: true,
  pagesFetched: 1, recordsFetched: 2, after: null,
  totals: {
    spend: 100, impressions: 2000, clicks: 20, ctr: 1, cpc: 5,
    purchases: 2, purchaseValue: 40, roas: 0.4,
  },
  campaigns: [campaign("11", 60, 2, 40), campaign("22", 40, 0, 0)],
  ...overrides,
});
const commerce = (overrides = {}) => ({
  since: "2026-10-12", until: "2026-10-18",
  ordersComplete: true, metaComplete: true, complete: true,
  pagesFetched: 2, ordersFetched: 102, ordersPagesComplete: true,
  paidOrdersWithoutDate: 0, cancelledOrdersIgnored: 20, paidDatesRecovered: 44,
  individualLookups: 44, inconsistentDetails: 0,
  datedPaidOrders: 22, datedPaidRevenue: 1000,
  paidOrders: 22, paidRevenue: 1000, averageTicket: 45.45,
  storeCurrency: "BRL", metaCurrency: "BRL",
  mediaSpend: 100, metaAttributedRevenue: 40, metaRoas: .4, mer: 10,
  daily: [], warnings: [],
  ...overrides,
});

test("calculates Meta-only investment concentration and CPA using the same campaign rows", () => {
  const result = analyzeMedia(insight());
  assert.ok(result);
  assert.equal(result.rows.length, 2);
  assert.equal(result.topSpender.id, "11");
  assert.equal(result.topSpender.spendShare, 60);
  assert.equal(result.rows[0].costPerAttributedPurchase, 30);
  assert.equal(result.rows[0].roas, 40 / 60);
  assert.equal(result.rows[1].costPerAttributedPurchase, null);
  assert.equal(result.rows[1].signal, "without-attributed-purchases");
  assert.equal(result.spendWithoutAttributedPurchases, 40);
  assert.equal(result.campaignsWithoutAttributedPurchases, 1);
  assert.equal(result.accountCostPerAttributedPurchase, 50);
});

test("zero investment and no purchases do not fabricate share, CPA or ROAS", () => {
  const data = insight({
    totals: { ...insight().totals, spend: 0, purchases: 0, purchaseValue: 0 },
    campaigns: [campaign("0", 0, 0, 0, 0)],
  });
  const result = analyzeMedia(data);
  assert.ok(result);
  assert.equal(result.accountCostPerAttributedPurchase, null);
  assert.equal(result.rows[0].spendShare, null);
  assert.equal(result.rows[0].costPerAttributedPurchase, null);
  assert.equal(result.rows[0].roas, null);
  assert.equal(result.rows[0].signal, "no-spend");
  assert.equal(result.topSpender, null);
});

test("refuses partial and inconsistent Meta aggregates, duplicate campaigns, invalid values", () => {
  assert.equal(analyzeMedia(insight({ complete: false })), null);
  assert.equal(analyzeMedia(insight({
    totals: { ...insight().totals, spend: 200 },
  })), null);
  assert.equal(analyzeMedia(insight({
    campaigns: [campaign("11", 60, 2, 40), campaign("11", 40, 0, 0)],
  })), null);
  assert.equal(analyzeMedia(insight({
    campaigns: [campaign("11", -60, 2, 40), campaign("22", 40, 0, 0)],
  })), null);
  assert.equal(analyzeMedia(insight({
    campaigns: [campaign("11", Number.NaN, 2, 40), campaign("22", 40, 0, 0)],
  })), null);
});

test("keeps Nuvemshop paid orders and Meta attributed purchases independent", () => {
  const joined = compareSources(insight(), commerce());
  assert.ok(joined);
  assert.equal(joined.paidOrders, 22);
  assert.equal(joined.paidRevenue, 1000);
  assert.equal(joined.attributedPurchases, 2);
  assert.equal(joined.attributedRevenue, 40);
  assert.equal(joined.mediaSpend, 100);
  assert.equal(joined.mer, 10);
  assert.equal(joined.attributedRoas, 0.4);
  assert.equal("unattributedRevenue" in joined, false);
  assert.equal("attributedPaidOrders" in joined, false);
});

test("blocks cross-source comparison for partial, mismatched dates/currencies or differing Meta reports", () => {
  assert.equal(compareSources(insight({ complete: false }), commerce()), null);
  assert.equal(compareSources(insight(), commerce({ ordersComplete: false })), null);
  assert.equal(compareSources(insight(), commerce({ complete: false })), null);
  assert.equal(compareSources(insight(), commerce({ metaComplete: false })), null);
  assert.equal(compareSources(insight(), commerce({ since: "2026-10-11" })), null);
  assert.equal(compareSources(insight(), commerce({ metaCurrency: "USD" })), null);
  assert.equal(compareSources(insight(), commerce({ mediaSpend: 120 })), null);
  assert.equal(compareSources(insight(), commerce({ metaAttributedRevenue: 41 })), null);
  assert.equal(compareSources(insight(), commerce({ paidRevenue: null })), null);
});

test("zero spend leaves MER/ROAS blank but still permits source-side comparison", () => {
  const meta = insight({
    totals: { ...insight().totals, spend: 0, purchases: 0, purchaseValue: 0 },
    campaigns: [campaign("0", 0, 0, 0)],
  });
  const outcome = compareSources(meta, commerce({
    mediaSpend: 0, metaAttributedRevenue: 0, metaRoas: null, mer: null,
  }));
  assert.ok(outcome);
  assert.equal(outcome.mer, null);
  assert.equal(outcome.attributedRoas, null);
});
