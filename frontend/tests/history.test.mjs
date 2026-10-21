import test from "node:test";
import assert from "node:assert/strict";
import { sums, graphBars, campaignOutcome, storeOutcome } from "../lib/history.ts";

test("does not add currencies when reporting a historical total", () => {
  assert.equal(sums([{ currency: "BRL", value: 50 }, { currency: "USD", value: 5 }]), null);
});
test("does not turn an empty or invalid series into a monetary zero", () => {
  assert.equal(sums([]), null);
  assert.equal(sums([{ currency: "BRL", value: Number.NaN }]), null);
});
test("aggregates same-currency values and avoids NaN bar heights", () => {
  assert.deepEqual(sums([{ currency: "BRL", value: 15 }, { currency: "BRL", value: 5 }]), { currency: "BRL", value: 20 });
  assert.deepEqual(graphBars([{ date: "2026-10-01", value: 0 }]), [{ date: "2026-10-01", value: 0, height: 0 }]);
});


test("ad outcomes answer spent, attributed purchases, attributed revenue and attributed ROAS", () => {
  const ads = campaignOutcome([{
    date: "2026-10-20", currency: "BRL", spend: 130,
    attributedPurchases: 5, attributedRevenue: 1000,
    impressions: 100, clicks: 10,
  }]);
  assert.deepEqual(ads.spend, { currency: "BRL", value: 130 });
  assert.equal(ads.purchases, 5);
  assert.deepEqual(ads.revenue, { currency: "BRL", value: 1000 });
  assert.ok(Math.abs(ads.roas - 1000 / 130) < 0.00001);
});

test("store sales are independent of Meta attribution; no invented organic sales", () => {
  const meta = campaignOutcome([{
    date: "2026-10-20", currency: "BRL", spend: 130,
    attributedPurchases: 5, attributedRevenue: 1000,
    impressions: 100, clicks: 10,
  }]);
  const store = storeOutcome([{
    date: "2026-10-20", currency: "BRL", paidOrders: 21, paidRevenue: 4060.58,
  }]);
  assert.equal(meta.revenue.value, 1000);
  assert.equal(store.revenue.value, 4060.58);
  assert.equal(store.paidOrders, 21);
  assert.equal("organicRevenue" in store, false);
  assert.equal("organicPurchases" in meta, false);
});

test("empty history and missing metrics do not become zero conversions", () => {
  assert.deepEqual(campaignOutcome([]), {
    spend: null, revenue: null, purchases: null, roas: null,
  });
  assert.deepEqual(storeOutcome([]), { revenue: null, paidOrders: null });
  const invalid = campaignOutcome([{
    date: "2026-10-20", currency: "BRL", spend: 100,
    attributedPurchases: null, attributedRevenue: null,
    impressions: 100, clicks: 10,
  }]);
  assert.equal(invalid.purchases, null);
  assert.equal(invalid.revenue, null);
  assert.equal(invalid.roas, null);
});

test("zero conversions are valid when an actual Meta daily insight is present", () => {
  const report = campaignOutcome([{
    date: "2026-10-20", currency: "BRL", spend: 130,
    attributedPurchases: 0, attributedRevenue: 0, impressions: 500, clicks: 4,
  }]);
  assert.equal(report.purchases, 0);
  assert.equal(report.revenue.value, 0);
  assert.equal(report.roas, 0);
});

test("a zero denominator or currency mismatch hides attributed ROAS", () => {
  const sample = {
    date: "2026-10-20", currency: "BRL", spend: 0,
    attributedPurchases: 1, attributedRevenue: 200, impressions: 0, clicks: 0,
  };
  assert.equal(campaignOutcome([sample]).roas, null);
  const currencyMix = campaignOutcome([
    { ...sample, currency: "BRL", spend: 100 },
    { ...sample, currency: "USD", spend: 10 },
  ]);
  assert.equal(currencyMix.spend, null);
  assert.equal(currencyMix.revenue, null);
  assert.equal(currencyMix.roas, null);
});

test("daily Meta purchases are summed once per row, preserving reported attribution", () => {
  const a = { date: "2026-10-19", currency: "BRL", spend: 30,
    attributedPurchases: 2, attributedRevenue: 180, impressions: 100, clicks: 20 };
  const b = { ...a, date: "2026-10-20", spend: 70, attributedPurchases: 1, attributedRevenue: 220 };
  const outcome = campaignOutcome([a, b]);
  assert.equal(outcome.purchases, 3);
  assert.equal(outcome.revenue.value, 400);
  assert.equal(outcome.roas, 4);
});
