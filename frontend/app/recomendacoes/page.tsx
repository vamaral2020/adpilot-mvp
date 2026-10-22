import Link from "next/link";
import { recommendationQualitySummary, type RecommendationDataQuality }
  from "../../lib/recommendation-spotlight";
import { money, number, resolvePeriod } from "../../lib/dashboard";
import { parseOptionalGoal } from "../../lib/recommendation-goals";

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
type LinkedGoal = { goalId:string; accountId:string; campaignId:string;
  productLabel:string; priceCents:number; targetCpa:number; targetRoas:number;
  linkedAt:string; };
type Report = {
  since: string; until: string; targetRoas: number | null; targetCpa: number | null;
  coverageVerified: boolean; generatedAt: string; metaSyncStatus: string | null;
  lastMetaSyncSuccess: string | null; warnings: string[]; recommendations: Advice[];
  quality?: RecommendationDataQuality | null;
  linkedGoals?: LinkedGoal[];
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
  TARGET_CPA: "CPA máximo",
  LINKED_GOAL_CPA: "CPA do produto associado",
  LINKED_GOAL_ROAS: "ROAS do produto associado",
  PERIOD_DAYS: "Dias selecionados",
  COLLECTED_DAYS: "Dias com consulta comprovada",
  UNCOLLECTED_DAYS: "Dias sem coleta comprovada",
  DAYS_WITHOUT_ROWS: "Dias consultados sem linhas",
  CTR_PREVIOUS_PCT: "CTR anterior",
  CTR_RECENT_PCT: "CTR recente", CPC_PREVIOUS: "CPC anterior",
  CPC_RECENT: "CPC recente", CTR_PCT: "CTR", CPC: "CPC",
};
const moneyMetrics = new Set(["SPEND","ATTRIBUTED_REVENUE","CPA","TARGET_CPA","LINKED_GOAL_CPA","CPC_PREVIOUS","CPC_RECENT","CPC"]);
const ratioMetrics = new Set(["ROAS","TARGET_ROAS","LINKED_GOAL_ROAS"]);
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
  // Normalize pt-BR comma decimals before forwarding to BigDecimal request params.
  // Invalid input is displayed, never silently omitted as "no business goals".
  const roasGoal=parseOptionalGoal(targetRoas,"ROAS mínimo",100);
  const cpaGoal=parseOptionalGoal(targetCpa,"CPA máximo",1000000);
  const validationError=roasGoal.error ?? cpaGoal.error;
  const result=period.error?{error:period.error}:validationError
    ? {error:validationError}
    : await load(period.since,period.until,roasGoal.value??"",cpaGoal.value??"");
  const report=result.data;
  const linked=new Map((report?.linkedGoals??[]).map(goal=>[
    goal.accountId+":"+goal.campaignId,goal
  ] as const));
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
      <p className="calcRecommendationPrompt">
        Vai criar uma campanha? <Link href="/calculadora">Planeje seu CPA e ROAS a partir da margem do produto →</Link>
        {" · "}<Link href="/planejamento">Associar produtos às campanhas →</Link>
      </p>
    </header>
    <div className="filterPanel">
      <form className="filterForm" method="GET">
        <label>De <input type="date" name="since" required defaultValue={period.since}/></label>
        <label>Até <input type="date" name="until" required defaultValue={period.until}/></label>
        <label>ROAS mínimo desejado (opcional; substitui a meta salva)
          <input type="text" inputMode="decimal" name="targetRoas"
            pattern="[0-9]+([.,][0-9]{1,4})?" maxLength={20}
            title="Informe um número positivo, como 3,4 ou 3.4 (até 4 casas decimais)."
            placeholder="Ex.: 3,4" defaultValue={targetRoas} />
        </label>
        <label>CPA máximo (R$; opcional, substitui a meta salva)
          <input type="text" inputMode="decimal" name="targetCpa"
            pattern="[0-9]+([.,][0-9]{1,4})?" maxLength={20}
            title="Informe um valor positivo, como 75 ou 75,50 (até 4 casas decimais)."
            placeholder="Ex.: 75,00" defaultValue={targetCpa}/>
        </label>
        <button type="submit">Analisar campanhas</button>
      </form>
    </div>
    {result.error && <div className="alertBanner error" role="alert">{result.error}</div>}
    {report && <>
      <p className="recommendationsAppliedGoals">
        <strong>Origem das metas:</strong>{" "}
        {report.targetRoas!==null || report.targetCpa!==null
          ? "Metas manuais preenchidas na consulta prevalecem nos respectivos campos."
          : "Metas salvas são aplicadas apenas às campanhas explicitamente associadas."}
        {" "}{(report.linkedGoals?.length??0)} vínculo(s) de produto encontrados neste período.
        <span>ROAS manual: {report.targetRoas!==null?number(report.targetRoas,2)+"x":"não informado"}.
          CPA manual: {report.targetCpa!==null?money(report.targetCpa,"BRL"):"não informado"}.
          Campos vazios usam a meta associada, se houver.</span>
      </p>
      <section className="recommendationsSummary" aria-label="Resumo da análise">
        <div><small>Campanhas com evidências</small><strong>{campaignCount}</strong></div>
        <div><small>Orientações geradas</small><strong>{report.recommendations.length}</strong></div>
        <div><small>Confiabilidade histórica</small>
          <strong>{report.coverageVerified?"Verificada":"Ainda não verificada"}</strong></div>
      </section>
      <section className="recommendationQualityPanel" aria-labelledby="recommendationQualityTitle">
        <div className="smallEyebrow">FONTE: META ADS · AUDITORIA DE COLETA</div>
        <h2 id="recommendationQualityTitle">Qualidade dos dados usados na análise</h2>
        <p>{recommendationQualitySummary(report.quality)}</p>
        {report.quality && <>
          <div className="recommendationQualityGrid">
            <div><small>Dias do período</small><strong>{report.quality.requestedDays}</strong></div>
            <div><small>Dias consultados</small><strong>{report.quality.collectedDays}</strong></div>
            <div><small>Sem coleta comprovada</small><strong>{report.quality.uncollectedDates.length}</strong></div>
            <div><small>Consultados sem linhas</small><strong>{report.quality.daysWithoutRows}</strong></div>
          </div>
          <p className="recommendationQualityGate">
            {report.quality.analysisEligible
              ? "Coleta recente e janela observada: permitidas apenas investigações consultivas, sem mudanças automáticas."
              : "Coleta incompleta ou não confirmada: o AdPilot prioriza verificar dados antes de sugerir investigações financeiras."}
          </p>
          <details className="recommendationQualityDetails">
            <summary>Ver datas sem coleta comprovada</summary>
            <p>{report.quality.uncollectedDates.length
              ? report.quality.uncollectedDates.join(", ")
              : "Não há dias sem comprovante de coleta Meta no período."}</p>
          </details>
        </>}
        <p className="recommendationQualityDisclaimer">
          Consulta concluída não prova atribuição definitiva da Meta, nem corresponde
          a pedidos individualmente conciliados na Nuvemshop. Dias sem linhas não
          significam compras ou investimento iguais a zero.
        </p>
      </section>
      <div className="alertBanner caution" role="status">
        <strong>Recomendações preliminares</strong>
        <span>As compras e receitas são atribuídas pela Meta e podem sofrer ajustes.
          Nenhuma orientação altera automaticamente orçamento, público ou anúncios.
          O status das janelas consultadas não certifica a completude financeira da loja.</span>
      </div>
      {report.warnings.includes("BUSINESS_GOALS_NOT_CONFIGURED") &&
        <p className="recommendationsHint">Há campanhas sem meta econômica associada.
          <Link href="/planejamento"> Associe o produto correspondente </Link>
          ou informe CPA e/ou ROAS manualmente. O AdPilot não cria associações automaticamente.</p>}
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
                {item.ruleCode==="DATA_QUALITY_LIMITED"?"Verificar dados":
                  item.confidence==="INSUFFICIENT"?"Evidência insuficiente":"Evidência preliminar"}
              </span>
            </div>
            <p>{item.justification}</p>
            {item.accountId && item.campaignId && linked.has(item.accountId+":"+item.campaignId) &&
              (() => {
                const goal=linked.get(item.accountId+":"+item.campaignId)!;
                return <p className="recommendationPlanEvidence">
                  <strong>Produto associado manualmente:</strong> {goal.productLabel}
                  {" · "}CPA salvo {money(goal.targetCpa,"BRL")}
                  {" · "}ROAS salvo {number(goal.targetRoas,2)}x.
                  {report.targetRoas!==null || report.targetCpa!==null
                    ? " Metas manuais informadas substituem os campos correspondentes nesta análise."
                    : " Estas metas estão sendo usadas como referência econômica nesta campanha."}
                  {" "}Considera uma unidade por pedido; não comprova que cada compra Meta
                  corresponde a esse produto.
                </p>;
              })()}
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
