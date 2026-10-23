import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";

const page = readFileSync(new URL("../app/conciliacao/page.tsx", import.meta.url), "utf8");
const css = readFileSync(new URL("../app/globals.css", import.meta.url), "utf8");

test("unverified cross-source ratio is not the primary performance KPI", () => {
  const start=page.indexOf('<div className="reconciliationRatios">');
  const end=page.indexOf('</div>\n        {mer!==null ?',start);
  assert.ok(start>0 && end>start);
  const mainCards=page.slice(start,end);
  assert.doesNotMatch(mainCards, /multiplier\(mer\)/);
  assert.match(mainCards, /Pedidos da loja vinculados à publicidade/);
  assert.match(mainCards, /Não identificados/);
  assert.match(mainCards, /ROAS atribuído · Meta/);

  const disclosure=page.slice(end);
  assert.match(disclosure, /<details className="reconciliationMathDetails">/);
  assert.match(disclosure, /Consultar relação aritmética entre fontes \(não é retorno publicitário\)/);
  assert.match(disclosure, /multiplier\(mer\)/);
  assert.match(disclosure, /Não comprova a receita integral da loja nem vendas geradas pelos anúncios/);
  assert.match(disclosure, /O valor pode ser muito elevado quando o investimento é baixo/);
});

test("on-screen cards auto-fit the available width even with browser zoom", () => {
  const start=css.indexOf("/* PR037: browser layout");
  assert.ok(start>0);
  const layout=css.slice(start);
  assert.match(layout, /@media screen\s*\{/);
  assert.match(layout, /grid-template-columns:repeat\(auto-fit,minmax\(min\(100%,260px\),1fr\)\)/);
  assert.match(layout, /overflow-wrap:anywhere/);
  assert.match(layout, /@media screen and \(max-width:700px\)/);
  assert.match(layout, /grid-template-columns:minmax\(0,1fr\)/);
  assert.match(layout, /\.reconciliationMathDetails > summary:focus-visible/);
});

test("the existing print stylesheet stays isolated from browser-specific styles", () => {
  const old=css.indexOf("/* PR036: A4-safe");
  const current=css.indexOf("/* PR037: browser layout");
  assert.ok(old>0 && current>old);
  assert.ok(css.slice(old,current).includes("@media print"));
  assert.ok(css.slice(current).startsWith("/* PR037: browser layout"));
});
