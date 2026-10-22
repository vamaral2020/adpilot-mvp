/**
 * Projection for the local, read-only Meta campaign recommendation preview.
 * Intentionally separate from the live Insights API: the historical snapshots
 * may be stale/incomplete and cannot be represented as real-time results.
 */
export type RecommendationEvidence = {
  name: string;
  value: number | null;
  limitation: string | null;
};

export type RecommendationAdvice = {
  ruleCode: string;
  campaignId: string | null;
  campaignName: string | null;
  accountId: string | null;
  currency: string | null;
  priority: "LOW" | "MEDIUM" | "HIGH";
  confidence: "INSUFFICIENT" | "LOW" | "MEDIUM" | "HIGH";
  title: string;
  justification: string;
  evidence: RecommendationEvidence[];
};

export type RecommendationPreview = {
  since: string;
  until: string;
  coverageVerified: boolean;
  metaSyncStatus: string | null;
  warnings: string[];
  recommendations: RecommendationAdvice[];
};

export type Spotlight = {
  kind: "advice" | "no-data" | "unavailable";
  title: string;
  explanation: string;
  campaignName: string | null;
  ruleCode: string | null;
  confidence: RecommendationAdvice["confidence"] | null;
  evidence: RecommendationEvidence[];
  currency: string | null;
  coverageVerified: boolean;
  syncUnconfirmed: boolean;
};

const ruleRank: Record<string, number> = {
  SPEND_WITHOUT_PURCHASES: 9,
  ROAS_BELOW_TARGET: 8,
  CPA_ABOVE_TARGET: 7,
  INVALID_CAMPAIGN_DATA: 6,
  CTR_DECLINING: 5,
  CPC_INCREASING: 4,
  LOW_SAMPLE: 3,
  NO_RECORDED_SPEND: 2,
  FAVORABLE_PERFORMANCE: 1,
  WITHIN_TARGETS: 1,
  MONITOR_PERFORMANCE: 0,
};
const priorityRank = { LOW: 1, MEDIUM: 2, HIGH: 3 };

function validAdvice(value: RecommendationAdvice): boolean {
  return typeof value?.ruleCode === "string"
    && typeof value?.title === "string" && typeof value?.justification === "string"
    && typeof value?.campaignId === "string" && value.campaignId.length > 0;
}

/**
 * Select an evidence-backed campaign insight over generic setup messages.
 * Missing historical records remain missing; never interpret them as zero.
 */
export function selectSpotlight(preview?: RecommendationPreview): Spotlight {
  const fallback = {
    campaignName: null, ruleCode: null, confidence: null, evidence: [],
    currency: null, coverageVerified: false, syncUnconfirmed: false,
  } as const;
  if (!preview || !Array.isArray(preview.recommendations)) {
    return {
      ...fallback, kind: "unavailable", title: "Recomendações indisponíveis",
      explanation: "Não foi possível consultar o histórico local de recomendações. Os indicadores da Meta acima são uma fonte diferente.",
    };
  }
  const candidates = preview.recommendations.filter(validAdvice).sort((a,b) =>
    priorityRank[b.priority] - priorityRank[a.priority]
    || (ruleRank[b.ruleCode] ?? 0) - (ruleRank[a.ruleCode] ?? 0)
    || (a.campaignName ?? a.campaignId ?? "").localeCompare(b.campaignName ?? b.campaignId ?? "")
  );
  const syncUnconfirmed = preview.warnings?.includes("META_SYNC_NOT_CONFIRMED_CURRENT") ?? false;
  if (candidates.length) {
    const top = candidates[0];
    return {
      kind: "advice", title: top.title, explanation: top.justification,
      campaignName: top.campaignName || top.campaignId,
      ruleCode: top.ruleCode, confidence: top.confidence,
      currency: top.currency, evidence: Array.isArray(top.evidence) ? top.evidence : [],
      coverageVerified: preview.coverageVerified === true, syncUnconfirmed,
    };
  }
  const noData = preview.recommendations.some(item => item.ruleCode === "NO_META_INSIGHTS");
  return {
    ...fallback,
    kind: "no-data",
    title: noData ? "Histórico de campanhas ainda indisponível" : "Configure suas metas para começar",
    explanation: noData
      ? "Ainda não há registros históricos da Meta para o período. Isso não significa que o investimento ou as vendas tenham sido zero."
      : "Defina ROAS mínimo e/ou CPA máximo na análise de recomendações para comparar o desempenho com os objetivos do negócio.",
    coverageVerified: preview.coverageVerified === true, syncUnconfirmed,
  };
}

export function spotlightMetric(spotlight: Spotlight, name: string): number | null {
  const item = spotlight.evidence.find(metric => metric.name === name);
  return typeof item?.value === "number" && Number.isFinite(item.value) && item.value >= 0
    ? item.value : null;
}
