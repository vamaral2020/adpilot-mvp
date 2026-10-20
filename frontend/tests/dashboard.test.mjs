import test from "node:test";
import assert from "node:assert/strict";
import {
  presetPeriod, resolvePeriod, money, number, percentage, multiplier, finite, readableDate, verifiedMer
} from "../lib/dashboard.ts";

const today = new Date("2026-10-20T12:00:00.000Z");

test("defaults to last seven completed UTC dates", () => {
  assert.deepEqual(resolvePeriod(undefined, undefined, today), {
    since: "2026-10-13", until: "2026-10-19"
  });
  assert.deepEqual(presetPeriod(30, today), {
    since: "2026-09-20", until: "2026-10-19"
  });
});

test("accepts custom inclusive date period and one day", () => {
  assert.deepEqual(resolvePeriod("2026-10-12", "2026-10-18", today), {
    since: "2026-10-12", until: "2026-10-18"
  });
  assert.equal(resolvePeriod("2026-10-18", "2026-10-18", today).error, undefined);
});

test("rejects malformed, impossible or reversed dates and >90 day intervals", () => {
  for (const [since, until] of [
    ["2026-02-30", "2026-03-01"],
    ["2026-10-19", "2026-10-12"],
    ["2026-05-01", "2026-10-18"],
    ["not-a-date", "2026-10-18"],
    ["2026-10-12", undefined],
    ["2026-10-12", "2026-11-30"],
  ]) {
    assert.ok(resolvePeriod(since, until, today).error, `Expected rejection: ${since}, ${until}`);
  }
});

test("handles leap days without timezone date shifting", () => {
  const leap = new Date("2024-03-01T10:00:00.000Z");
  assert.deepEqual(presetPeriod(2, leap), { since: "2024-02-28", until: "2024-02-29" });
  assert.equal(resolvePeriod("2024-02-29", "2024-02-29", leap).error, undefined);
});

test("renders Portuguese number formats without inventing missing data", () => {
  assert.ok(money(130.88, "BRL").includes("130,88"));
  assert.ok(money(201.05, "BRL").includes("201,05"));
  assert.ok(money(19, "USD").includes("US$"));
  assert.equal(number(8239), "8.239");
  assert.equal(percentage(4.0053), "4,01%");
  assert.equal(multiplier(1.5361), "1,54x");
  assert.equal(money(null), "—");
  assert.equal(multiplier(null), "—");
  assert.equal(finite(Number.POSITIVE_INFINITY), null);
  assert.equal(readableDate("2026-10-18"), "18 de out.");
});

test("MER is suppressed for partial periods and different currencies", () => {
  const snapshot = {
    ordersComplete: true, metaComplete: true, storeCurrency: "BRL",
    metaCurrency: "BRL", mediaSpend: 100, mer: 2.45,
  };
  assert.equal(verifiedMer(snapshot), 2.45);
  assert.equal(verifiedMer({ ...snapshot, ordersComplete: false }), null);
  assert.equal(verifiedMer({ ...snapshot, metaComplete: false }), null);
  assert.equal(verifiedMer({ ...snapshot, metaCurrency: "USD" }), null);
  assert.equal(verifiedMer({ ...snapshot, mediaSpend: 0 }), null);
  assert.equal(verifiedMer({ ...snapshot, storeCurrency: null }), null);
  assert.equal(verifiedMer({ ...snapshot, mer: null }), null);
});
