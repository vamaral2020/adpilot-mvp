export type InsightMetrics = {
  spend: number | null;
  impressions: number | null;
  clicks: number | null;
  ctr: number | null;
  cpc: number | null;
  purchases: number | null;
  purchaseValue: number | null;
  roas: number | null;
};

export type CampaignMetrics = {
  campaignId: string;
  campaignName: string;
  metrics: InsightMetrics;
};

export type InsightSummary = {
  since: string;
  until: string;
  complete: boolean;
  pagesFetched: number;
  recordsFetched: number;
  after: string | null;
  totals: InsightMetrics;
  campaigns: CampaignMetrics[];
};

export type Campaign = {
  id: string;
  name: string;
  objective?: string;
  status?: string;
  effective_status?: string;
  daily_budget?: string;
};

export type CommerceSummary = {
  since: string;
  until: string;
  ordersComplete: boolean;
  metaComplete: boolean;
  complete: boolean;
  pagesFetched: number;
  ordersFetched: number;
  ordersPagesComplete: boolean;
  paidOrdersWithoutDate: number;
  datedPaidOrders: number | null;
  datedPaidRevenue: number | null;
  paidOrders: number | null;
  paidRevenue: number | null;
  averageTicket: number | null;
  storeCurrency: string | null;
  metaCurrency: string | null;
  mediaSpend: number | null;
  metaAttributedRevenue: number | null;
  metaRoas: number | null;
  mer: number | null;
  daily: { date: string; paidOrders: number; paidRevenue: number }[];
  warnings: string[];
};

/** Defensive UI validation: a partial or cross-currency MER is never rendered. */
export function verifiedMer(summary?: CommerceSummary): number | null {
  if (!summary?.ordersComplete || !summary?.metaComplete ||
      !summary.storeCurrency || summary.storeCurrency !== summary.metaCurrency ||
      (finite(summary.mediaSpend) ?? 0) <= 0) return null;
  return finite(summary.mer);
}

export type CampaignPage = { data: Campaign[]; after: string | null };
export type MetaAccount = { id: string; name: string; currency: string; account_status: number };

export type Period = { since: string; until: string; error?: string };

const DAY = 86_400_000;
const ISO_DAY = /^\d{4}-\d{2}-\d{2}$/;

export function isoUtcDay(date: Date): string {
  return date.toISOString().slice(0, 10);
}

function parseDay(input: string): number | null {
  if (!ISO_DAY.test(input)) return null;
  const timestamp = Date.parse(input + "T00:00:00Z");
  if (!Number.isFinite(timestamp)) return null;
  return isoUtcDay(new Date(timestamp)) === input ? timestamp : null;
}

export function presetPeriod(days: number, today = new Date()): Period {
  // Last N complete UTC days. Accounts may have a different reporting timezone.
  const end = Date.UTC(today.getUTCFullYear(), today.getUTCMonth(), today.getUTCDate()) - DAY;
  return {
    since: isoUtcDay(new Date(end - (days - 1) * DAY)),
    until: isoUtcDay(new Date(end)),
  };
}

export function resolvePeriod(
  requestedSince?: string,
  requestedUntil?: string,
  today = new Date(),
): Period {
  const defaultPeriod = presetPeriod(7, today);
  if (!requestedSince && !requestedUntil) return defaultPeriod;
  const since = requestedSince ?? "";
  const until = requestedUntil ?? "";
  const start = parseDay(since);
  const end = parseDay(until);
  const tomorrow = Date.UTC(today.getUTCFullYear(), today.getUTCMonth(), today.getUTCDate()) + DAY;
  if (start === null || end === null || end < start || (end - start) / DAY > 89 || end > tomorrow) {
    return {
      since: requestedSince || defaultPeriod.since,
      until: requestedUntil || defaultPeriod.until,
      error: "Informe datas válidas em um intervalo de até 90 dias, sem períodos futuros.",
    };
  }
  return { since, until };
}

export function finite(value: number | null | undefined): number | null {
  if (value === null || value === undefined) return null;
  const numeric = Number(value);
  return Number.isFinite(numeric) ? numeric : null;
}

export function money(value: number | null | undefined, currency = "BRL"): string {
  const amount = finite(value);
  if (amount === null) return "—";
  const code = /^[A-Z]{3}$/.test(currency) ? currency : "BRL";
  try {
    return new Intl.NumberFormat("pt-BR", { style: "currency", currency: code }).format(amount);
  } catch {
    return new Intl.NumberFormat("pt-BR", { style: "currency", currency: "BRL" }).format(amount);
  }
}

export function number(value: number | null | undefined, maximumFractionDigits = 0): string {
  const amount = finite(value);
  return amount === null ? "—" :
    new Intl.NumberFormat("pt-BR", { maximumFractionDigits }).format(amount);
}

export function percentage(value: number | null | undefined): string {
  const amount = finite(value);
  return amount === null ? "—" : number(amount, 2) + "%";
}

export function multiplier(value: number | null | undefined): string {
  const amount = finite(value);
  return amount === null ? "—" : number(amount, 2) + "x";
}

export function readableDate(value: string): string {
  const time = parseDay(value);
  return time === null ? value :
    new Intl.DateTimeFormat("pt-BR", { day: "2-digit", month: "short", timeZone: "UTC" }).format(time);
}
