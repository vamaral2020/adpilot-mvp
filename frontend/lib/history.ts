export type MetaDay = { date: string; currency: string; spend: number; impressions: number; clicks: number; attributedPurchases: number; attributedRevenue: number };
export type StoreDay = { date: string; currency: string; paidOrders: number; paidRevenue: number };
export type CampaignResult = { accountId: string; campaignId: string; name: string; currency: string; spend: number; impressions: number; clicks: number; attributedPurchases: number; attributedRevenue: number };
export type SourceState = { provider: string; resource: string; lastStatus: string | null; lastSuccess: string | null; cursorAt: string | null };
/** A successful query window is an audit receipt, not a guarantee of final attributed outcomes. */
export type MetaCoverage = {
  status: "NO_COLLECTION_RECEIPT" | "PARTIAL_WINDOWS" | "WINDOWS_OBSERVED" | "MULTIPLE_ACCOUNTS";
  accountId: string | null;
  requestedDays: number;
  observedDays: number;
  datesWithRows: number;
  datesWithoutRows: number;
  unobservedDates: string[];
  observedWithoutRows: string[];
  lastSuccess: string | null;
};
export type StoreCoverage = {
  status: "UPDATED_AT_ONLY_NOT_PAID_DATE";
  successfulUpdateWindows: number;
  lastSuccess: string | null;
};
export type HistoryCoverage = { meta: MetaCoverage; store: StoreCoverage };
export type AnalyticsHistory = { since: string; until: string; meta: MetaDay[]; store: StoreDay[];
  campaigns: CampaignResult[]; sources: SourceState[]; complete: boolean; warnings: string[];
  coverage?: HistoryCoverage | null;
};

/** Display status without claiming that a successful collection proves final outcomes. */
export function coverageMessage(coverage?: HistoryCoverage | null) {
  if (!coverage) return "Não há diagnóstico de janelas de coleta disponível.";
  switch (coverage.meta.status) {
    case "NO_COLLECTION_RECEIPT":
      return "Não há comprovantes de janelas de coleta Meta para este período. Registros antigos podem existir, mas não são prova de cobertura.";
    case "PARTIAL_WINDOWS":
      return "A Meta foi consultada com sucesso em apenas parte do período. Os demais dias não possuem coleta comprovada.";
    case "WINDOWS_OBSERVED":
      return "Todos os dias foram incluídos em consultas Meta concluídas. Isso não comprova atribuição definitiva nem vendas iguais a zero nos dias sem registros.";
    case "MULTIPLE_ACCOUNTS":
      return "Foram identificadas coletas de contas Meta diferentes. A cobertura combinada não é considerada verificável.";
    default:
      return "Não foi possível classificar a cobertura da Meta.";
  }
}

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


/**
 * Only Meta-attributed results. They are platform attribution, not reconciled
 * Nuvemshop orders or causal proof that an ad generated each purchase.
 * Missing series must never be silently rendered as a measured zero.
 */
export function campaignOutcome(rows: MetaDay[]): {
  spend: { currency: string; value: number } | null;
  revenue: { currency: string; value: number } | null;
  purchases: number | null;
  roas: number | null;
} {
  const spend = sums(rows.map(row => ({ currency: row.currency, value: row.spend })));
  const revenue = sums(rows.map(row => ({ currency: row.currency, value: row.attributedRevenue })));
  const purchases = rows.length && rows.every(row =>
    typeof row.attributedPurchases === "number" &&
    Number.isFinite(row.attributedPurchases) && row.attributedPurchases >= 0)
    ? rows.reduce((total, row) => total + row.attributedPurchases, 0)
    : null;
  const roas = spend && revenue && spend.currency === revenue.currency && spend.value > 0
    ? revenue.value / spend.value : null;
  return { spend, revenue, purchases, roas };
}

/** Paid store orders across all sources; no organic/ad channel classification. */
export function storeOutcome(rows: StoreDay[]): {
  revenue: { currency: string; value: number } | null;
  paidOrders: number | null;
} {
  const revenue = sums(rows.map(row => ({ currency: row.currency, value: row.paidRevenue })));
  const paidOrders = rows.length && rows.every(row =>
    typeof row.paidOrders === "number" &&
    Number.isSafeInteger(row.paidOrders) && row.paidOrders >= 0)
    ? rows.reduce((total, row) => total + row.paidOrders, 0)
    : null;
  return { revenue, paidOrders };
}
