/**
 * PR057: presentation-only model for Meta aggregated event observations.
 * NEVER call these rates "user conversions" or imply an identified cohort.
 */
export type FunnelEvidence = {
  level:string;id:string;adsetId:string|null;
  spend:number|null;impressions:number|null;allClicks:number|null;
  linkClicks:number|null;landingPageViews:number|null;addToCarts:number|null;
  initiatedCheckouts:number|null;attributedPurchases:number|null;
  visitsPerLinkClickPercent:number|null;cartsPerVisitPercent:number|null;
  checkoutsPerCartPercent:number|null;purchasesPerCheckoutPercent:number|null;
  costPerLandingPageView:number|null;costPerAddToCart:number|null;
  costPerInitiatedCheckout:number|null;attributedCpa:number|null;
  warnings:string[];
};
export type FunnelFinding = {
  ruleCode:string;priority:string;confidence:string;
  observation:string;suggestedCheck:string;evidence:string[];
};
export type FunnelReport = {
  campaignId:string;campaignObjective:string;currency:string;
  since:string;until:string;analysisMode:string;partialCurrentDay:boolean;
  complete:boolean;
  adsetCoverage:{complete:boolean;pagesFetched:number;rowsFetched:number};
  adCoverage:{complete:boolean;pagesFetched:number;rowsFetched:number};
  campaign:FunnelEvidence;adsets:FunnelEvidence[];ads:FunnelEvidence[];
  findings:FunnelFinding[];warnings:string[];principles:string[];
};
export type RatioDisplay = {
  status:"OBSERVED"|"MISSING"|"NON_SEQUENTIAL"|"INCOMPLETE";
  value:number|null;
};
export type FunnelStage = {
  key:string;label:string;count:number|null;relativeWidth:number;
  ratio:RatioDisplay;cost:number|null;costLabel:string;
};

function numeric(value:unknown):number|null {
  return typeof value==="number"&&Number.isFinite(value)&&value>=0?value:null;
}

/** Backend ratios are event-count relationships, never verified funnel conversions. */
export function safeEventRatio(previous:number|null, next:number|null,
    reported:number|null, complete:boolean):RatioDisplay {
  if(!complete) return {status:"INCOMPLETE",value:null};
  const before=numeric(previous),after=numeric(next),pct=numeric(reported);
  if(before===null||after===null||pct===null)
    return {status:"MISSING",value:null};
  // Events need not be a sequential cohort. Do not display 1100% as conversion.
  if(after>before || pct>100 || (before===0&&after>0))
    return {status:"NON_SEQUENTIAL",value:null};
  if(before===0) return {status:"MISSING",value:null};
  return {status:"OBSERVED",value:pct};
}

export function buildFunnelStages(funnel:FunnelEvidence,complete:boolean):FunnelStage[] {
  const raw=[
    {key:"links",label:"Cliques no link",count:funnel.linkClicks,
      previous:null,ratio:null,cost:null,costLabel:""},
    {key:"visits",label:"Visualizações da página",count:funnel.landingPageViews,
      previous:funnel.linkClicks,ratio:funnel.visitsPerLinkClickPercent,
      cost:funnel.costPerLandingPageView,costLabel:"Custo por visita"},
    {key:"carts",label:"Adições ao carrinho",count:funnel.addToCarts,
      previous:funnel.landingPageViews,ratio:funnel.cartsPerVisitPercent,
      cost:funnel.costPerAddToCart,costLabel:"Custo por carrinho"},
    {key:"checkouts",label:"Checkouts iniciados",count:funnel.initiatedCheckouts,
      previous:funnel.addToCarts,ratio:funnel.checkoutsPerCartPercent,
      cost:funnel.costPerInitiatedCheckout,costLabel:"Custo por checkout"},
    {key:"purchases",label:"Compras atribuídas pela Meta",count:funnel.attributedPurchases,
      previous:funnel.initiatedCheckouts,ratio:funnel.purchasesPerCheckoutPercent,
      cost:funnel.attributedCpa,costLabel:"CPA atribuído"},
  ];
  const positive=raw.map(s=>numeric(s.count)).filter((n):n is number=>n!==null&&n>0);
  const peak=Math.max(0,...positive);
  return raw.map((stage,i)=>({
    key:stage.key,label:stage.label,count:numeric(stage.count),
    relativeWidth:stage.count===null||peak===0?0:
      Math.min(100,Math.max(0,(numeric(stage.count)??0)/peak*100)),
    ratio:i===0?{status:"MISSING" as const,value:null}:
      safeEventRatio(stage.previous,stage.count,stage.ratio,complete),
    cost:complete?numeric(stage.cost):null,costLabel:stage.costLabel,
  }));
}

export function ratioLabel(ratio:RatioDisplay):string {
  switch(ratio.status) {
    case "OBSERVED":return ratio.value===null?"Não informado":
      new Intl.NumberFormat("pt-BR",{minimumFractionDigits:1,maximumFractionDigits:1})
        .format(ratio.value)+"%";
    case "NON_SEQUENTIAL":return "Não comparável";
    case "INCOMPLETE":return "Amostra parcial";
    default:return "Não informado";
  }
}

const findings:Record<string,{title:string;observation:string;check:string}>={
  FUNNEL_INCOMPLETE_EVIDENCE:{
    title:"Dados insuficientes para analisar o funil",
    observation:"A coleta de conjuntos está vazia ou não terminou todas as páginas.",
    check:"Verifique a cobertura da consulta antes de comparar resultados."},
  PROVISIONAL_CURRENT_DAY:{
    title:"Dia atual: valores provisórios",
    observation:"A janela inclui o dia corrente e os dados ainda podem mudar.",
    check:"Prefira dias encerrados antes de decidir sobre o orçamento."},
  TRAFFIC_OPTIMIZES_VISITS_NOT_PURCHASES:{
    title:"Campanha com objetivo de tráfego",
    observation:"A estratégia declarada prioriza visitas, não necessariamente compras.",
    check:"Confira o objetivo comercial antes de comparar com campanhas de vendas."},
  PURCHASE_ACTION_NOT_REPORTED:{
    title:"Compras não informadas nos Insights",
    observation:"A Meta não retornou uma contagem reconhecida de compras nesta consulta.",
    check:"Verifique o evento Purchase, Pixel/CAPI e os pedidos da loja separadamente."},
  CHECKOUT_ACTION_NOT_REPORTED:{
    title:"Inícios de checkout não informados",
    observation:"Há eventos de carrinho, mas não há contagem de início de checkout.",
    check:"Verifique se o evento InitiateCheckout está sendo mensurado."},
  SPARSE_CART_ACTIONS:{
    title:"Poucos eventos de carrinho registrados",
    observation:"A amostra de visitas e carrinhos merece investigação, sem causa confirmada.",
    check:"Revise mensuração, oferta e página de produto antes de alterar a campanha."},
  NON_MONOTONIC_EVENT_COUNTS:{
    title:"Contagens não seguem uma sequência",
    observation:"Algumas ações posteriores superam as anteriores no período.",
    check:"Não interprete as contagens como um caminho percorrido pelos mesmos visitantes."},
};
export type ReadableFinding={
  title:string;observation:string;suggestedCheck:string;
  confidence:string;priority:string;ruleCode:string;
};
export function readableFinding(f:FunnelFinding):ReadableFinding {
  const described=findings[f.ruleCode];
  return {
    title:described?.title??"Sinal técnico a verificar",
    observation:described?.observation??"Um sinal adicional foi retornado pelo diagnóstico.",
    suggestedCheck:described?.check??"Consulte a evidência técnica antes de concluir.",
    confidence:f.confidence==="LOW"?"Baixa":"Insuficiente",
    priority:f.priority==="HIGH"?"Alta":f.priority==="MEDIUM"?"Média":"Baixa",
    ruleCode:f.ruleCode,
  };
}
export type Attention={title:string;description:string};
export function funnelAttention(report:FunnelReport):Attention[] {
  const alerts:Attention[]=[];
  const warningSet=new Set([...report.warnings,...(report.campaign.warnings??[])]);
  if(!report.complete||!report.adsetCoverage?.complete||!report.adCoverage?.complete)
    alerts.push({title:"Coleta incompleta",description:
      "As contagens disponíveis são apenas amostras. Não representam os totais da campanha."});
  if(report.partialCurrentDay)
    alerts.push({title:"Período provisório",description:
      "A consulta inclui hoje; eventos e atribuições podem ser atualizados."});
  if(warningSet.has("DOWNSTREAM_EVENTS_EXCEED_UPSTREAM"))
    alerts.push({title:"Contagens não sequenciais",description:
      "Uma etapa tem mais eventos do que a anterior. Não use a relação como taxa de conversão."});
  if(warningSet.has("DELIVERY_METRICS_UNAVAILABLE_NOT_ZERO"))
    alerts.push({title:"Métricas de entrega ausentes",description:
      "Cliques, impressões ou gasto podem não ter sido informados. Ausente não significa zero."});
  if(warningSet.has("ZERO_PERIOD_SPEND_WITH_ATTRIBUTED_PURCHASES"))
    alerts.push({title:"Compras com gasto zero no período",description:
      "Atribuições podem chegar após o gasto. Não calcule CPA ou retorno de uma janela sem investimento."});
  if(warningSet.has("PURCHASE_ACTION_UNAVAILABLE_NOT_ZERO"))
    alerts.push({title:"Compras não informadas",description:
      "A Meta não retornou Purchase reconhecido. Isso não comprova ausência de pedidos na loja."});
  if(warningSet.has("ATTRIBUTION_SETTING_UNKNOWN"))
    alerts.push({title:"Janela de atribuição desconhecida",description:
      "A configuração não foi informada nesta consulta; evite comparações definitivas."});
  return alerts;
}
