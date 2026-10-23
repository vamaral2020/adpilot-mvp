import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";

const page = readFileSync(new URL("../app/conciliacao/page.tsx", import.meta.url),"utf8");
const css = readFileSync(new URL("../app/globals.css", import.meta.url),"utf8");

test("reconciliation uses a print-specific root without changing other pages", () => {
  assert.match(page,/className="historyShell reconciliationPage"/);
  const printStart=css.indexOf("/* PR036: A4-safe");
  assert.ok(printStart>0,"Expected isolated reconciliation print stylesheet");
  const printBlock=css.slice(printStart);
  assert.match(printBlock,/\@media print\s*\{/);
  assert.match(printBlock,/\.reconciliationPage\s+\.historyCard,/);
  assert.match(printBlock,/background:#fff!important/);
  assert.match(printBlock,/\.reconciliationPage\s+\.historySectionHead h2,/);
  assert.match(printBlock,/color:#152b43!important/);
  assert.match(printBlock,/\.reconciliationPage\s+\.reconciliationRatios strong/);
  assert.match(printBlock,/color:#172d45!important/);
});

test("print tables are not clipped by scroll containers or legacy minimum widths", () => {
  const printBlock=css.slice(css.indexOf("/* PR036: A4-safe"));
  assert.match(printBlock,/\.reconciliationPage\s+\.tableScroll\s*\{\s*overflow:visible!important/);
  assert.match(printBlock,/\.reconciliationPage\s+\.historyCampaignTable\s*\{/);
  assert.match(printBlock,/min-width:0!important/);
  assert.match(printBlock,/table-layout:fixed!important/);
  assert.match(printBlock,/word-break:normal!important/);
});

test("high MER is identified as observed ratio, not ad-generated sales", () => {
  assert.match(page,/Relação observada · receita da loja \/ gasto Meta/);
  assert.match(page,/Não comprova a receita integral da loja nem vendas geradas pelos anúncios/);
});

test("printed campaign names inherit a readable foreground instead of screen pale blue", () => {
  const printBlock=css.slice(css.indexOf("/* PR036: A4-safe"),css.indexOf("/* PR037: browser layout"));
  assert.match(printBlock,/\.reconciliationPage \.historyCampaignTable td:first-child strong\s*\{\s*color:#172d45!important/);
  assert.match(printBlock,/\.reconciliationPage \.historyCampaignId\s*\{\s*color:#5a7189!important/);
});

test("printed short daily tables keep heading with rows; dev badge is omitted", () => {
  const printBlock=css.slice(css.indexOf("/* PR036: A4-safe"),css.indexOf("/* PR037: browser layout"));
  assert.match(printBlock,/section\[aria-labelledby="reconciliation-daily"\]\s*\{\s*break-inside:avoid-page/);
  assert.match(printBlock,/section\[aria-labelledby="reconciliation-campaigns"\] \.historySectionHead\s*\{\s*break-after:avoid-page/);
  const globalPrint=css.slice(css.indexOf("@media print{"));
  assert.match(globalPrint,/nextjs-portal\{display:none!important\}/);
});
