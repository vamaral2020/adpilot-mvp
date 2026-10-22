import test from "node:test";
import assert from "node:assert/strict";
import { parseOptionalGoal } from "../lib/recommendation-goals.ts";

test("accepts Brazilian comma decimals and dot decimals for ROAS", () => {
  assert.deepEqual(parseOptionalGoal("3,4","ROAS mínimo",100),{value:"3.4",error:null});
  assert.deepEqual(parseOptionalGoal("3.4","ROAS mínimo",100),{value:"3.4",error:null});
  assert.deepEqual(parseOptionalGoal(" 3,4000 ","ROAS mínimo",100),{value:"3.4",error:null});
  assert.deepEqual(parseOptionalGoal("0,0001","ROAS mínimo",100),{value:"0.0001",error:null});
});

test("accepts BRL CPA goals including whole values and cents", () => {
  assert.deepEqual(parseOptionalGoal("75","CPA máximo",1000000),{value:"75",error:null});
  assert.deepEqual(parseOptionalGoal("75,50","CPA máximo",1000000),{value:"75.5",error:null});
  assert.deepEqual(parseOptionalGoal("75.50","CPA máximo",1000000),{value:"75.5",error:null});
  assert.deepEqual(parseOptionalGoal("1000000","CPA máximo",1000000),{value:"1000000",error:null});
});

test("optional goals are omitted only when truly empty", () => {
  assert.deepEqual(parseOptionalGoal("   ","ROAS mínimo",100),{value:null,error:null});
});

test("invalid amounts report errors rather than being silently omitted", () => {
  const bad=["0","0,0000","-1","3,4.5","1.000,50","3,12345","1e2","NaN","Infinity","101"];
  for (const value of bad) {
    const parsed=parseOptionalGoal(value,"ROAS mínimo",100);
    assert.equal(parsed.value,null,value);
    assert.equal(typeof parsed.error,"string",value);
    assert.ok(parsed.error.length>10,value);
  }
});

test("caps CPA and rejects currency symbols or grouping", () => {
  for (const value of ["1000000,01","R$ 75,00","1.000,00","75,"]) {
    const parsed=parseOptionalGoal(value,"CPA máximo",1000000);
    assert.equal(parsed.value,null,value);
    assert.ok(parsed.error,value);
  }
});
