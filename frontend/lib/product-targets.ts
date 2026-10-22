/**
 * Frontend-only unit economics for ONE item in ONE order.
 * Inputs accept ungrouped pt-BR decimals; money stays in integer cents and
 * percent rates in integer basis points. No store/Meta reads or writes.
 */
export const PRICE_FIELDS = [
  "price", "productCost", "desiredProfit", "taxPercent", "paymentPercent",
  "paymentFixed", "packaging", "shippingSubsidy", "otherCosts",
] as const;

export type PriceField = typeof PRICE_FIELDS[number];
export type CostForm = Record<PriceField, string>;
export type CostErrors = Partial<Record<PriceField, string>>;
export type CostBreakdown = {
  product: number;
  tax: number;
  paymentPercent: number;
  paymentFixed: number;
  packaging: number;
  shippingSubsidy: number;
  other: number;
};
export type CostResult = {
  priceCents: number;
  totalCostCents: number;
  contributionCents: number;
  marginPercent: number;
  desiredProfitCents: number;
  breakEvenCpaCents: number | null;
  targetCpaCents: number | null;
  breakEvenRoas: number | null;
  targetRoas: number | null;
  breakdown: CostBreakdown;
  status: "NO_CONTRIBUTION" | "GOAL_NOT_FEASIBLE" | "READY";
};
export type Calculation =
  | { ok: false; errors: CostErrors }
  | { ok: true; result: CostResult };

export const EMPTY_COST_FORM: CostForm = {
  price: "199", // prefilled as the user's example, not a saved product
  productCost: "",
  desiredProfit: "",
  taxPercent: "",
  paymentPercent: "",
  paymentFixed: "",
  packaging: "",
  shippingSubsidy: "",
  otherCosts: "",
};

const MAX_AMOUNT_CENTS = 100_000_000; // R$ 1,000,000.00 per field
const amountPattern = /^\d+(?:[.,]\d{1,2})?$/;

function parseFixed(raw: string, required: boolean, maximum: number): number | string {
  const value = raw.trim();
  if (!value) return required ? "Preencha este campo." : 0;
  if (!amountPattern.test(value)) {
    return "Use apenas números, sem milhares, com vírgula ou ponto e até 2 casas decimais.";
  }
  const parts = value.replace(",", ".").split(".");
  const whole = Number(parts[0]);
  const fraction = Number((parts[1] ?? "").padEnd(2, "0"));
  if (!Number.isSafeInteger(whole) || !Number.isSafeInteger(fraction)) {
    return "Valor inválido.";
  }
  const units = whole * 100 + fraction;
  if (!Number.isSafeInteger(units) || units > maximum) return "Valor acima do limite permitido.";
  return units;
}

/** Percentage in 1/100 of 1% (basis points): 4,50% = 450. */
function parsePercent(raw: string): number | string {
  const bps = parseFixed(raw, false, 10_000);
  return typeof bps === "string" ? bps : bps;
}

/**
 * A target ROAS rounded UP to two decimals avoids recommending a
 * visibly smaller threshold than the exact mathematical requirement.
 */
function roasCeiling(priceCents: number, cpaCents: number): number | null {
  if (cpaCents <= 0) return null;
  return Math.ceil(priceCents * 100 / cpaCents) / 100;
}

export function calculateProductTargets(form: CostForm): Calculation {
  const errors: CostErrors = {};
  const amounts = {} as Record<PriceField, number>;
  for (const field of PRICE_FIELDS) {
    const parsed = field === "taxPercent" || field === "paymentPercent"
      ? parsePercent(form[field])
      : parseFixed(form[field], field === "price"
          || field === "productCost" || field === "desiredProfit", MAX_AMOUNT_CENTS);
    if (typeof parsed === "string") errors[field] = parsed;
    else amounts[field] = parsed;
  }
  if (amounts.price === 0) errors.price = "O preço de venda deve ser maior que zero.";
  if (Object.keys(errors).length > 0) return { ok: false, errors };

  const priceCents = amounts.price;
  const tax = Math.round(priceCents * amounts.taxPercent / 10_000);
  const paymentPercent = Math.round(priceCents * amounts.paymentPercent / 10_000);
  const breakdown: CostBreakdown = {
    product: amounts.productCost,
    tax,
    paymentPercent,
    paymentFixed: amounts.paymentFixed,
    packaging: amounts.packaging,
    shippingSubsidy: amounts.shippingSubsidy,
    other: amounts.otherCosts,
  };
  const totalCostCents = Object.values(breakdown).reduce((a,b) => a+b,0);
  const contributionCents = priceCents - totalCostCents;
  const targetCpaCentsRaw = contributionCents - amounts.desiredProfit;
  const status: CostResult["status"] = contributionCents <= 0
    ? "NO_CONTRIBUTION"
    : targetCpaCentsRaw <= 0 ? "GOAL_NOT_FEASIBLE" : "READY";

  return {
    ok: true,
    result: {
      priceCents,
      totalCostCents,
      contributionCents,
      marginPercent: contributionCents / priceCents * 100,
      desiredProfitCents: amounts.desiredProfit,
      breakEvenCpaCents: contributionCents > 0 ? contributionCents : null,
      targetCpaCents: status === "READY" ? targetCpaCentsRaw : null,
      breakEvenRoas: roasCeiling(priceCents,contributionCents),
      targetRoas: status === "READY" ? roasCeiling(priceCents,targetCpaCentsRaw) : null,
      breakdown,
      status,
    },
  };
}
