import test from "node:test";
import assert from "node:assert/strict";
import {interpreterPeriod,utcToday} from "../lib/interpreter-period.ts";

test("today is accepted as provisional monitoring while yesterday is historical",()=>{
  const today="2026-10-25";
  assert.deepEqual(interpreterPeriod("2026-10-18",today,today),{
    valid:true,partialCurrentDay:true,mode:"CURRENT_DAY_MONITORING",
  });
  assert.deepEqual(interpreterPeriod("2026-10-18","2026-10-24",today),{
    valid:true,partialCurrentDay:false,mode:"HISTORICAL_CONTEXT",
  });
});

test("future dates, invalid calendar dates and reversed windows are rejected",()=>{
  const today="2026-10-25";
  for(const [since,until] of [
    ["2026-10-25","2026-10-26"],
    ["2026-10-26","2026-10-26"],
    ["2026-10-31","2026-10-25"],
    ["2026-02-30","2026-10-25"],
    ["2026-10-01","2026-10-32"],
    ["2026-10-25","2026-10-24"],
  ]) assert.equal(interpreterPeriod(since,until,today).valid,false,
    `${since}..${until} must be invalid`);
});

test("31 inclusive days accepted; 32 rejected even if window includes today",()=>{
  assert.equal(interpreterPeriod("2026-09-25","2026-10-25","2026-10-25").valid,true);
  assert.equal(interpreterPeriod("2026-09-24","2026-10-25","2026-10-25").valid,false);
});

test("UTC midnight boundary is explicit and stable",()=>{
  assert.equal(utcToday(new Date("2026-10-25T23:59:59Z")),"2026-10-25");
  assert.equal(utcToday(new Date("2026-10-26T00:00:01Z")),"2026-10-26");
});
