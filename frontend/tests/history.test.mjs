import test from "node:test";
import assert from "node:assert/strict";
import { sums, graphBars } from "../lib/history.ts";

test("does not add currencies when reporting a historical total", () => {
  assert.equal(sums([{ currency: "BRL", value: 50 }, { currency: "USD", value: 5 }]), null);
});
test("does not turn an empty or invalid series into a monetary zero", () => {
  assert.equal(sums([]), null);
  assert.equal(sums([{ currency: "BRL", value: Number.NaN }]), null);
});
test("aggregates same-currency values and avoids NaN bar heights", () => {
  assert.deepEqual(sums([{ currency: "BRL", value: 15 }, { currency: "BRL", value: 5 }]), { currency: "BRL", value: 20 });
  assert.deepEqual(graphBars([{ date: "2026-10-01", value: 0 }]), [{ date: "2026-10-01", value: 0, height: 0 }]);
});
