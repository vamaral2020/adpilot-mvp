import Link from "next/link";
import { money, number, multiplier, presetPeriod, resolvePeriod } from "../../lib/dashboard";
import {
  comparisonDescription, comparisonTitle, safeIndicativeMer, dailyAmount,
  type CommercialReconciliation,
} from "../../lib/reconciliation";

export const dynamic = "force-dynamic";
type Params = { since?:string|string[];until?:string|string[] };
type Result = {data?:CommercialReconciliation;error?:string};

async function load(since:string,until:string):Promise<Result> {
  const username=process.env.ADPILOT_ADMIN_USERNAME??"";
  const password=process.env.ADPILOT_ADMIN_PASSWORD??"";
  if(!username||!password) return {error:"Credenciais internas não configuradas."};
  const base=process.env.INTERNAL_API_URL??"http://localhost:18080";
  try {
    const params=new URLSearchParams({since,until});
    const response=await fetch(base+"/api/v1/analytics/reconciliation?"+params,{
      headers:{Authorization:"Basic "+Buffer.from(username+":"+password).toString("base64")},
      cache:"no-store",signal:AbortSignal.timeout(20000),
    });
    if(!response.ok) return {error:"Falha ao consultar a conciliação (HTTP "+response.status+")."};
    return {data:await response.json() as CommercialReconciliation};
  }catch {
    return {error:"A comparação histórica não está disponível."};
  }
}

function Metric({label,value,help}:{label:string;value:string;help:string}) {
  return <article className="historyCard historyMetric">
    <span>{label}</span><strong>{value}</strong><small>{help}</small>
  </article>;
}

export default async function ReconciliationPage({searchParams}:{
  searchParams:Promise<Params>;
}) {
  const query=await searchParams;
  const period=resolvePeriod(typeof query.since==="string"?query.since:undefined,
    typeof query.until==="string"?query.until:undefined);
  const response=period.error?{error:period.error}:await load(period.since,period.until);
  const value=response.data;
  const seven=presetPeriod(7),thirty=presetPeriod(30);
  const metaCurrency=value?.meta?.currency??undefined;
  const shopCurrency=value?.store?.currency??undefined;
  const mer=safeIndicativeMer(value);
  const metaRoas=value?.comparisonStatus==="INDICATIVE_ONLY" &&
    typeof value.indicativeAttributedRoas==="number" &&
    Number.isFinite(value.indicativeAttributedRoas)
    ? value.indicativeAttributedRoas:null;
  const metaCoverage=value?.coverage?.meta;

  return <main className="historyShell">
    <nav className="historyNav">
      <Link href="/">← Visão geral</Link>
      <strong>AdPilot / Conciliação comercial</strong>
    </nav>
    <header className="historyHeader">
      <div className="smallEyebrow">FINANÇAS · FONTES INDEPENDENTES · SOMENTE LEITURA</div>
      <h1>Conciliação comercial<span className="titlePeriod">.</span></h1>
      <p>Compare o gasto e as compras atribuídas pela Meta com os pagamentos
        identificados na Nuvemshop, sem presumir que um anúncio gerou cada pedido.</p>
    </header>

    <div className="filterPanel">
      <div className="periodPills">
        <Link href={"/conciliacao?since="+seven.since+"&until="+seven.until}
          className="periodPill">7 dias completos</Link>
        <Link href={"/conciliacao?since="+thirty.since+"&until="+thirty.until}
          className="periodPill">30 dias completos</Link>
      </div>
      <form className="filterForm" method="GET">
        <label>De <input name="since" type="date" required defaultValue={period.since}/></label>
        <label>Até <input name="until" type="date" required defaultValue={period.until}/></label>
        <button type="submit">Comparar fontes</button>
      </form>
    </div>

    {response.error&&<div className="alertBanner error" role="alert">{response.error}</div>}
    {value&&<>
      <section className="historyCard reconciliationState" aria-labelledby="reconciliation-state">
        <div className="historySectionHead">
          <div>
            <div className="smallEyebrow">LIMITES DE CONFIANÇA</div>
            <h2 id="reconciliation-state">{comparisonTitle(value.comparisonStatus)}</h2>
          </div>
          <span className="historySourceTag">Não auditado</span>
        </div>
        <p>{comparisonDescription(value.comparisonStatus)}</p>
        <p className="historySmall">
          <strong>Pedidos vinculados a campanhas:</strong> Não.{" "}
          <strong>Conciliação financeira comprovada:</strong> Não.
          A Meta registra atribuições e a Nuvemshop armazena pedidos pagos de todos
          os canais. Não há correspondência por pedido nem controle de devoluções,
          custo dos produtos, frete e impostos.
        </p>
      </section>

      <div className="reconciliationSources">
        <section className="historyStorePanel" aria-labelledby="reconciliation-shop">
          <div className="historySectionHead">
            <div>
              <div className="smallEyebrow">NUVEMSHOP · TODOS OS CANAIS</div>
              <h2 id="reconciliation-shop">Pagamentos identificados</h2>
            </div>
            <span className="historySourceTag">Snapshots locais</span>
          </div>
          <div className="reconciliationMetrics">
            <Metric label="Receita paga identificada"
              value={money(value.store.identifiedPaidRevenue,shopCurrency)}
              help="Pedidos elegíveis com paid_at. Não é faturamento auditado."/>
            <Metric label="Pedidos pagos identificados"
              value={number(value.store.identifiedPaidOrders)}
              help="Sem classificação automática como orgânico ou anúncio."/>
            <Metric label="Dias com registros pagos"
              value={number(value.store.reportedDays)}
              help="Dias presentes nos snapshots por data UTC de pagamento."/>
          </div>
        </section>

        <section className="historyOutcomePanel" aria-labelledby="reconciliation-meta">
          <div className="historySectionHead">
            <div>
              <div className="smallEyebrow">META ADS · ATRIBUIÇÃO DA PLATAFORMA</div>
              <h2 id="reconciliation-meta">Investimento e atribuições</h2>
            </div>
            <span className="historySourceTag">Insights históricos</span>
          </div>
          <div className="reconciliationMetrics">
            <Metric label="Investimento observado"
              value={money(value.meta.spend,metaCurrency)}
              help="Gasto nas campanhas retornadas pela Meta."/>
            <Metric label="Compras atribuídas"
              value={number(value.meta.attributedPurchases,2)}
              help="Conversões da Meta; não são pedidos pagos conciliados."/>
            <Metric label="Receita atribuída"
              value={money(value.meta.attributedRevenue,metaCurrency)}
              help="Estimativa de atribuição reportada pela Meta."/>
          </div>
        </section>
      </div>

      <section className="historyCard" aria-labelledby="reconciliation-ratios">
        <div className="historySectionHead">
          <div>
            <h2 id="reconciliation-ratios">Indicadores comparativos</h2>
            <p>Somente quando há moeda compatível, registros identificados,
              evidências suficientes de sincronização e cobertura Meta.</p>
          </div>
          <span className="historySourceTag">Não auditado</span>
        </div>
        <div className="reconciliationRatios">
          <div><small>MER indicativo · snapshots</small>
            <strong>{multiplier(mer)}</strong>
            <p>Receita paga identificada ÷ gasto Meta.
              Não comprova a receita integral da loja.</p></div>
          <div><small>ROAS atribuído · Meta</small>
            <strong>{multiplier(metaRoas)}</strong>
            <p>Receita atribuída pela Meta ÷ gasto Meta.
              Não é ROAS real nem lucro.</p></div>
          <div><small>Lucro / CAC / vendas orgânicas</small>
            <strong>Indisponível</strong>
            <p>Exigem custos completos, pagamentos auditados
              ou associação individual de origem dos pedidos.</p></div>
        </div>
        {mer===null&&<p className="historySmall">
          O MER não é exibido neste intervalo porque os critérios de comparação
          ainda não foram satisfeitos. Isso não significa resultado igual a zero.
        </p>}
      </section>

      <section className="historyCard" aria-labelledby="reconciliation-daily">
        <div className="historySectionHead">
          <div><h2 id="reconciliation-daily">Datas observadas nas duas fontes</h2>
            <p>Meta: dia reportado no fuso da conta. Loja: dia UTC de pagamento.
              Linhas não informadas aparecem como “—”, nunca como zero.</p></div>
        </div>
        {value.daily.length===0?<p className="historySmall">
          Não existem registros para mostrar. A ausência de linhas não comprova
          ausência de vendas ou investimento.</p>:
        <div className="tableScroll"><table className="historyCampaignTable">
          <thead><tr><th>Dia</th><th>Gasto Meta</th><th>Receita atribuída Meta</th>
            <th>Pedidos pagos identificados</th><th>Receita paga identificada</th></tr></thead>
          <tbody>{value.daily.map(day=>{
            const meta=day.meta.length===1?day.meta[0]:null;
            const shop=day.store.length===1?day.store[0]:null;
            const sourceMetaCurrency=meta?.currency??undefined;
            const sourceShopCurrency=shop?.currency??undefined;
            return <tr key={day.date}>
              <td>{day.date}</td>
              <td>{money(dailyAmount(day.meta,"spend"),sourceMetaCurrency)}</td>
              <td>{money(meta?.attributedRevenue??null,sourceMetaCurrency)}</td>
              <td>{number(shop?.paidOrders??null)}</td>
              <td>{money(dailyAmount(day.store,"paidRevenue"),sourceShopCurrency)}</td>
            </tr>;
          })}</tbody>
        </table></div>}
      </section>

      <section className="historyCard" aria-labelledby="reconciliation-campaigns">
        <div className="historySectionHead">
          <div><h2 id="reconciliation-campaigns">Atribuição por campanha</h2>
            <p>Exclusivamente indicadores da Meta. Os pagamentos da loja não
              são distribuídos entre campanhas.</p></div>
          <Link className="historyRecommendationsLink"
            href={"/recomendacoes?since="+period.since+"&until="+period.until}>
            Analisar campanhas →
          </Link>
        </div>
        {value.campaigns.length===0?<p className="historySmall">
          Não há campanhas com registros retornados neste período.</p>:
        <div className="tableScroll"><table className="historyCampaignTable">
          <thead><tr><th>Campanha</th><th>Investimento</th>
            <th>Compras atribuídas</th><th>Receita atribuída</th></tr></thead>
          <tbody>{value.campaigns.map(c=><tr
            key={c.accountId+":"+c.campaignId+":"+c.currency}>
            <td><strong>{c.name}</strong><span className="historyCampaignId">ID: {c.campaignId}</span></td>
            <td>{money(c.spend,c.currency)}</td>
            <td>{number(c.attributedPurchases,2)}</td>
            <td>{money(c.attributedRevenue,c.currency)}</td>
          </tr>)}</tbody>
        </table></div>}
      </section>

      <section className="historyCard" aria-labelledby="reconciliation-coverage">
        <div className="historySectionHead">
          <div><h2 id="reconciliation-coverage">Cobertura e integridade</h2>
            <p>Comprovantes de coleta não garantem vendas definitivas.</p></div>
          <Link className="historyRecommendationsLink"
            href={"/historico?since="+period.since+"&until="+period.until}>
            Inspecionar histórico →
          </Link>
        </div>
        <div className="historyCoverageMetrics">
          <div><small>Meta: dias solicitados</small>
            <strong>{number(metaCoverage?.requestedDays??null)}</strong></div>
          <div><small>Meta: dias consultados</small>
            <strong>{number(metaCoverage?.observedDays??null)}</strong></div>
          <div><small>Meta: dias sem linhas</small>
            <strong>{number(metaCoverage?.datesWithoutRows??null)}</strong></div>
          <div><small>Loja: janelas de atualização</small>
            <strong>{number(value.coverage?.store?.successfulUpdateWindows??null)}</strong></div>
        </div>
        <p className="historySmall">
          Cobertura da loja baseada em <code>updated_at</code>, não em
          <code> paid_at</code>. Pedidos podem ter sido atualizados antes da
          primeira coleta ou depois do intervalo consultado. Um 404 anterior
          da Nuvemshop não comprova que o intervalo contém zero vendas.
          Sem associação individual, não é possível subtrair a receita atribuída
          pela Meta para inferir vendas orgânicas.
        </p>
      </section>
    </>}
  </main>;
}
