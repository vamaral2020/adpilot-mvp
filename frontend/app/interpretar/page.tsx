import Link from "next/link";
import {money, number} from "../../lib/dashboard";
import {interpreterPeriod, utcToday} from "../../lib/interpreter-period";

export const dynamic="force-dynamic";

type Campaign={id:string;name:string;objective:string|null;effective_status:string|null};
type Page={data:Campaign[];after:string|null};
type Coverage={complete:boolean;pagesFetched:number;rowsFetched:number};
type Adset={id:string;name:string|null;optimizationGoal:string;promotedEvent:string;
  billingEvent:string;bidStrategy:string;destinationType:string;
  effectiveStatus:string;interpretation:string};
type Evidence={level:string;id:string;adsetId:string;spend:number;impressions:number;
  allClicks:number;linkClicks:number|null;landingPageViews:number|null;
  addToCarts:number|null;attributedPurchases:number|null;
  attributedPurchaseValue:number|null;attributionSetting:string;
  linkCtrPercent:number|null;costPerLinkClick:number|null;
  attributedCpa:number|null;attributedRoas:number|null;evidenceQuality:string};
type Report={campaignId:string;campaignObjective:string;campaignStatus:string;
  currency:string;since:string;until:string;analysisMode:string;
  partialCurrentDay:boolean;complete:boolean;
  adsetConfigurationCoverage:Coverage;adsetInsightsCoverage:Coverage;
  adInsightsCoverage:Coverage;adsets:Adset[];adsetInsights:Evidence[];
  adInsights:Evidence[];warnings:string[];principles:string[]};
type Params={campaignId?:string|string[];since?:string|string[];until?:string|string[]};

async function read<T>(path:string):Promise<{data?:T;error?:string}> {
  const user=process.env.ADPILOT_ADMIN_USERNAME??"";
  const password=process.env.ADPILOT_ADMIN_PASSWORD??"";
  if(!user||!password) return {error:"Credenciais internas não configuradas."};
  try {
    const base=process.env.INTERNAL_API_URL??"http://localhost:18080";
    const response=await fetch(base+path,{
      headers:{Authorization:"Basic "+Buffer.from(user+":"+password).toString("base64")},
      cache:"no-store",signal:AbortSignal.timeout(45000),
    });
    if(!response.ok) return {error:"Consulta à Meta indisponível (HTTP "+response.status+
      "). Verifique permissões, parâmetros e conexão."};
    return {data:await response.json() as T};
  }catch{return {error:"Não foi possível consultar a Meta."};}
}

const objectives:Record<string,string>={
  OUTCOME_SALES:"Vendas",OUTCOME_TRAFFIC:"Tráfego",OUTCOME_ENGAGEMENT:"Engajamento",
  OUTCOME_LEADS:"Cadastros",OUTCOME_AWARENESS:"Reconhecimento",OUTCOME_APP_PROMOTION:"App"};
const interpretation:Record<string,string>={
  REVIEW_SALES_OPTIMIZATION:"Campanha de vendas otimizada para tráfego ou alcance. Revise o objetivo de otimização e o evento de compra antes de alterar.",
  PURCHASE_EVENT_DECLARED:"Evento Purchase declarado no conjunto; confirme a qualidade dos eventos enviados à Meta.",
  CONVERSION_EVENT_NOT_VERIFIED:"Otimização para conversões declarada, mas o evento de compra não foi confirmado nesta leitura.",
  VALUE_OPTIMIZATION_DECLARED:"Otimização para valor declarada; verifique receita, margem e qualidade da atribuição.",
  OBJECTIVE_NOT_SALES:"Campanha com outro objetivo; não julgue sua eficiência apenas pelo CPA de compra.",
  OPTIMIZATION_UNKNOWN:"Meta não forneceu parâmetros suficientes para interpretar com segurança."};
const fmt=(v:number|null|undefined,digits=2)=>
  v===null||v===undefined||!Number.isFinite(v)?"Não informado":number(v,digits);
const moneyValue=(v:number|null|undefined,currency:string)=>
  v===null||v===undefined||!Number.isFinite(v)?"Não informado":money(v,currency);

function pastSevenDays():{since:string;until:string} {
  const now=new Date();
  const end=new Date(Date.UTC(now.getUTCFullYear(),now.getUTCMonth(),now.getUTCDate()-1));
  const start=new Date(Date.UTC(end.getUTCFullYear(),end.getUTCMonth(),end.getUTCDate()-6));
  return {since:start.toISOString().slice(0,10),until:end.toISOString().slice(0,10)};
}
 function rowsTable(title:string,rows:Evidence[],currency:string) {
  return <section className="historySummary">
    <h2>{title}</h2>
    <p className="historySmall">Métricas atribuídas pela Meta; ações ausentes não equivalem a zero.
      Valores são agregados no intervalo selecionado.</p>
    {rows.length===0?<p>Sem linhas de Insights no intervalo consultado.</p>:
      <div style={{overflowX:"auto"}}>
        <table>
          <thead><tr><th>ID</th><th>Gasto</th><th>Cliques totais</th>
            <th>Cliques no link</th><th>Visitas destino</th><th>Carrinhos</th>
            <th>Compras atribuídas</th><th>CPA atribuído</th><th>ROAS atribuído</th>
            <th>Atribuição</th></tr></thead>
          <tbody>{rows.map(r=><tr key={r.id}>
            <td>{r.id}</td><td>{moneyValue(r.spend,currency)}</td>
            <td>{fmt(r.allClicks,0)}</td><td>{fmt(r.linkClicks,0)}</td>
            <td>{fmt(r.landingPageViews,0)}</td><td>{fmt(r.addToCarts,0)}</td>
            <td>{fmt(r.attributedPurchases,0)}</td>
            <td>{moneyValue(r.attributedCpa,currency)}</td>
            <td>{r.attributedRoas===null?"Não informado":fmt(r.attributedRoas)+"x"}</td>
            <td>{r.attributionSetting==="UNKNOWN"?"Não informada":r.attributionSetting}</td>
          </tr>)}</tbody>
        </table>
      </div>}
  </section>;
}

export default async function InterpreterPage({searchParams}:{searchParams:Promise<Params>}) {
  const p=await searchParams;
  const campaignId=typeof p.campaignId==="string"&&/^\d{1,40}$/.test(p.campaignId)
    ?p.campaignId:"";
  const defaults=pastSevenDays();
  const since=typeof p.since==="string"?p.since:defaults.since;
  const until=typeof p.until==="string"?p.until:defaults.until;
  const period=interpreterPeriod(since,until,utcToday());
  const valid=period.valid;
  const listing=await read<Page>("/api/v1/meta/campaigns?limit=100");
  const campaigns=listing.data?.data??[];
  const exists=campaigns.some(c=>c.id===campaignId);
  const report=campaignId&&valid&&exists
    ?await read<Report>("/api/v1/meta/campaigns/"+campaignId+
        "/delivery-diagnostics?"+new URLSearchParams({since,until}).toString())
    :null;
  return <main className="historyShell">
    <nav className="historyNav"><Link href="/">← Visão geral</Link>
      <strong>AdPilot / Intérprete Meta</strong></nav>
    <header className="historyHeader">
      <div className="smallEyebrow">META ADS · INSIGHTS E CONFIGURAÇÃO</div>
      <h1>Intérprete de campanhas</h1>
      <p>Entenda o objetivo declarado, os sinais de entrega e os gargalos do funil
        antes de escolher sua próxima estratégia. Nenhuma configuração é alterada.</p>
    </header>
    {listing.error&&<p className="alertBanner error">{listing.error}</p>}
    <section className="filterPanel">
      <form className="filterForm" method="GET">
        <label htmlFor="campaignId">Campanha
          <select id="campaignId" name="campaignId" defaultValue={exists?campaignId:""}
            required>
            <option value="" disabled>Selecione uma campanha</option>
            {campaigns.map(c=><option value={c.id} key={c.id}>
              {c.name} — {objectives[c.objective??""]??"Objetivo não informado"}
            </option>)}
          </select>
        </label>
        <label htmlFor="since">De
          <input id="since" type="date" name="since" defaultValue={since} required/>
        </label>
        <label htmlFor="until">Até
          <input id="until" type="date" name="until" defaultValue={until} required/>
        </label>
        <button type="submit">Interpretar campanha</button>
      </form>
      <p className="historySmall">Até 31 dias (referência UTC), incluindo hoje para
        monitoramento provisório. Dias anteriores permitem análise histórica contextual;
        a coleta não altera orçamento ou anúncios.</p>
    </section>
    {!valid&&<p className="alertBanner error">Escolha um intervalo válido de até
      31 dias, sem datas futuras. Hoje é permitido para monitoramento parcial.</p>}
    {valid&&period.partialCurrentDay&&!report?.data&&
      <p className="alertBanner caution">Monitoramento de hoje: métricas parciais,
        sujeitas a atraso de atribuição e a alterações ao longo do dia.
        Não tome decisões de orçamento com base apenas nesta leitura.</p>}
    {campaignId&&!exists&&<p className="alertBanner caution">
      A campanha selecionada não está na primeira página de campanhas. Selecione uma listada.
    </p>}
    {listing.data?.after&&<p className="alertBanner caution">
      Há mais campanhas além das primeiras 100. A lista é parcial.</p>}
    {report?.error&&<p className="alertBanner error">{report.error}</p>}
    {report?.data&&<InterpreterReport report={report.data}/>}
    {!campaignId&&<section className="historySummary">
      <h2>Comece pela campanha de vendas</h2>
      <p>Selecione uma campanha para comparar otimização declarada, cliques, visitas,
        carrinhos e compras atribuídas. O relatório não faz recomendações automáticas
        de redução ou aumento de orçamento.</p></section>}
  </main>;
}

function InterpreterReport({report:r}:{report:Report}) {
  return <>
    {r.partialCurrentDay&&<p className="alertBanner caution" role="status">
      MONITORAMENTO DE HOJE — RESULTADOS PARCIAIS. Investimento, cliques,
      carrinhos e compras atribuídas ainda podem mudar. Não use este período
      como resultado final, nem para pausar ou aumentar orçamentos.
      A Meta utiliza o fuso da conta, que pode diferir da referência UTC do filtro.
    </p>}
    {!r.partialCurrentDay&&<p className="alertBanner caution">
      ANÁLISE HISTÓRICA — período de dias anteriores, mas os eventos atribuídos
      ainda podem ser revisados pela Meta. Dados não demonstram lucro ou causalidade.
    </p>}
    <section className="historySummary">
      <h2>Configuração observada</h2>
      <p>Objetivo: <strong>{objectives[r.campaignObjective]??"Não informado"}</strong>
        {" · "}Moeda: <strong>{r.currency}</strong>
        {" · "}Status: {r.campaignStatus}</p>
      <p>Conjuntos: {r.adsets.length} · Insights de conjuntos: {r.adsetInsights.length}
        {" · "}Insights de anúncios: {r.adInsights.length}</p>
    </section>
    {!r.complete&&<p className="alertBanner caution">
      Relatório parcial: o limite de paginação foi atingido. Não use as linhas
      abaixo para ranquear todas as peças ou distribuir orçamento.</p>}
    <section className="historySummary">
      <h2>O que a configuração sugere verificar</h2>
      {r.adsets.length===0?<p>Sem conjuntos retornados.</p>:
        r.adsets.map(a=><article key={a.id} style={{marginBottom:"1rem"}}>
          <h3>{a.name??a.id}</h3>
          <p>Otimização: <strong>{a.optimizationGoal}</strong> · Evento declarado:
            {" "}{a.promotedEvent} · Lance: {a.bidStrategy} · Destino: {a.destinationType}</p>
          <p>{interpretation[a.interpretation]??"Configuração não interpretável."}</p>
        </article>)}
    </section>
    {rowsTable("Desempenho por conjunto",r.adsetInsights,r.currency)}
    {rowsTable("Desempenho por anúncio",r.adInsights,r.currency)}
    <section className="historySummary">
      <h2>Limitações da leitura</h2>
      <p>As métricas de compra e ROAS são atribuições da Meta, não vendas
        confirmadas na Nuvemshop nem prova de aumento incremental de vendas.
        O AdPilot não verificou a composição efetiva dos públicos,
        Advantage+ Audience ou a fase de aprendizado.</p>
      <ul>{r.warnings.map(w=><li key={w}>{w.replaceAll("_"," ").toLowerCase()}</li>)}</ul>
    </section>
  </>;
}
