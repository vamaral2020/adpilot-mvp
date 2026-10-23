import type { CampaignResult, HistoryCoverage, MetaDay, StoreDay } from "./history";

/**
 * Read-only, historical two-source comparison. Never an order↔campaign join.
 * Numeric amounts are source-reported observations, not verified net sales.
 */
export type MetaObserved = {
  currency:string|null; spend:number|null; attributedPurchases:number|null;
  attributedRevenue:number|null; reportedDays:number;
};
export type StoreObserved = {
  currency:string|null; identifiedPaidOrders:number|null;
  identifiedPaidRevenue:number|null; reportedDays:number;
};
export type DayComparison = {
  date:string;meta:MetaDay[];store:StoreDay[];
};
export type ComparisonStatus =
  "INDICATIVE_ONLY"|"INSUFFICIENT_OBSERVATIONS"|"AMBIGUOUS_CURRENCY"|
  "CURRENCY_MISMATCH"|"META_COVERAGE_UNVERIFIED"|"SYNC_EVIDENCE_INCOMPLETE";
export type CommercialReconciliation = {
  since:string;until:string;comparisonStatus:ComparisonStatus;
  financiallyVerified:boolean;ordersMatchedToCampaigns:boolean;
  meta:MetaObserved;store:StoreObserved;
  indicativeMer:number|null;indicativeAttributedRoas:number|null;
  daily:DayComparison[];campaigns:CampaignResult[];
  coverage:HistoryCoverage|null;warnings:string[];
};

const headings:Record<ComparisonStatus,string>={
  INDICATIVE_ONLY:"Comparação indicativa disponível",
  INSUFFICIENT_OBSERVATIONS:"Registros insuficientes",
  AMBIGUOUS_CURRENCY:"Moedas não identificadas de forma única",
  CURRENCY_MISMATCH:"Moedas diferentes entre as fontes",
  META_COVERAGE_UNVERIFIED:"Cobertura Meta insuficiente",
  SYNC_EVIDENCE_INCOMPLETE:"Sincronização ou comprovantes insuficientes",
};
const summaries:Record<ComparisonStatus,string>={
  INDICATIVE_ONLY:"Os valores observados podem ser colocados lado a lado, mas a cobertura integral das vendas por data de pagamento não está comprovada. MER é apenas indicativo.",
  INSUFFICIENT_OBSERVATIONS:"Uma ou ambas as fontes não têm registros históricos suficientes. Ausência de linhas não significa vendas ou gasto zero.",
  AMBIGUOUS_CURRENCY:"Há mais de uma moeda, valores inválidos ou ausência de identificação. Totais financeiros não serão somados entre moedas.",
  CURRENCY_MISMATCH:"Os valores da Meta e da Nuvemshop têm moedas diferentes. Nenhuma conversão cambial foi presumida.",
  META_COVERAGE_UNVERIFIED:"Existem dias ou contas Meta sem cobertura de coleta suficiente, ou o dia atual ainda não terminou. Não calculamos MER.",
  SYNC_EVIDENCE_INCOMPLETE:"Uma fonte não possui recibos de coleta bem-sucedida ou sua última execução falhou. A comparação financeira está bloqueada.",
};
export function comparisonTitle(status:ComparisonStatus):string {
  return headings[status]??"Estado da comparação indisponível";
}
export function comparisonDescription(status:ComparisonStatus):string {
  return summaries[status]??"Não foi possível confirmar a qualidade dos dados.";
}

/** Never expose a numeric MER as audited or use it for a non-indicative state. */
export function safeIndicativeMer(value:CommercialReconciliation|null|undefined):number|null {
  if(!value || value.comparisonStatus!=="INDICATIVE_ONLY"
      || value.financiallyVerified || value.ordersMatchedToCampaigns
      || typeof value.indicativeMer!=="number"
      || !Number.isFinite(value.indicativeMer) || value.indicativeMer<0)
    return null;
  return value.indicativeMer;
}

/** Missing day rows aren't observed zeros; ambiguous day currencies aren't merged. */
export function dailyAmount(rows:MetaDay[]|StoreDay[],key:"spend"|"paidRevenue"):number|null {
  if(!Array.isArray(rows)||rows.length!==1) return null;
  const item=rows[0] as unknown as Record<string,unknown>;
  const value=item[key];
  return typeof value==="number" && Number.isFinite(value) && value>=0?value:null;
}
