"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { money, number } from "../../lib/dashboard";
import { type PlanningCatalog, type LinkedCampaign } from "../../lib/economic-goals";

function key(c:LinkedCampaign) { return c.accountId+":"+c.campaignId; }

export default function CampaignGoals() {
  const [catalog,setCatalog]=useState<PlanningCatalog|null>(null);
  const [pending,setPending]=useState(false);
  const [selected,setSelected]=useState<Record<string,string>>({});
  const [confirmed,setConfirmed]=useState<Record<string,boolean>>({});
  const [notice,setNotice]=useState("");
  const [error,setError]=useState("");

  async function refresh() {
    const response=await fetch("/api/planning/catalog",{cache:"no-store"});
    if(!response.ok) throw new Error("Não foi possível carregar metas e campanhas.");
    const updated=await response.json() as PlanningCatalog;
    setCatalog(updated);
    setSelected(Object.fromEntries(updated.campaigns.map(c=>[key(c),c.linkedGoalId??""])));
    setConfirmed({});
  }

  useEffect(()=>{
    let active=true;
    fetch("/api/planning/catalog",{cache:"no-store"})
      .then(async r=>{if(!r.ok) throw new Error();return await r.json() as PlanningCatalog;})
      .then(data=>{if(active){
        setCatalog(data);
        setSelected(Object.fromEntries(data.campaigns.map(c=>[key(c),c.linkedGoalId??""])));
      }})
      .catch(()=>{if(active)setError("Não foi possível carregar metas e campanhas locais.");});
    return ()=>{active=false;};
  },[]);

  async function modify(c:LinkedCampaign,action:"link"|"unlink") {
    const selection=selected[key(c)]??"";
    if(action==="link" && (!selection || !confirmed[key(c)])) {
      setError("Selecione a meta e confirme que a campanha representa um único produto.");
      return;
    }
    setPending(true);setError("");setNotice("");
    try {
      const url="/api/planning/campaigns/"+c.accountId+"/"+c.campaignId+"/goal";
      const response=await fetch(url,action==="link" ? {
        method:"PUT",headers:{"Content-Type":"application/json"},
        body:JSON.stringify({goalId:selection,singleProductConfirmed:true}),
      } : {method:"DELETE"});
      if(!response.ok) {
        setError(response.status===400
          ?"O vínculo foi recusado: confira a campanha BRL e a confirmação."
          :"Não foi possível alterar o vínculo.");return;
      }
      await refresh();
      setNotice(action==="link"
        ?"Produto associado manualmente à campanha. Metas disponíveis nas recomendações."
        :"Vínculo removido. Nenhuma campanha foi alterada na Meta.");
    }catch{setError("Não foi possível comunicar com o serviço local.");}
    finally{setPending(false);}
  }

  return <main className="historyShell">
    <nav className="historyNav">
      <Link href="/calculadora">← Calculadora</Link>
      <strong>AdPilot / Metas econômicas</strong>
    </nav>
    <header className="historyHeader">
      <div className="smallEyebrow">PLANEJAMENTO LOCAL · ASSOCIAÇÃO MANUAL</div>
      <h1>Produtos e campanhas<span className="titlePeriod">.</span></h1>
      <p>Reutilize a meta econômica de um produto em campanhas da Meta Ads
        sem estimar quais produtos foram vendidos por uma atribuição de anúncio.</p>
      <p><Link href="/calculadora">Criar outra meta a partir dos custos do produto →</Link></p>
    </header>
    <div className="alertBanner caution">
      <strong>Uma unidade de um produto por compra</strong>
      <span>Associe uma meta somente se a campanha anunciar exclusivamente
        esse produto e a projeção de uma unidade por pedido for representativa.
        Não use esta associação para anúncios de catálogo, campanhas de vários
        produtos ou cestas com itens diferentes. Não há vínculo automático
        entre produtos, campanhas e pedidos.</span>
    </div>
    {error && <div className="alertBanner error" role="alert">{error}</div>}
    {notice && <div className="alertBanner" role="status">{notice}</div>}
    {!catalog && !error && <p>Carregando metas e campanhas locais...</p>}
    {catalog && <>
      <section className="historyCard planCatalog" aria-label="Metas econômicas salvas">
        <div className="historySectionHead">
          <div>
            <h2>Metas econômicas salvas</h2>
            <p>Versões imutáveis dos custos informados na calculadora.</p>
          </div>
          <Link href="/calculadora">Nova meta →</Link>
        </div>
        {catalog.goals.length===0 ? <p>Nenhuma meta salva. Crie uma meta na calculadora.</p> :
          <div className="tableScroll"><table className="efficiencyTable">
            <thead><tr><th>Produto / versão</th><th>Preço</th>
              <th>CPA-alvo</th><th>ROAS mínimo</th><th>Lucro desejado</th></tr></thead>
            <tbody>{catalog.goals.map(g=><tr key={g.id}>
              <td><strong>{g.productLabel}</strong>
                <small className="planVersion">{new Date(g.createdAt).toLocaleString("pt-BR")}</small></td>
              <td>{money(g.priceCents/100,"BRL")}</td>
              <td>{money(g.targetCpaCents/100,"BRL")}</td>
              <td>{number(g.targetRoasHundredths/100,2)}x</td>
              <td>{number(g.desiredProfitBasisPoints/100,2)}%</td>
            </tr>)}</tbody>
          </table></div>}
      </section>
      <section className="historyCard planCatalog" aria-label="Associação de campanhas">
        <div className="historySectionHead">
          <div>
            <h2>Associar metas às campanhas</h2>
            <p>Campanhas identificadas nos snapshots locais da Meta,
              com moeda BRL comprovada pelos Insights.</p>
          </div>
        </div>
        {catalog.campaigns.length===0 ? <p>Nenhuma campanha BRL identificada.
          Execute a sincronização histórica e de campanhas antes de associar metas.</p>
          : <div className="planCampaignList">{catalog.campaigns.map(c=>{
              const k=key(c);
              const original=c.linkedGoalId??"";
              const changed=(selected[k]??"")!==original;
              return <div className="planCampaign" key={k}>
                <div className="planCampaignHead">
                  <strong>{c.campaignName}</strong>
                  <small>Conta {c.accountId} · Campanha {c.campaignId} · {c.currency}</small>
                </div>
                {c.linkedProductLabel && <p className="planLinked">
                  Associado: <strong>{c.linkedProductLabel}</strong>
                  {" · "}CPA {money((c.linkedCpaCents??0)/100,"BRL")}
                  {" · "}ROAS {number((c.linkedRoasHundredths??0)/100,2)}x
                </p>}
                <label htmlFor={"plan-"+k}>Meta econômica a associar</label>
                <select id={"plan-"+k} value={selected[k]??""} disabled={pending}
                  onChange={e=>{
                    setSelected(p=>({...p,[k]:e.target.value}));
                    setConfirmed(p=>({...p,[k]:false}));
                  }}>
                  <option value="">Selecione uma meta salva</option>
                  {catalog.goals.map(g=><option key={g.id} value={g.id}>
                    {g.productLabel} · CPA {money(g.targetCpaCents/100,"BRL")}
                    {" · "}ROAS {number(g.targetRoasHundredths/100,2)}x
                    {" · "}({g.id.slice(0,8)})
                  </option>)}
                </select>
                <label className="planConfirmation">
                  <input type="checkbox" checked={confirmed[k]??false}
                    disabled={pending || !(selected[k]??"")}
                    onChange={e=>setConfirmed(p=>({...p,[k]:e.target.checked}))}/>
                  Confirmo que esta campanha representa um único produto
                  (uma unidade por pedido) e que essa meta se aplica a ela.
                </label>
                <div className="planActions">
                  <button type="button" disabled={pending || !selected[k]
                    || !confirmed[k] || (!changed && original!=="")}
                    onClick={()=>modify(c,"link")}>
                    {original?"Atualizar associação":"Associar à campanha"}
                  </button>
                  {original && <button type="button" className="planUnlink"
                    disabled={pending} onClick={()=>modify(c,"unlink")}>Desvincular</button>}
                </div>
              </div>;
            })}</div>}
      </section>
      <p className="historySmall">Os vínculos e as metas existem somente no banco
        de dados do AdPilot. Não são enviados à Meta Ads nem à Nuvemshop.
        <Link href="/recomendacoes"> Ver recomendações com as metas associadas →</Link>
      </p>
    </>}
  </main>;
}
