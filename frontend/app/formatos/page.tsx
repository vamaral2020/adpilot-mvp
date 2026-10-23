import Link from "next/link";

export const dynamic = "force-dynamic";

type Campaign = {
  id:string; name:string; objective:string|null;
  status:string|null; effective_status:string|null;
};
type CampaignPage={data:Campaign[];after:string|null};
type Adset={
  id:string; name:string|null; optimizationGoal:string|null;
  destinationType:string|null; effectiveStatus:string|null;
};
type Ad={
  id:string;name:string|null;adsetId:string;
  effectiveStatus:string|null;format:string;formatEvidence:string;
};
type FormatReport={
  campaignId:string;adsets:Adset[];ads:Ad[];
  complete:boolean;formatsFullyIdentified:boolean;
  adsetPages:number;adPages:number;
};
type Params={campaignId?:string|string[]};

async function getInternal<T>(path:string):Promise<{data?:T;error?:string}> {
  const username=process.env.ADPILOT_ADMIN_USERNAME??"";
  const password=process.env.ADPILOT_ADMIN_PASSWORD??"";
  if(!username || !password) return {error:"Credenciais internas não configuradas."};
  const base=process.env.INTERNAL_API_URL??"http://localhost:18080";
  try {
    const response=await fetch(base+path,{
      headers:{Authorization:"Basic "+Buffer.from(username+":"+password).toString("base64")},
      cache:"no-store",signal:AbortSignal.timeout(25000),
    });
    if(!response.ok) return {error:"Não foi possível consultar a Meta Ads (HTTP "+response.status+")."};
    return {data:await response.json() as T};
  } catch {
    return {error:"Falha de conexão ao consultar os formatos da Meta Ads."};
  }
}

const objectiveLabels:Record<string,string>={
  OUTCOME_SALES:"Vendas",
  OUTCOME_TRAFFIC:"Tráfego",
  OUTCOME_ENGAGEMENT:"Engajamento",
  OUTCOME_AWARENESS:"Reconhecimento",
  OUTCOME_LEADS:"Cadastros",
  OUTCOME_APP_PROMOTION:"Aplicativo",
};
const formatLabels:Record<string,string>={
  IMAGE:"Imagem",VIDEO:"Vídeo",CAROUSEL:"Carrossel",
  CATALOG:"Catálogo",DYNAMIC_ASSETS:"Recursos dinâmicos / mistos",
  LINK_OR_IMAGE:"Link ou imagem (indeterminado)",UNKNOWN:"Não identificado",
};

export default async function FormatsPage({searchParams}:{searchParams:Promise<Params>}) {
  const query=await searchParams;
  const listing=await getInternal<CampaignPage>("/api/v1/meta/campaigns?limit=100");
  const campaigns=listing.data?.data??[];
  const requested=typeof query.campaignId==="string"?query.campaignId:"";
  const selected=campaigns.find(c=>c.id===requested)??campaigns[0];
  const report=selected
    ?await getInternal<FormatReport>("/api/v1/meta/campaigns/"+encodeURIComponent(selected.id)+"/formats")
    :null;
  const detail=report?.data;
  const adsets=new Map(detail?.adsets.map(x=>[x.id,x])??[]);
  return <main className="historyShell">
    <nav className="historyNav">
      <Link href="/">← Visão geral</Link>
      <strong>AdPilot / Objetivos e formatos</strong>
    </nav>
    <header className="historyHeader">
      <div className="smallEyebrow">META ADS · INSPEÇÃO SOMENTE LEITURA</div>
      <h1>Objetivos e formatos dos anúncios</h1>
      <p>Inspecione a estrutura efetiva das campanhas, incluindo otimização do conjunto
        e tipo de criativo. Nenhuma configuração ou anúncio é alterado.</p>
    </header>
    {listing.error && <p className="alertBanner error">{listing.error}</p>}
    {campaigns.length>0 && <section className="filterPanel">
      <form className="filterForm" method="GET">
        <label htmlFor="campaignId">Campanha
          <select id="campaignId" name="campaignId" defaultValue={selected?.id}>
            {campaigns.map(c=><option key={c.id} value={c.id}>
              {c.name} — {objectiveLabels[c.objective??""]??c.objective??"Objetivo desconhecido"}
            </option>)}
          </select>
        </label>
        <button type="submit">Inspecionar campanha</button>
      </form>
    </section>}
    {selected && <section className="historySummary">
      <h2>{selected.name}</h2>
      <p>Objetivo: <strong>{objectiveLabels[selected.objective??""]??selected.objective??"Não informado"}</strong>
        {" · "}Situação: <strong>{selected.effective_status??selected.status??"Não informada"}</strong>
        {" · "}ID {selected.id}</p>
      <p className="historySmall">O objetivo é definido no nível da campanha; o destino e a
        otimização dependem do conjunto. O formato depende do anúncio e do criativo.</p>
    </section>}
    {report?.error && <p className="alertBanner error">{report.error}</p>}
    {detail && <>
      {!detail.complete && <p className="alertBanner caution">
        A paginação ultrapassou o limite de segurança. As listas abaixo estão incompletas;
        não representam todos os anúncios da campanha.</p>}
      {!detail.formatsFullyIdentified && <p className="alertBanner caution">
        Alguns criativos não permitem identificação confiável de formato somente pelos
        metadados disponíveis. Recursos dinâmicos podem combinar formatos durante a entrega.
      </p>}
      <section className="recommendationsSummary" aria-label="Inventário da campanha">
        <div><small>Conjuntos identificados</small><strong>{detail.adsets.length}</strong></div>
        <div><small>Anúncios identificados</small><strong>{detail.ads.length}</strong></div>
        <div><small>Inventário</small><strong>{detail.complete?"Completo":"Parcial"}</strong></div>
      </section>
      <section className="recommendationsList">
        <article className="recommendationCard">
          <h2>Conjuntos de anúncios</h2>
          {detail.adsets.length===0?<p>Nenhum conjunto retornado nesta consulta.</p>:
            detail.adsets.map(set=><div className="formatItem" key={set.id}>
              <strong>{set.name??set.id}</strong>
              <p>Otimização: {set.optimizationGoal??"Não informada"}
                {" · "}Destino: {set.destinationType??"Não informado"}
                {" · "}Situação: {set.effectiveStatus??"Não informada"}</p>
            </div>)}
        </article>
        <article className="recommendationCard">
          <h2>Criativos e formatos</h2>
          {detail.ads.length===0?<p>Nenhum anúncio retornado nesta consulta.</p>:
            detail.ads.map(ad=><div className="formatItem" key={ad.id}>
              <strong>{ad.name??ad.id}</strong>
              <p>Formato: <strong>{formatLabels[ad.format]??"Não identificado"}</strong>
                {" · "}Conjunto: {adsets.get(ad.adsetId)?.name??ad.adsetId}
                {" · "}Situação: {ad.effectiveStatus??"Não informada"}</p>
            </div>)}
        </article>
      </section>
      <p className="historySmall">Formato estimado a partir do cadastro do criativo na Meta,
        não da renderização final em cada posicionamento. Nenhuma URL de destino,
        texto do anúncio, mídia ou dado pessoal é retornado nesta página.</p>
    </>}
  </main>;
}
