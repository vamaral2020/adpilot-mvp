import test from "node:test";
import assert from "node:assert/strict";
import { comparisonTitle, comparisonDescription, safeIndicativeMer, dailyAmount } from "../lib/reconciliation.ts";

const report=(overrides={})=>({
  comparisonStatus:"INDICATIVE_ONLY",
  financiallyVerified:false,
  ordersMatchedToCampaigns:false,
  indicativeMer:1.5,
  ...overrides,
});

test("MER remains explicitly indicative and disappears for unverified states",()=>{
  assert.equal(safeIndicativeMer(report()),1.5);
  assert.equal(safeIndicativeMer(report({comparisonStatus:"META_COVERAGE_UNVERIFIED"})),null);
  assert.equal(safeIndicativeMer(report({comparisonStatus:"CURRENCY_MISMATCH"})),null);
  assert.equal(safeIndicativeMer(report({financiallyVerified:true})),null);
  assert.equal(safeIndicativeMer(report({ordersMatchedToCampaigns:true})),null);
  assert.equal(safeIndicativeMer(report({indicativeMer:Number.NaN})),null);
  assert.equal(safeIndicativeMer(report({indicativeMer:-2})),null);
  assert.equal(safeIndicativeMer(null),null);
});

test("missing daily row and mixed currency day cannot become zero",()=>{
  assert.equal(dailyAmount([],"paidRevenue"),null);
  assert.equal(dailyAmount([{paidRevenue:120},{paidRevenue:60}],"paidRevenue"),null);
  assert.equal(dailyAmount([{paidRevenue:null}],"paidRevenue"),null);
  assert.equal(dailyAmount([{paidRevenue:0}],"paidRevenue"),0);
  assert.equal(dailyAmount([{spend:13.93}],"spend"),13.93);
  assert.equal(dailyAmount([{spend:-1}],"spend"),null);
});

test("states explain evidence rather than promise audited financial attribution",()=>{
  assert.match(comparisonTitle("INDICATIVE_ONLY"),/indicativa/i);
  assert.match(comparisonDescription("INSUFFICIENT_OBSERVATIONS"),/não significa/i);
  assert.match(comparisonDescription("CURRENCY_MISMATCH"),/conversão cambial/i);
  assert.match(comparisonDescription("SYNC_EVIDENCE_INCOMPLETE"),/insuficientes/i);
});
