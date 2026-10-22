import test from "node:test";
import assert from "node:assert/strict";
import {
  calculateProductTargets,
  calculateProductContribution,
  desiredProfitFromPercentage,
  EMPTY_COST_FORM,
  PRICE_FIELDS,
  COST_FIELDS,
} from "../lib/product-targets.ts";

const complete = (overrides = {}) => ({
  ...EMPTY_COST_FORM,
  price: "199",
  productCost: "0",
  desiredProfit: "0",
  taxPercent: "0",
  paymentPercent: "0",
  paymentFixed: "0",
  packaging: "0",
  shippingSubsidy: "0",
  otherCosts: "0",
  ...overrides,
});

const example = (overrides = {}) => complete({
  productCost: "85",
  desiredProfit: "20",
  taxPercent: "10",
  paymentPercent: "4,5",
  paymentFixed: "1",
  packaging: "3",
  shippingSubsidy: "15",
  otherCosts: "2",
  ...overrides,
});

test("R$ 199: derives margin from all explicitly provided costs, not profit target", () => {
  const calculation=calculateProductTargets(example());
  assert.equal(calculation.ok,true);
  if(!calculation.ok)return;
  const r=calculation.result;
  assert.equal(r.priceCents,19900);
  assert.deepEqual(r.breakdown,{
    product:8500,tax:1990,paymentPercent:896,paymentFixed:100,
    packaging:300,shippingSubsidy:1500,other:200,
  });
  assert.equal(r.totalCostCents,13486);
  assert.equal(r.contributionCents,6414);
  assert.equal(r.breakEvenCpaCents,6414);
  assert.equal(r.targetCpaCents,4414);
  assert.equal(r.breakEvenRoas,3.11);
  assert.equal(r.targetRoas,4.51);
  assert.equal(r.status,"READY");
});

test("the automatic pre-ad result can be displayed before profit reserve is entered", () => {
  const input=example({desiredProfit:""});
  const contribution=calculateProductContribution(input);
  const targets=calculateProductTargets(input);
  assert.equal(contribution.ok,true);
  if(contribution.ok) {
    assert.equal(contribution.result.contributionCents,6414);
    assert.equal(contribution.result.totalCostCents,13486);
  }
  assert.equal(targets.ok,false);
  if(!targets.ok) assert.ok(targets.errors.desiredProfit);
});

test("every cost field is mandatory and only an explicit 0 is treated as zero", () => {
  for(const field of PRICE_FIELDS) {
    const input=complete({[field]:""});
    const calculation=calculateProductTargets(input);
    assert.equal(calculation.ok,false,"Expected missing "+field+" to invalidate targets");
    if(!calculation.ok) {
      assert.equal(typeof calculation.errors[field],"string",field);
    }
  }
  for(const field of COST_FIELDS) {
    const contribution=calculateProductContribution(complete({[field]:""}));
    assert.equal(contribution.ok,false,"Expected missing "+field+" to invalidate contribution");
    if(!contribution.ok) assert.ok(contribution.errors[field],field);
  }
});

test("zero is allowed explicitly for every expense and for post-ad profit reserve", () => {
  const zero=calculateProductTargets(complete());
  assert.equal(zero.ok,true);
  if(zero.ok) {
    assert.equal(zero.result.totalCostCents,0);
    assert.equal(zero.result.contributionCents,19900);
    assert.equal(zero.result.targetCpaCents,19900);
    assert.equal(zero.result.breakEvenRoas,1);
    assert.equal(zero.result.targetRoas,1);
  }
  const formattedZeros=calculateProductTargets(complete({
    productCost:"0,00",desiredProfit:"0,00",taxPercent:"0,00",paymentPercent:"0.00",
    paymentFixed:"0.00",packaging:"0,00",shippingSubsidy:"0",otherCosts:"0",
  }));
  assert.deepEqual(formattedZeros,zero);
});

test("the contribution updates if costs change; target reserve only changes CPA and ROAS", () => {
  const a=calculateProductTargets(complete({productCost:"78",desiredProfit:"0"}));
  const b=calculateProductTargets(complete({productCost:"78",desiredProfit:"20"}));
  const c=calculateProductTargets(complete({productCost:"80",desiredProfit:"20"}));
  assert.equal(a.ok,true);
  assert.equal(b.ok,true);
  assert.equal(c.ok,true);
  if(a.ok && b.ok && c.ok) {
    assert.equal(a.result.contributionCents,12100);
    assert.equal(b.result.contributionCents,12100);
    assert.equal(b.result.targetCpaCents,10100);
    assert.equal(c.result.contributionCents,11900);
    assert.equal(c.result.targetCpaCents,9900);
    assert.equal(b.result.targetRoas,1.98);
    assert.equal(c.result.targetRoas,2.02);
  }
});

test("comma and dot give identical amounts with all fields filled", () => {
  const a=calculateProductTargets(complete({
    price:"199,90",productCost:"100,20",desiredProfit:"20,10",
  }));
  const b=calculateProductTargets(complete({
    price:"199.90",productCost:"100.20",desiredProfit:"20.10",
  }));
  assert.deepEqual(a,b);
  assert.equal(a.ok,true);
  if(a.ok) {
    assert.equal(a.result.targetCpaCents,7960);
    assert.equal(a.result.targetRoas,2.52);
  }
});

test("targets are rounded up so suggested ROAS never understates break-even", () => {
  const calculation=calculateProductTargets(complete({
    price:"199",productCost:"150",desiredProfit:"5",
  }));
  assert.equal(calculation.ok,true);
  if(calculation.ok) {
    assert.ok(calculation.result.targetRoas * calculation.result.targetCpaCents
      >= calculation.result.priceCents);
    assert.equal(calculation.result.breakEvenCpaCents,4900);
    assert.equal(calculation.result.targetCpaCents,4400);
  }
});

test("no contribution or reserving all the margin does not invent a finite CPA", () => {
  const cases=[
    ["NO_CONTRIBUTION",{price:"199",productCost:"199",desiredProfit:"0"}],
    ["NO_CONTRIBUTION",{price:"199",productCost:"200",desiredProfit:"0"}],
    ["GOAL_NOT_FEASIBLE",{price:"199",productCost:"100",desiredProfit:"99"}],
    ["GOAL_NOT_FEASIBLE",{price:"199",productCost:"100",desiredProfit:"110"}],
  ];
  for(const [expected,fields] of cases) {
    const r=calculateProductTargets(complete(fields));
    assert.equal(r.ok,true);
    if(r.ok) {
      assert.equal(r.result.status,expected);
      assert.equal(r.result.targetCpaCents,null);
      assert.equal(r.result.targetRoas,null);
    }
  }
});

test("invalid percentages and monetary values are never coerced to zero", () => {
  const cases=[
    ["taxPercent","101"],["taxPercent",""],["paymentPercent","-1"],
    ["paymentPercent","1.234,56"],["productCost","-2"],["productCost","100,999"],
    ["price","0"],["price","1.000,00"],["price","1e2"],["price","1000000,01"],
    ["paymentFixed","NaN"],["desiredProfit","Infinity"],
  ];
  for(const [field,value] of cases) {
    const r=calculateProductTargets(example({[field]:value}));
    assert.equal(r.ok,false,field+"="+value);
    if(!r.ok) assert.ok(r.errors[field],field+"="+value);
  }
});

test("fees and taxes apply to gross selling price, not margin", () => {
  const r=calculateProductTargets(complete({
    price:"199",productCost:"0",desiredProfit:"0",
    taxPercent:"10",paymentPercent:"5",
  }));
  assert.equal(r.ok,true);
  if(r.ok) {
    assert.equal(r.result.breakdown.tax,1990);
    assert.equal(r.result.breakdown.paymentPercent,995);
    assert.equal(r.result.contributionCents,16915);
  }
});


test("17% of R$ 199 automatically becomes R$ 33,83 without changing other costs", () => {
  const profit=desiredProfitFromPercentage("199","17");
  assert.equal(profit.ok,true);
  if(!profit.ok)return;
  assert.equal(profit.amountCents,3383);
  assert.equal(profit.amountInput,"33.83");
  const costs=complete({
    price:"199",productCost:"78",taxPercent:"4",paymentPercent:"5",
    paymentFixed:"1",shippingSubsidy:"3",packaging:"2",otherCosts:"10",
    desiredProfit:profit.amountInput,
  });
  const calc=calculateProductTargets(costs);
  assert.equal(calc.ok,true);
  if(!calc.ok)return;
  assert.equal(calc.result.contributionCents,8709);
  assert.equal(calc.result.desiredProfitCents,3383);
  assert.equal(calc.result.targetCpaCents,5326);
  assert.equal(calc.result.targetRoas,3.74);
});

test("only profit percentage drives the computed profit; price changes update its value", () => {
  const a=desiredProfitFromPercentage("199","17");
  const b=desiredProfitFromPercentage("299","17");
  const c=desiredProfitFromPercentage("199","17,50");
  const d=desiredProfitFromPercentage("199","17.50");
  assert.equal(a.ok,true);assert.equal(b.ok,true);
  assert.deepEqual(c,d);
  if(a.ok && b.ok && c.ok) {
    assert.equal(a.amountCents,3383);
    assert.equal(b.amountCents,5083);
    assert.equal(c.amountCents,3483);
  }
});

test("profit percent accepts explicit zero and rounds to cents", () => {
  const zero=desiredProfitFromPercentage("199,99","0");
  const result=desiredProfitFromPercentage("199,99","17");
  assert.equal(zero.ok,true);
  assert.equal(result.ok,true);
  if(zero.ok && result.ok){
    assert.equal(zero.amountInput,"0.00");
    assert.equal(result.amountCents,3400);
    assert.equal(result.amountInput,"34.00");
  }
});

test("invalid profit percentage is rejected and never silently treated as zero", () => {
  for(const raw of [""," ","-1","100,01","17,123","1.000,00","1e2","NaN","17%"]) {
    const r=desiredProfitFromPercentage("199",raw);
    assert.equal(r.ok,false,raw);
    if(!r.ok) assert.equal(r.field,"percent",raw);
  }
  for(const price of ["","0","-1","1.000,00","199,999"]) {
    const r=desiredProfitFromPercentage(price,"17");
    assert.equal(r.ok,false,price);
    if(!r.ok) assert.equal(r.field,"price",price);
  }
});

test("goal remains unfeasible if 17% profit exceeds the actual contribution", () => {
  const desired=desiredProfitFromPercentage("199","17");
  assert.equal(desired.ok,true);
  if(!desired.ok)return;
  const costs=complete({price:"199",productCost:"180",desiredProfit:desired.amountInput});
  const result=calculateProductTargets(costs);
  assert.equal(result.ok,true);
  if(result.ok){
    assert.equal(result.result.status,"GOAL_NOT_FEASIBLE");
    assert.equal(result.result.targetCpaCents,null);
    assert.equal(result.result.targetRoas,null);
  }
});
