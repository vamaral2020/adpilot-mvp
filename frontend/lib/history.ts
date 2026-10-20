export type MetaDay = { date: string; currency: string; spend: number; impressions: number; clicks: number; attributedPurchases: number; attributedRevenue: number };
export type StoreDay = { date: string; currency: string; paidOrders: number; paidRevenue: number };
export type SourceState = { provider: string; resource: string; lastStatus: string | null; lastSuccess: string | null; cursorAt: string | null };
export type AnalyticsHistory = { since: string; until: string; meta: MetaDay[]; store: StoreDay[]; sources: SourceState[]; complete: boolean; warnings: string[] };

export function sums(rows: { currency: string; value: number }[]): { currency: string; value: number } | null {
  const currencies = new Set(rows.map(row => row.currency));
  if (currencies.size !== 1) return null;
  const currency = [...currencies][0];
  if (!/^[A-Z]{3}$/.test(currency) || rows.some(row => !Number.isFinite(row.value) || row.value < 0)) return null;
  return { currency, value: rows.reduce((sum, row) => sum + row.value, 0) };
}

export function graphBars(rows: { date: string; value: number }[]): { date: string; height: number; value: number }[] {
  const max = Math.max(0, ...rows.map(x => Number.isFinite(x.value) ? x.value : 0));
  return rows.map(row => ({ ...row, height: max && Number.isFinite(row.value) ? Math.max(0, Math.min(100, row.value / max * 100)) : 0 }));
}
