import Link from "next/link";
import { money, number, resolvePeriod } from "../../lib/dashboard";

export const dynamic = "force-dynamic";

type Metric = { name: string; value: number | null; limitation: string | null };
type Advice = {
  ruleCode: string; ruleVersion: number; accountId: string | null;
  campaignId: string | null; campaignName: string | null; currency: string | null;
  priority: "LOW" | "MEDIUM" | "HIGH";
  confidence: "INSUFFICIENT" | "LOW" | "MEDIUM" | "HIGH";
  actionCode: string; title: string; justification: string;
  evidence: Metric[]; limitations: string[];
};
type Report = {
  since: string; until: string; targetRoas: number | null; targetCpa: number | null;
  coverageVerified: boolean; generatedAt: string; metaSyncStatus: string | null;
  lastMetaSyncSuccess: string | null; warnings: string[]; recommendations: Advice[];
};
type Params = {
  since?: string | string[]; until?: string | string[];
  targetRoas?: string | string[]; targetCpa?: string | string[];
};

const metricLabels: Record<string,string> = {
  SPEND: "Investimento Meta", ATTRIBUTED_PURCHASES: "Compras atribuídas",
  ATTRIBUTED_REVENUE: "Receita atribuída", ROAS: "ROAS atribuído",
  CPA: "CPA atribuído", IMPRESSIONS: "Impressões", CLICKS: "Cliques totais",
  OBSERVED_DAYS: "Dias com registros", TARGET_ROAS: "ROAS-alvo",
  TARGET_CPA: "CPA máximo", CTR_PREVIOUS_PCT: "CTR anterior",
  CTR_RECENT_PCT: "CTR recente", CPC_PREVIOUS: "CPC anterior",
  CPC_RECENT: "CPC recente", CTR_PCT: "CTR", CPC: "CPC",
};
const moneyMetrics = new Set(["SPEND","ATTRIBUTED_REVENUE","CPA","TARGET_CPA","CPC_PREVIOUS","CPC_RECENT","CPC"]);
const ratioMetrics = new Set(["ROAS","TARGET_ROAS"]);
const pctMetrics = new Set(["CTR_PREVIOUS_PCT","CTR_RECENT_PCT","CTR_PCT"]);
function readableMetric(metric: Metric, currency: string | null): string {
  if (metric.value === null || !Number.isFinite(metric.value)) return "—";
  if (moneyMetrics.has(metric.name)) return money(metric.value,currency ?? "BRL");
  if (ratioMetrics.has(metric.name)) return number(metric.value,2) + "x";
  if (pctMetrics.has(metric.name)) return number(metric.value,2) + "%";
  return number(metric.value,2);
}

async function load(since: string, until: string, targetRoas: string, targetCpa: string):
    Promise<{ data?: Report; error?: string }> {
  const username=process.env.ADPILOT_ADMIN_USERNAME ?? "";
  const password=process.env.ADPILOT_ADMIN_PASSWORD ?? "";
  if (!username || !password) return {error:"Credenciais internas indisponíveis."};
  const q=new URLSearchParams({since,until});
  if (targetRoas) q.set("targetRoas",targetRoas);
  if (targetCpa) q.set("targetCpa",targetCpa);
  try {
    const base=process.env.INTERNAL_API_URL ?? "http://localhost:18080";
    const response=await fetch(base + "/api/v1/analytics/recommendations?" + q,{
      headers:{Authorization:"Basic "+Buffer.from(username+":"+password).toString("base64")},
      cache:"no-store",signal:AbortSignal.timeout(15000),
    });
    if(!response.ok) return {error:"Não foi possível avaliar as campanhas (HTTP "+response.status+"). Confira o período e as metas."};
    return {data:await response.json() as Report};
  }catch{
    return {error:"Não foi possível consultar as recomendações locais."};
  }
}

export default async function RecommendationsPage({searchParams}:{searchParams:Promise<Params>}) {
  const raw=await searchParams;
  const period=resolvePeriod(typeof raw.since==="string"?raw.since:undefined,
    typeof raw.until==="string"?raw.until:undefined);
  const targetRoas=typeof raw.targetRoas==="string"?raw.targetRoas:"";
  const targetCpa=typeof raw.targetCpa==="string"?raw.targetCpa:"";
  const result=period.error?{error:period.error}:await load(period.since,period.until,targetRoas,targetCpa);
  const report=result.data;
  const campaignCount=new Set(report?.recommendations.filter(x=>x.campaignId).map(x=>
    x.accountId+":"+x.campaignId) ?? []).size;
  return <main className="historyShell">
    <nav className="historyNav">
      <Link href="/historico">← Histórico das campanhas</Link>
      <strong>AdPilot / Recomendações</strong>
    </nav>
    <header className="historyHeader">
      <div className="smallEyebrow">PR 017 · ANÁLISE CONSULTIVA</div>
      <h1>O que merece atenção<span className="titlePeriod">?</span></h1>
      <p>Orientações baseadas em compras atribuídas pela Meta, investimento e tendências.
        Nenhuma campanha ou orçamento é alterado pelo AdPilot.</p>
    </header>
    <div className="filterPanel">
      <form className="filterForm" method="GET">
        <label>De <input type="date" name="since" required defaultValue={period.since}/></label>
        <label>Até <input type="date" name="until" required defaultValue={period.until}/></label>
        <label>ROAS mínimo desejado
          <input type="number" name="targetRoas" min="0.0001" max="100" step="0.01"
            placeholder="Ex.: 3" defaultValue={targetRoas} />
        </label>
        <label>CPA máximo (R$)
          <input type="number" name="targetCpa" min="0.0001" max="1000000" step="0.01"
            placeholder="Ex.: 50" defaultValue={targetCpa}/>
        </label>
        <button type="submit">Analisar campanhas</button>
      </form>
    </div>
    {result.error && <div className="alertBanner error" role="alert">{result.error}</div>}
    {report && <>
      <section className="recommendationsSummary" aria-label="Resumo da análise">
        <div><small>Campanhas com evidências</small><strong>{campaignCount}</strong></div>
        <div><small>Orientações geradas</small><strong>{report.recommendations.length}</strong></div>
        <div><small>Confiabilidade histórica</small>
          <strong>{report.coverageVerified?"Verificada":"Ainda não verificada"}</strong></div>
      </section>
      <div className="alertBanner caution" role="status">
        <strong>Recomendações preliminares</strong>
        <span>Os resultados da Meta são atribuídos pela plataforma, não vinculados a pedidos
          individuais da loja. A cobertura histórica ainda não está comprovada e conversões
          podem sofrer ajustes posteriores. Por isso, nenhuma recomendação autoriza pausar,
          aumentar ou reduzir automaticamente o orçamento.</span>
      </div>
      {report.warnings.includes("BUSINESS_GOALS_NOT_CONFIGURED") &&
        <p className="recommendationsHint">Informe ROAS desejado e/ou CPA máximo para comparar
          suas campanhas com objetivos reais. Sem essas metas, a análise é principalmente informativa.</p>}
      <section className="recommendationsList" aria-label="Recomendações por campanha">
        {report.recommendations.map((item,index)=>
          <article className="recommendationCard" key={item.ruleCode+":"+(item.campaignId??"account")+":"+index}>
            <div className="recommendationCardTop">
              <div>
                <small className="smallEyebrow">{item.campaignId?
                    "CAMPANHA · "+(item.campaignName??item.campaignId):"CONFIGURAÇÃO / QUALIDADE DOS DADOS"}</small>
                <h2>{item.title}</h2>
                {item.campaignId && <small className="recommendationCampaignId">ID {item.campaignId}</small>}
              </div>
              <span className={"recommendationTag "+(item.priority==="MEDIUM"?"recommendationTagMedium":"")}>
                {item.confidence==="INSUFFICIENT"?"Evidência insuficiente":"Evidência preliminar"}
              </span>
            </div>
            <p>{item.justification}</p>
            {item.evidence.length>0 && <details className="recommendationEvidence">
              <summary>Ver métricas que fundamentam a análise</summary>
              <div className="recommendationEvidenceGrid">
                {item.evidence.map(metric=><div key={metric.name}>
                  <span>{metricLabels[metric.name] ?? metric.name}</span>
                  <strong>{readableMetric(metric,item.currency)}</strong>
                </div>)}
              </div>
            </details>}
            <p className="recommendationFootnote">Regra {item.ruleCode} · v{item.ruleVersion} ·
              Prioridade {item.priority==="MEDIUM"?"moderada":"baixa"} ·
              Ação sugerida: {item.actionCode==="INVESTIGATE"?"investigar":
              item.actionCode==="CONFIGURE_TARGETS"?"configurar metas":
              item.actionCode==="REVIEW_DATA"?"verificar dados":"acompanhar"}</p>
          </article>)}
      </section>
      <p className="historySmall">Consulta de recomendações: somente leitura, sem alterar dados das
        campanhas. Dias e limites seguem a conta Meta Ads. Não inferimos vendas orgânicas.
        {report.metaSyncStatus? " Última sincronização Meta: "+report.metaSyncStatus+".":""}</p>
    </>}
  </main>;
}
