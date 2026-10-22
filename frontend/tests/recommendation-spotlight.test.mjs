import test from "node:test";
import assert from "node:assert/strict";
import { selectSpotlight, spotlightMetric, recommendationQualitySummary } from "../lib/recommendation-spotlight.ts";

const makeAdvice = (ruleCode, priority, values = {}) => ({
  ruleCode, priority, confidence: "INSUFFICIENT", campaignId: "52596120828588",
  campaignName: "VENDAS_102_PECAS_7DIAS", accountId: "497208073675481",
  currency: "BRL", title: "Acompanhar antes de alterar orçamento",
  justification: "A Meta atribuiu 3 compras; acompanhe antes de agir.",
  evidence: [
    { name: "ATTRIBUTED_PURCHASES", value: 3, limitation: "Não são pedidos da loja" },
    { name: "ROAS", value: 5.33, limitation: null },
    { name: "CPA", value: 43.63, limitation: null },
  ], ...values,
});

const report = (recommendations, changes = {}) => ({
  since: "2026-10-15", until: "2026-10-21", coverageVerified: false,
  metaSyncStatus: "SUCCEEDED", warnings: ["HISTORICAL_COVERAGE_NOT_VERIFIED"],
  recommendations, ...changes,
});

test("homepage shows campaign advice before generic goal-setup guidance", () => {
  const focus = selectSpotlight(report([
    { ...makeAdvice("CONFIGURE_GOALS", "LOW"), campaignId: null, campaignName: null },
    makeAdvice("LOW_SAMPLE", "LOW"),
  ]));
  assert.equal(focus.kind, "advice");
  assert.equal(focus.ruleCode, "LOW_SAMPLE");
  assert.equal(focus.campaignName, "VENDAS_102_PECAS_7DIAS");
  assert.equal(focus.coverageVerified, false);
  assert.equal(spotlightMetric(focus, "ATTRIBUTED_PURCHASES"), 3);
  assert.equal(spotlightMetric(focus, "ROAS"), 5.33);
});

test("selects higher-priority issue without inventing a cause or action", () => {
  const focus = selectSpotlight(report([
    makeAdvice("LOW_SAMPLE", "LOW"),
    makeAdvice("ROAS_BELOW_TARGET", "MEDIUM", {
      title: "ROAS abaixo da meta", campaignId: "campaign-2",
    }),
  ]));
  assert.equal(focus.ruleCode, "ROAS_BELOW_TARGET");
  assert.equal(focus.title, "ROAS abaixo da meta");
});

test("no historical Meta rows does not mean zero spend or zero conversions", () => {
  const focus = selectSpotlight(report([
    { ...makeAdvice("NO_META_INSIGHTS", "LOW"), campaignId: null, evidence: [] },
    { ...makeAdvice("CONFIGURE_GOALS", "LOW"), campaignId: null, evidence: [] },
  ]));
  assert.equal(focus.kind, "no-data");
  assert.match(focus.explanation,/não significa.*zero/i);
  assert.equal(spotlightMetric(focus, "ROAS"), null);
});

test("failed read is distinguished from a valid empty historical window", () => {
  const focus = selectSpotlight(undefined);
  assert.equal(focus.kind, "unavailable");
  assert.equal(focus.coverageVerified, false);
});

test("unverified sync and absent metrics never become fabricated numbers", () => {
  const focus = selectSpotlight(report([makeAdvice("LOW_SAMPLE", "LOW", {
    evidence: [{ name: "CPA", value: null, limitation: "Sem denominador" }],
  })], { warnings:["META_SYNC_NOT_CONFIRMED_CURRENT"], metaSyncStatus:"FAILED" }));
  assert.equal(focus.syncUnconfirmed, true);
  assert.equal(spotlightMetric(focus,"CPA"),null);
  assert.equal(spotlightMetric(focus,"ROAS"),null);
});

test("a report with no issues and no campaigns points to goal configuration", () => {
  const focus = selectSpotlight(report([{...makeAdvice("CONFIGURE_GOALS","LOW"),campaignId:null}]));
  assert.equal(focus.kind, "no-data");
  assert.match(focus.title, /Configure/);
});


test("data-quality warning outranks ordinary LOW_SAMPLE and reports missing dates", () => {
  const quality={status:"PARTIAL_WINDOWS",requestedDays:8,collectedDays:3,
    daysWithRows:5,daysWithoutRows:0,uncollectedDates:["2026-10-14"],
    collectedWithoutRows:[],lastWindowSuccess:"2026-10-22T21:15:20Z",
    syncFresh:true,analysisEligible:false};
  const focus=selectSpotlight(report([
    makeAdvice("LOW_SAMPLE","LOW"),
    makeAdvice("DATA_QUALITY_LIMITED","MEDIUM",{
      title:"Verificar qualidade dos dados antes de decidir",
      justification:"Faltam comprovantes de coleta para os dias informados.",
    }),
  ],{quality}));
  assert.equal(focus.ruleCode,"DATA_QUALITY_LIMITED");
  assert.equal(focus.collectedDays,3);
  assert.equal(focus.requestedDays,8);
  assert.equal(focus.qualityStatus,"PARTIAL_WINDOWS");
  assert.match(focus.qualitySummary,/apenas parte/);
  assert.equal(focus.coverageVerified,false);
});

test("fresh Meta receipts do not certify financial completeness", () => {
  const quality={status:"WINDOWS_OBSERVED",requestedDays:8,collectedDays:8,
    daysWithRows:6,daysWithoutRows:2,uncollectedDates:[],
    collectedWithoutRows:["2026-10-14","2026-10-15"],
    lastWindowSuccess:"2026-10-22T21:15:20Z",syncFresh:true,analysisEligible:true};
  const focus=selectSpotlight(report([makeAdvice("LOW_SAMPLE","LOW")],{quality}));
  assert.equal(focus.ruleCode,"LOW_SAMPLE");
  assert.equal(focus.syncUnconfirmed,false);
  assert.equal(focus.coverageVerified,false);
  assert.match(recommendationQualitySummary(quality),/atribuição financeira continua preliminar/);
});

test("stale sync changes presentation but does not invent Meta sales", () => {
  const quality={status:"WINDOWS_OBSERVED",requestedDays:8,collectedDays:8,
    daysWithRows:6,daysWithoutRows:2,uncollectedDates:[],
    collectedWithoutRows:["2026-10-14","2026-10-15"],
    lastWindowSuccess:"2026-10-20T21:15:20Z",syncFresh:false,analysisEligible:false};
  const focus=selectSpotlight(report([makeAdvice("DATA_QUALITY_LIMITED","MEDIUM")],{quality}));
  assert.equal(focus.syncUnconfirmed,true);
  assert.match(focus.qualitySummary,/atualizar a sincronização/);
  assert.equal(spotlightMetric(focus,"ATTRIBUTED_PURCHASES"),3);
});
