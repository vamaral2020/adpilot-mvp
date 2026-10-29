import test from "node:test";
import assert from "node:assert/strict";
import {readFileSync} from "node:fs";

const interpreter=readFileSync(new URL("../app/interpretar/page.tsx",import.meta.url),"utf8");
const panel=readFileSync(new URL("../app/interpretar/funnel-panel.tsx",import.meta.url),"utf8");
const css=readFileSync(new URL("../app/interpretar/funnel.css",import.meta.url),"utf8");

test("primary business funnel remains visible and advanced duplicate tables are optional",()=>{
  const visible=interpreter.indexOf("<FunnelPanel report={funnel}/>");
  const advanced=interpreter.indexOf('<details className="interpretAdvanced"');
  assert.ok(visible>0);
  assert.ok(advanced>visible);
  const detail=interpreter.slice(advanced);
  assert.match(detail,/Ver configuração técnica e tabelas completas de Insights/);
  assert.match(detail,/rowsTable\("Desempenho por conjunto"/);
  assert.match(detail,/rowsTable\("Desempenho por anúncio"/);
  assert.match(detail,/<\/details>/);
  assert.doesNotMatch(interpreter.slice(0,advanced),/rowsTable\("Desempenho/);
});

test("the funnel retains every core stage and advisory warning in the visible view",()=>{
  assert.match(panel,/buildFunnelStages\(r.campaign,r.complete\)/);
  assert.match(panel,/funnelAttention\(r\)/);
  assert.match(panel,/ratioLabel\(s.ratio\)/);
  assert.match(panel,/Não comparável|funnelUncomparable/);
  assert.match(panel,/compras atribuídas|Compras atribuídas/);
  assert.match(panel,/não uma taxa de conversão de pessoas/);
  assert.match(panel,/Orientações para investigação/);
  assert.match(panel,/Consulta|O que verificar/);
});

test("the screen layout is compact at desktop widths, responsive at mobile widths",()=>{
  const pr=css.indexOf("/* PR058 — screen-only density and hierarchy.");
  assert.ok(pr>0);
  const rules=css.slice(pr);
  assert.match(rules,/@media screen\s*\{/);
  assert.match(rules,/@media screen and \(min-width:1000px\)/);
  assert.match(rules,/\.funnelStage\s*\{\s*display:grid;grid-template-columns/);
  assert.match(rules,/@media screen and \(max-width:850px\)/);
  assert.match(rules,/@media screen and \(max-width:420px\)/);
  assert.doesNotMatch(rules,/@media print/);
});

test("native disclosures have explicit labels and visible focus indicators",()=>{
  assert.match(panel,/<details className="funnelPanel funnelDetailsDisclosure"/);
  assert.match(panel,/Detalhamento por conjunto e anúncio/);
  assert.match(panel,/funnelDisclosureSubtitle/);
  assert.match(interpreter,/<details className="interpretAdvanced"/);
  assert.match(css,/\.funnelDetailsDisclosure>summary:focus-visible/);
  assert.match(css,/\.interpretAdvanced>summary:focus-visible/);
});

test("existing read-only campaign controls and diagnostics remain intact",()=>{
  assert.match(interpreter,/name="campaignId"/);
  assert.match(interpreter,/name="since"/);
  assert.match(interpreter,/name="until"/);
  assert.match(interpreter,/funnel-diagnostics\?/);
  assert.match(interpreter,/delivery-diagnostics\?/);
  assert.match(interpreter,/Cache|cache:"no-store"/);
});
