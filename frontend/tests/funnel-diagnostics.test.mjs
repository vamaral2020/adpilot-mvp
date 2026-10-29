import test from "node:test";
import assert from "node:assert/strict";
import {safeEventRatio,ratioLabel,buildFunnelStages,readableFinding,funnelAttention}
  from "../lib/funnel-diagnostics.ts";

function evidence(changes={}) {
  return {
    level:"campaign",id:"123",adsetId:null,spend:130,impressions:8000,allClicks:300,
    linkClicks:194,landingPageViews:177,addToCarts:34,initiatedCheckouts:16,
    attributedPurchases:3,visitsPerLinkClickPercent:91.2371,
    cartsPerVisitPercent:19.209,checkoutsPerCartPercent:47.0588,
    purchasesPerCheckoutPercent:18.75,costPerLandingPageView:0.7345,
    costPerAddToCart:3.8235,costPerInitiatedCheckout:8.125,
    attributedCpa:43.3333,warnings:[],...changes,
  };
}
function report(changes={}) {
  return {campaignId:"123",campaignObjective:"OUTCOME_SALES",currency:"BRL",
    since:"2026-10-17",until:"2026-10-23",analysisMode:"HISTORICAL_CONTEXT",
    partialCurrentDay:false,complete:true,
    adsetCoverage:{complete:true,pagesFetched:1,rowsFetched:1},
    adCoverage:{complete:true,pagesFetched:1,rowsFetched:1},
    campaign:evidence(),adsets:[],ads:[],findings:[],warnings:[],
    principles:[],...changes};
}

test("known event relationships stay descriptive, not user conversion claims",()=>{
  assert.deepEqual(safeEventRatio(34,16,47.0588,true),
    {status:"OBSERVED",value:47.0588});
  assert.equal(ratioLabel(safeEventRatio(34,16,47.0588,true)),"47,1%");
  const stages=buildFunnelStages(evidence(),true);
  assert.deepEqual(stages.map(s=>s.key),
    ["links","visits","carts","checkouts","purchases"]);
  assert.equal(stages[0].relativeWidth,100);
  assert.equal(stages[4].count,3);
  assert.equal(stages[4].cost,43.3333);
  assert.equal(stages[4].ratio.status,"OBSERVED");
});

test("1100 percent never displayed as a conversion rate",()=>{
  const stages=buildFunnelStages(evidence({
    spend:0,linkClicks:0,landingPageViews:1,addToCarts:11,
    initiatedCheckouts:7,attributedPurchases:2,
    visitsPerLinkClickPercent:null,cartsPerVisitPercent:1100,
    costPerAddToCart:null,attributedCpa:null,
  }),true);
  assert.equal(stages[2].count,11);
  assert.equal(stages[2].ratio.status,"NON_SEQUENTIAL");
  assert.equal(ratioLabel(stages[2].ratio),"Não comparável");
  assert.equal(stages[1].ratio.status,"NON_SEQUENTIAL");
  assert.notEqual(stages[2].relativeWidth,0);
});

test("reported ratios over 100 suppressed even for inconsistent raw fields",()=>{
  assert.equal(safeEventRatio(10,9,110,true).status,"NON_SEQUENTIAL");
  assert.equal(safeEventRatio(0,1,null,true).status,"NON_SEQUENTIAL");
  assert.equal(safeEventRatio(2,3,150,true).status,"NON_SEQUENTIAL");
});

test("explicit zero, missing and incomplete are distinct",()=>{
  assert.equal(safeEventRatio(10,0,0,true).value,0);
  assert.equal(ratioLabel(safeEventRatio(10,0,0,true)),"0,0%");
  assert.equal(safeEventRatio(0,0,null,true).status,"MISSING");
  assert.equal(ratioLabel(safeEventRatio(null,2,null,true)),"Não informado");
  const stages=buildFunnelStages(evidence(),false);
  assert.ok(stages.every((s,i)=>i===0||s.ratio.status==="INCOMPLETE"));
  assert.ok(stages.every(s=>s.cost===null));
});

test("paused campaign zero spend preserves purchase count, never a zero CPA",()=>{
  const stages=buildFunnelStages(evidence({
    spend:0,impressions:null,allClicks:null,linkClicks:0,
    landingPageViews:1,addToCarts:11,initiatedCheckouts:7,
    attributedPurchases:2,visitsPerLinkClickPercent:null,
    cartsPerVisitPercent:1100,checkoutsPerCartPercent:63.6364,
    purchasesPerCheckoutPercent:28.5714,
    costPerLandingPageView:null,costPerAddToCart:null,
    costPerInitiatedCheckout:null,attributedCpa:null,
  }),true);
  assert.equal(stages[4].count,2);
  assert.equal(stages[4].cost,null);
  const attention=funnelAttention(report({campaign:evidence({
    spend:0,warnings:["DOWNSTREAM_EVENTS_EXCEED_UPSTREAM"]
  }),warnings:["ZERO_PERIOD_SPEND_WITH_ATTRIBUTED_PURCHASES"]}));
  assert.ok(attention.some(x=>x.title==="Compras com gasto zero no período"));
  assert.ok(attention.some(x=>x.title==="Contagens não sequenciais"));
});

test("provisional and partial reports are clearly marked",()=>{
  const attention=funnelAttention(report({complete:false,partialCurrentDay:true}));
  assert.deepEqual(attention.slice(0,2).map(a=>a.title),
    ["Coleta incompleta","Período provisório"]);
});

test("known findings translated without raw technical tokens",()=>{
  const found=readableFinding({ruleCode:"PURCHASE_ACTION_NOT_REPORTED",
    priority:"HIGH",confidence:"INSUFFICIENT",observation:"unsafe",
    suggestedCheck:"unsafe",evidence:[]});
  assert.equal(found.title,"Compras não informadas nos Insights");
  assert.equal(found.confidence,"Insuficiente");
  assert.ok(found.suggestedCheck.includes("Pixel/CAPI"));
  const unknown=readableFinding({ruleCode:"SOME_NEW_RULE",priority:"HIGH",
    confidence:"HIGH",observation:"raw",suggestedCheck:"raw",evidence:[]});
  assert.equal(unknown.title,"Sinal técnico a verificar");
  assert.ok(!unknown.observation.includes("raw"));
  assert.equal(unknown.confidence,"Insuficiente");
});

test("missing purchase and coverage warnings show uncertainty, not zero",()=>{
  const attention=funnelAttention(report({warnings:[
    "PURCHASE_ACTION_UNAVAILABLE_NOT_ZERO","ATTRIBUTION_SETTING_UNKNOWN",
    "DELIVERY_METRICS_UNAVAILABLE_NOT_ZERO"
  ]}));
  assert.ok(attention.some(x=>x.title==="Compras não informadas"));
  assert.ok(attention.some(x=>x.title==="Métricas de entrega ausentes"));
  assert.ok(attention.some(x=>x.title==="Janela de atribuição desconhecida"));
});
