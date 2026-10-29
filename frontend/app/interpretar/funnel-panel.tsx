import {money,number} from "../../lib/dashboard";
import {
  buildFunnelStages,funnelAttention,ratioLabel,readableFinding,
  type FunnelEvidence,type FunnelReport,
} from "../../lib/funnel-diagnostics";

function count(v:number|null|undefined):string {
  return typeof v==="number"&&Number.isFinite(v)&&v>=0?number(v,0):"Não informado";
}
function amount(v:number|null|undefined,currency:string):string {
  return typeof v==="number"&&Number.isFinite(v)&&v>=0?money(v,currency):"Não informado";
}

export function FunnelPanel({report:r}:{report:FunnelReport}) {
  const stages=buildFunnelStages(r.campaign,r.complete);
  const attention=funnelAttention(r);
  const countAvailable=r.campaign!=null;
  return <div className="funnelScreen" aria-label="Diagnóstico visual do funil">
    <section className="funnelPanel">
      <div className="funnelSectionHead">
        <div>
          <span className="smallEyebrow">DIAGNÓSTICO CONSULTIVO · META ADS</span>
          <h2>Como os eventos avançam após o anúncio</h2>
          <p>Contagens atribuídas pela Meta para a campanha selecionada.
            São eventos agregados, não a jornada dos mesmos visitantes.</p>
        </div>
        <span className="funnelScope">
          {r.partialCurrentDay?"Dia atual · provisório":
            r.complete?"Período histórico":"Amostra incompleta"}
        </span>
      </div>
      {!r.complete&&<p className="funnelInlineAlert" role="status">
        A coleta está incompleta. Os números abaixo são uma amostra,
        não os totais consolidados da campanha.
      </p>}
      <div className="funnelOverview" aria-label="Resumo publicitário">
        <div><small>Investimento observado</small>
          <strong>{amount(r.campaign?.spend,r.currency)}</strong></div>
        <div><small>Visualizações da página</small>
          <strong>{count(r.campaign?.landingPageViews)}</strong></div>
        <div><small>Compras atribuídas</small>
          <strong>{count(r.campaign?.attributedPurchases)}</strong></div>
        <div><small>CPA atribuído</small>
          <strong>{r.complete&&r.campaign?.spend!=null&&r.campaign.spend>0
            ?amount(r.campaign?.attributedCpa,r.currency):"Não consolidado"}</strong></div>
      </div>
      {countAvailable&&<ol className="funnelStages" aria-label="Contagens de eventos por etapa">
        {stages.map((s,i)=><li className="funnelStage" key={s.key}>
          <div className="funnelStageMain">
            <div className="funnelStageHead">
              <div><span className="funnelStageNumber" aria-hidden="true">{i+1}</span>
                <strong>{s.label}</strong></div>
              <b>{count(s.count)}</b>
            </div>
            <div className="funnelTrack" aria-hidden="true">
              <div className="funnelFill" style={{width:s.relativeWidth+"%"}}/>
            </div>
          </div>
          <div className="funnelStageMeta">
            {i===0?<span>Início da comparação de eventos</span>:
              <span>Relação com a etapa anterior
                <strong className={s.ratio.status==="NON_SEQUENTIAL"?"funnelUncomparable":""}>
                  {ratioLabel(s.ratio)}
                </strong>
              </span>}
            {s.costLabel&&<span>{s.costLabel}
              <strong>{amount(s.cost,r.currency)}</strong></span>}
          </div>
          {s.ratio.status==="NON_SEQUENTIAL"&&<p className="funnelStageNote">
            Há mais eventos nesta etapa do que na anterior. São contagens
            atribuídas, não uma taxa de conversão de pessoas.
          </p>}
        </li>)}
      </ol>}
      <p className="funnelFootnote">
        As barras comparam apenas volumes de eventos observados, usando a maior
        contagem como escala. Percentuais são relações descritivas entre eventos,
        não taxas de conversão de usuários únicos. Compras atribuídas não
        comprovam pedidos pagos na Nuvemshop ou resultado incremental.
      </p>
    </section>

    <section className="funnelPanel" aria-label="Qualidade das evidências">
      <div className="funnelSectionHead">
        <div><h2>O que precisamos interpretar com cautela</h2>
          <p>Alertas derivados das evidências retornadas pela API.</p></div>
      </div>
      {attention.length===0
        ?<p className="funnelMuted">Nenhum alerta adicional foi identificado nesta
          consulta. Isso não comprova qualidade de rastreamento nem faturamento.</p>
        :<div className="funnelAttentionGrid">{attention.map(a=><article
          key={a.title} className="funnelAttention">
          <h3>{a.title}</h3><p>{a.description}</p>
        </article>)}</div>}
    </section>

    <section className="funnelPanel" aria-label="Hipóteses consultivas">
      <div className="funnelSectionHead">
        <div><h2>Orientações para investigação</h2>
          <p>São hipóteses verificáveis, não diagnósticos causais ou ordens de
            alteração do orçamento.</p></div>
      </div>
      {r.findings.length===0
        ?<p className="funnelMuted">Nenhuma hipótese adicional gerada pelas
          regras atuais. Isso não significa que a campanha esteja otimizada
          nem que as vendas estejam financeiramente verificadas.</p>
        :<div className="funnelFindings">{r.findings.map((finding,i)=>{
          const v=readableFinding(finding);
          return <article className="funnelFinding" key={v.ruleCode+"-"+i}>
            <div className="funnelFindingHead"><h3>{v.title}</h3>
              <span>Prioridade: {v.priority} · Confiança: {v.confidence}</span></div>
            <p>{v.observation}</p>
            <p><strong>O que verificar: </strong>{v.suggestedCheck}</p>
          </article>;
        })}</div>}
    </section>

    <section className="funnelPanel" aria-label="Detalhamento dos conjuntos e anúncios">
      <div className="funnelSectionHead">
        <div><h2>Detalhamento por conjunto e anúncio</h2>
          <p>Não somamos conjuntos e anúncios entre si, pois representam
            visões diferentes dos mesmos eventos.</p></div>
      </div>
      <FunnelDetail title="Conjuntos" data={r.adsets} currency={r.currency} complete={r.complete}/>
      <FunnelDetail title="Anúncios" data={r.ads} currency={r.currency} complete={r.complete}/>
    </section>
  </div>;
}

function FunnelDetail({title,data,currency,complete}:{
  title:string;data:FunnelEvidence[];currency:string;complete:boolean;
}) {
  return <div className="funnelDetail">
    <h3>{title}</h3>
    {data.length===0?<p className="funnelMuted">Nenhum registro informado.</p>:
      <div className="funnelTableScroll">
        <table className="funnelTable">
          <thead><tr><th scope="col">ID</th><th scope="col">Gasto</th>
            <th scope="col">Visitas</th><th scope="col">Carrinhos</th>
            <th scope="col">Checkouts</th><th scope="col">Compras atribuídas</th>
            <th scope="col">CPA atribuído</th></tr></thead>
          <tbody>{data.map(item=><tr key={item.id}>
            <th scope="row">{item.id}</th>
            <td>{complete?amount(item.spend,currency):"Amostra parcial"}</td>
            <td>{count(item.landingPageViews)}</td><td>{count(item.addToCarts)}</td>
            <td>{count(item.initiatedCheckouts)}</td>
            <td>{count(item.attributedPurchases)}</td>
            <td>{complete?amount(item.attributedCpa,currency):"Não consolidado"}</td>
          </tr>)}</tbody>
        </table>
      </div>}
  </div>;
}
