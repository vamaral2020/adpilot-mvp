import test from "node:test";
import assert from "node:assert/strict";
import { makeProductGoalInput } from "../lib/economic-goals.ts";
import { EMPTY_COST_FORM } from "../lib/product-targets.ts";

test("199 BRL plan transmits every validated cent and basis point without client CPA",()=>{
  const result=makeProductGoalInput({...EMPTY_COST_FORM,productCost:"78"},"17","Vestido midi");
  assert.equal(result.ok,true);
  if(!result.ok) return;
  assert.deepEqual(result.value,{
    productLabel:"Vestido midi",priceCents:19900,productCostCents:7800,
    taxBasisPoints:400,paymentBasisPoints:500,paymentFixedCents:200,
    shippingSubsidyCents:300,packagingCents:200,otherCostsCents:1000,
    desiredProfitBasisPoints:1700,
  });
  assert.equal("targetCpa" in result.value,false);
  assert.equal("targetRoas" in result.value,false);
});

test("all missing and invalid costs are rejected instead of treating blank as zero",()=>{
  const good={...EMPTY_COST_FORM,productCost:"78"};
  assert.equal(makeProductGoalInput({...good,paymentFixed:""},"17","Vestido").ok,false);
  assert.equal(makeProductGoalInput({...good,taxPercent:"101"},"17","Vestido").ok,false);
  assert.equal(makeProductGoalInput({...good,otherCosts:"-1"},"17","Vestido").ok,false);
  assert.equal(makeProductGoalInput(good,"17","").ok,false);
});

test("identical calculations can be saved as distinct manually labeled immutable plan versions",()=>{
  const form={...EMPTY_COST_FORM,productCost:"78",taxPercent:"0"};
  const a=makeProductGoalInput(form,"10","Vestido");
  const b=makeProductGoalInput(form,"15","Vestido");
  assert.equal(a.ok,true);
  assert.equal(b.ok,true);
  if(a.ok && b.ok){
    assert.equal(a.value.taxBasisPoints,0);
    assert.equal(a.value.desiredProfitBasisPoints,1000);
    assert.equal(b.value.desiredProfitBasisPoints,1500);
    assert.equal(a.value.productLabel,b.value.productLabel);
  }
});

test("unprofitable or infeasible targets cannot be persisted",()=>{
  const form={...EMPTY_COST_FORM,productCost:"190"};
  const result=makeProductGoalInput(form,"17","Vestido");
  assert.equal(result.ok,false);
});
