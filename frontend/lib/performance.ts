import type { CommerceSummary, InsightSummary } from "./dashboard";

export type CampaignEfficiency = {
  id: string;
  name: string;
  spend: number;
  clicks: number;
  purchases: number;
  attributedRevenue: number;
  roas: number | null;
  costPerAttributedPurchase: number | null;
  spendShare: number | null;
  signal: "without-attributed-purchases" | "with-attributed-purchases" | "no-spend";
};

export type MediaEfficiency = {
  rows: CampaignEfficiency[];
  totalSpend: number;
  totalAttributedRevenue: number;
  totalAttributedPurchases: number;
  accountCostPerAttributedPurchase: number | null;
  spendWithoutAttributedPurchases: number;
  campaignsWithoutAttributedPurchases: number;
  topSpender: CampaignEfficiency | null;
};

export type SourceComparison = {
  paidOrders: number;
  paidRevenue: number;
  attributedPurchases: number;
  attributedRevenue: number;
  mediaSpend: number;
  mer: number | null;
  attributedRoas: number | null;
};

/** Derived metrics are informational, never campaign-to-order reconciliation. */
const nonnegative = (value: number | null | undefined): number | null =>
  typeof value === "number" && Number.isFinite(value) && value >= 0 ? value : null;

/** Guard against partial pagination and inconsistent campaign/account totals. */
export function analyzeMedia(summary?: InsightSummary): MediaEfficiency | null {
  if (!summary?.complete || !Array.isArray(summary.campaigns)) return null;
  const totalSpend = nonnegative(summary.totals?.spend);
  const totalRevenue = nonnegative(summary.totals?.purchaseValue);
  const totalPurchases = nonnegative(summary.totals?.purchases);
  if (totalSpend === null || totalRevenue === null || totalPurchases === null) return null;
  const seen = new Set<string>();
  const rows: CampaignEfficiency[] = [];
  for (const campaign of summary.campaigns) {
    if (!campaign || typeof campaign.campaignId !== "string" || seen.has(campaign.campaignId)) return null;
    seen.add(campaign.campaignId);
    const m = campaign.metrics;
    const spend = nonnegative(m?.spend);
    const purchases = nonnegative(m?.purchases);
    const attributedRevenue = nonnegative(m?.purchaseValue);
    const clicks = nonnegative(m?.clicks);
    if (spend === null || purchases === null || attributedRevenue === null || clicks === null) return null;
    rows.push({
      id: campaign.campaignId,
      name: campaign.campaignName || campaign.campaignId,
      spend, clicks, purchases, attributedRevenue,
      roas: spend > 0 ? attributedRevenue / spend : null,
      costPerAttributedPurchase: purchases > 0 ? spend / purchases : null,
      spendShare: totalSpend > 0 ? spend / totalSpend * 100 : null,
      signal: spend === 0 ? "no-spend" :
        purchases === 0 ? "without-attributed-purchases" : "with-attributed-purchases",
    });
  }
  const close = (a: number, b: number) => Math.abs(a - b) <= 0.021;
  if (!close(rows.reduce((s, r) => s + r.spend, 0), totalSpend)
      || !close(rows.reduce((s, r) => s + r.attributedRevenue, 0), totalRevenue)
      || !close(rows.reduce((s, r) => s + r.purchases, 0), totalPurchases)) return null;

  rows.sort((a, b) => b.spend - a.spend || a.id.localeCompare(b.id));
  const without = rows.filter(row => row.signal === "without-attributed-purchases");
  return {
    rows, totalSpend, totalAttributedRevenue: totalRevenue,
    totalAttributedPurchases: totalPurchases,
    accountCostPerAttributedPurchase: totalPurchases > 0 ? totalSpend / totalPurchases : null,
    spendWithoutAttributedPurchases: without.reduce((s, row) => s + row.spend, 0),
    campaignsWithoutAttributedPurchases: without.length,
    topSpender: rows.find(row => row.spend > 0) ?? null,
  };
}

/**
 * Independent observations for the same requested dates. Comparing values is
 * NOT evidence that any Meta-reported purchase matches a paid store order.
 * Strictly block partial pages, different windows/currencies, and disagreeing
 * copies of Meta metrics.
 */
export function compareSources(meta?: InsightSummary,
    store?: CommerceSummary): SourceComparison | null {
  if (!meta?.complete || !store?.ordersComplete || !store.metaComplete || !store.complete
      || meta.since !== store.since || meta.until !== store.until
      || !store.storeCurrency || store.storeCurrency !== store.metaCurrency) return null;
  const paidOrders = nonnegative(store.paidOrders);
  const paidRevenue = nonnegative(store.paidRevenue);
  const attributedPurchases = nonnegative(meta.totals?.purchases);
  const attributedRevenue = nonnegative(meta.totals?.purchaseValue);
  const mediaSpend = nonnegative(meta.totals?.spend);
  const commerceSpend = nonnegative(store.mediaSpend);
  const commerceAttribution = nonnegative(store.metaAttributedRevenue);
  if ([paidOrders, paidRevenue, attributedPurchases, attributedRevenue,
    mediaSpend, commerceSpend, commerceAttribution].some(v => v === null)) return null;
  if (Math.abs(mediaSpend! - commerceSpend!) > 0.011
      || Math.abs(attributedRevenue! - commerceAttribution!) > 0.011) return null;
  return {
    paidOrders: paidOrders!, paidRevenue: paidRevenue!,
    attributedPurchases: attributedPurchases!,
    attributedRevenue: attributedRevenue!, mediaSpend: mediaSpend!,
    mer: mediaSpend! > 0 ? paidRevenue! / mediaSpend! : null,
    attributedRoas: mediaSpend! > 0 ? attributedRevenue! / mediaSpend! : null,
  };
}
