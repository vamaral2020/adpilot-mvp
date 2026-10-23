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
type AssetComposition={
  dynamicAssetFeed:boolean;
  imageAssetCount:number|null;
  videoAssetCount:number|null;
  carouselVariantCount:number|null;
  carouselCardCount:number|null;
  declaredFormats:string[];
  carouselStructureDetected:boolean;
  catalogReferenceDetected:boolean;
  mixedImageVideoAssets:boolean;
  notes:string[];
};
type Ad={
  id:string;name:string|null;adsetId:string;
  effectiveStatus:string|null;format:string;formatEvidence:string;
  assetComposition?:AssetComposition|null;
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
const assetFormatLabels:Record<string,string>={
  SINGLE_IMAGE:"Imagem única",SINGLE_VIDEO:"Vídeo único",
  CAROUSEL:"Carrossel",COLLECTION:"Coleção",
};
function counted(value:number|null|undefined):string {
  return typeof value==="number"&&Number.isInteger(value)&&value>=0
    ?String(value):"Não informado";
}
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
  const dynamicCount=detail?.ads.filter(x=>x.assetComposition?.dynamicAssetFeed).length??0;
  const catalogCount=detail?.ads.filter(x=>x.assetComposition?.catalogReferenceDetected).length??0;
  const carouselCount=detail?.ads.filter(x=>x.assetComposition?.carouselStructureDetected).length??0;
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
        <div><small>Recursos dinâmicos</small><strong>{dynamicCount}</strong></div>
        <div><small>Sinal de catálogo</small><strong>{catalogCount}</strong></div>
        <div><small>Estrutura de carrossel</small><strong>{carouselCount}</strong></div>
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
              {ad.assetComposition && (ad.assetComposition.dynamicAssetFeed
                || ad.assetComposition.carouselStructureDetected
                || ad.assetComposition.catalogReferenceDetected) && <div className="formatComposition">
                <p className="formatCompositionTitle">Composição cadastrada na Meta</p>
                {ad.assetComposition.dynamicAssetFeed && <div className="formatCompositionGrid">
                  <div><small>Recursos de imagem</small>
                    <strong>{counted(ad.assetComposition.imageAssetCount)}</strong></div>
                  <div><small>Recursos de vídeo</small>
                    <strong>{counted(ad.assetComposition.videoAssetCount)}</strong></div>
                  <div><small>Variantes de carrossel</small>
                    <strong>{counted(ad.assetComposition.carouselVariantCount)}</strong></div>
                  <div><small>Cartões de carrossel</small>
                    <strong>{counted(ad.assetComposition.carouselCardCount)}</strong></div>
                </div>}
                {!ad.assetComposition.dynamicAssetFeed &&
                  ad.assetComposition.carouselCardCount!==null && <p>
                    Cartões identificados na estrutura: {ad.assetComposition.carouselCardCount}</p>}
                <div className="formatSignals">
                  {ad.assetComposition.mixedImageVideoAssets &&
                    <span>Imagens e vídeos configurados no mesmo criativo</span>}
                  {ad.assetComposition.carouselStructureDetected &&
                    <span>Estrutura compatível com carrossel</span>}
                  {ad.assetComposition.catalogReferenceDetected &&
                    <span>Referência de catálogo identificada</span>}
                </div>
                {ad.assetComposition.declaredFormats.length>0 && <p>
                  <strong>Formatos declarados no cadastro:</strong>{" "}
                  {ad.assetComposition.declaredFormats.map(code=>assetFormatLabels[code]??"Outro")
                    .join(", ")}
                </p>}
                {ad.assetComposition.notes.includes("SOME_ASSET_COUNTS_NOT_REPORTED") &&
                  <p className="formatCompositionNotice">A Meta não informou todas as listas de
                    recursos. “Não informado” não significa zero.</p>}
                {ad.assetComposition.notes.includes("AD_FORMATS_UNRECOGNIZED") &&
                  <p className="formatCompositionNotice">A Meta retornou outros formatos não
                    reconhecidos; eles foram omitidos por segurança.</p>}
                <p className="formatCompositionNotice">Contagens representam entradas de
                  configuração, não arquivos únicos nem peças comprovadamente veiculadas.
                  O formato final pode variar por posicionamento.</p>
              </div>}
            </div>)}
        </article>
      </section>
      <p className="historySmall">Formato e composição derivados exclusivamente dos
        metadados de cadastro da Meta. Sinais de carrossel e catálogo não comprovam
        qual combinação foi entregue ao público. Listas ausentes não são tratadas
        como zero. URLs, texto, hashes e IDs de mídia não são retornados.</p>
    </>}
  </main>;
}
