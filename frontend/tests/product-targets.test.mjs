import test from "node:test";
import assert from "node:assert/strict";
import { calculateProductTargets, EMPTY_COST_FORM } from "../lib/product-targets.ts";

const example = (overrides = {}) => ({
  ...EMPTY_COST_FORM,
  price: "199",
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

test("R$ 199: computes total unit economics from cents and rounded fees", () => {
  const calculation = calculateProductTargets(example());
  assert.equal(calculation.ok, true);
  if (!calculation.ok) return;
  const r=calculation.result;
  assert.equal(r.priceCents,19900);
  assert.deepEqual(r.breakdown,{
    product:8500, tax:1990, paymentPercent:896, paymentFixed:100,
    packaging:300, shippingSubsidy:1500, other:200,
  });
  assert.equal(r.totalCostCents,13486);
  assert.equal(r.contributionCents,5414);
  assert.equal(r.breakEvenCpaCents,5414);
  assert.equal(r.targetCpaCents,3414);
  assert.equal(r.breakEvenRoas,3.68); // ceil(199/54.14, 2 decimals)
  assert.equal(r.targetRoas,5.83); // ceil(199/34.14, 2 decimals)
  assert.equal(r.status,"READY");
});

test("comma and dot mean the same amount and empty optional costs are zero", () => {
  const a=calculateProductTargets({
    ...EMPTY_COST_FORM, price:"199,90", productCost:"100,20", desiredProfit:"20,10",
  });
  const b=calculateProductTargets({
    ...EMPTY_COST_FORM, price:"199.90", productCost:"100.20", desiredProfit:"20.10",
    paymentFixed:"0",paymentPercent:"0",taxPercent:"0",packaging:"0",
    shippingSubsidy:"0",otherCosts:"0",
  });
  assert.deepEqual(a,b);
  assert.equal(a.ok,true);
  if(a.ok) {
    assert.equal(a.result.targetCpaCents,7960);
    assert.equal(a.result.targetRoas,2.52);
  }
});

test("a break-even ROAS target is rounded UP, never under the exact threshold", () => {
  const calc=calculateProductTargets({
    ...EMPTY_COST_FORM,price:"199",productCost:"150",desiredProfit:"5",
  });
  assert.equal(calc.ok,true);
  if(calc.ok) {
    assert.ok(calc.result.targetRoas * calc.result.targetCpaCents >= calc.result.priceCents);
    assert.equal(calc.result.breakEvenCpaCents,4900);
  }
});

test("zero contribution does not invent finite CPA/ROAS goals", () => {
  const cases=[
    ["NO_CONTRIBUTION",{price:"199",productCost:"199",desiredProfit:"0"}],
    ["NO_CONTRIBUTION",{price:"199",productCost:"200",desiredProfit:"0"}],
    ["GOAL_NOT_FEASIBLE",{price:"199",productCost:"100",desiredProfit:"99"}],
    ["GOAL_NOT_FEASIBLE",{price:"199",productCost:"100",desiredProfit:"110"}],
  ];
  for(const [expected,inputs] of cases){
    const result=calculateProductTargets({...EMPTY_COST_FORM,...inputs});
    assert.equal(result.ok,true);
    if(result.ok){
      assert.equal(result.result.status,expected);
      assert.equal(result.result.targetCpaCents,null);
      assert.equal(result.result.targetRoas,null);
    }
  }
});

test("desired profit of zero is explicit and yields a break-even goal", () => {
  const calc=calculateProductTargets({...EMPTY_COST_FORM,price:"199",
    productCost:"100",desiredProfit:"0"});
  assert.equal(calc.ok,true);
  if(calc.ok){
    assert.equal(calc.result.targetCpaCents,9900);
    assert.equal(calc.result.targetRoas,calc.result.breakEvenRoas);
  }
});

test("required fields must be explicitly entered, even if zero cost or profit", () => {
  const calc=calculateProductTargets(EMPTY_COST_FORM);
  assert.equal(calc.ok,false);
  if(!calc.ok){
    assert.ok(calc.errors.productCost);
    assert.ok(calc.errors.desiredProfit);
    assert.equal(calc.errors.price,undefined);
  }
});

test("invalid percentage and money cannot silently become zero", () => {
  const values=[
    ["taxPercent","101"],
    ["paymentPercent","-1"],
    ["paymentPercent","1.234,56"],
    ["productCost","-2"],
    ["productCost","100,999"],
    ["price","0"],
    ["price","1.000,00"],
    ["price","1e2"],
    ["price","1000000,01"],
    ["paymentFixed","NaN"],
    ["desiredProfit","Infinity"],
  ];
  for(const [field,value] of values) {
    const calc=calculateProductTargets(example({[field]:value}));
    assert.equal(calc.ok,false,field+"="+value);
    if(!calc.ok) assert.ok(calc.errors[field],field+"="+value);
  }
});

test("tax and payment fee rates are applied to product price, not net margin", () => {
  const calc=calculateProductTargets({...EMPTY_COST_FORM,price:"199",
    productCost:"0",desiredProfit:"0",taxPercent:"10",paymentPercent:"5"});
  assert.equal(calc.ok,true);
  if(calc.ok) {
    assert.equal(calc.result.breakdown.tax,1990);
    assert.equal(calc.result.breakdown.paymentPercent,995);
    assert.equal(calc.result.contributionCents,16915);
  }
});

test("round up computed ROAS so the copyable goal is never less than needed", () => {
  const calc=calculateProductTargets({
    ...EMPTY_COST_FORM,price:"199",productCost:"149",desiredProfit:"10"
  });
  assert.equal(calc.ok,true);
  if(calc.ok){
    assert.equal(calc.result.targetCpaCents,4000);
    assert.equal(calc.result.targetRoas,4.98);
  }
});
