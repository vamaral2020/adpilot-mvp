/**
 * Frontend-only, per-unit economics. All cost inputs must be explicitly
 * acknowledged (enter "0" when a cost does not apply); no silent defaults.
 * BRL values remain integer cents, percentage rates are integer basis points.
 */
export const PRICE_FIELDS = [
  "price", "productCost", "desiredProfit", "taxPercent", "paymentPercent",
  "paymentFixed", "packaging", "shippingSubsidy", "otherCosts",
] as const;

export type PriceField = typeof PRICE_FIELDS[number];
export type CostField = Exclude<PriceField, "desiredProfit">;
export const COST_FIELDS: readonly CostField[] = PRICE_FIELDS.filter(
  (field): field is CostField => field !== "desiredProfit",
);

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

export type Contribution = {
  priceCents: number;
  totalCostCents: number;
  contributionCents: number;
  marginPercent: number;
  breakdown: CostBreakdown;
};

export type CostResult = Contribution & {
  desiredProfitCents: number;
  breakEvenCpaCents: number | null;
  targetCpaCents: number | null;
  breakEvenRoas: number | null;
  targetRoas: number | null;
  status: "NO_CONTRIBUTION" | "GOAL_NOT_FEASIBLE" | "READY";
};

export type Calculation =
  | { ok: false; errors: CostErrors }
  | { ok: true; result: CostResult };
export type ContributionCalculation =
  | { ok: false; errors: CostErrors }
  | { ok: true; result: Contribution };

export const EMPTY_COST_FORM: CostForm = {
  price: "199", // sample price; always editable
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

function parseFixed(raw: string, maximum: number): number | string {
  const value = raw.trim();
  if (!value) return "Preencha este campo. Digite 0 quando não houver custo.";
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

/** Percentage in basis points: 4,50% => 450. */
function parsePercent(raw: string): number | string {
  return parseFixed(raw,10_000);
}

function readFields(form: CostForm, fields: readonly PriceField[]) {
  const errors: CostErrors = {};
  const amounts = {} as Record<PriceField, number>;
  for (const field of fields) {
    const parsed = field === "taxPercent" || field === "paymentPercent"
      ? parsePercent(form[field])
      : parseFixed(form[field],MAX_AMOUNT_CENTS);
    if (typeof parsed === "string") errors[field] = parsed;
    else amounts[field] = parsed;
  }
  if (fields.includes("price") && amounts.price === 0) {
    errors.price = "O preço de venda deve ser maior que zero.";
  }
  return { amounts, errors };
}

function calculateValidatedContribution(amounts: Record<PriceField,number>): Contribution {
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
  return {
    priceCents,
    totalCostCents,
    contributionCents,
    marginPercent: contributionCents / priceCents * 100,
    breakdown,
  };
}

/**
 * Computed "result before ads" is available as soon as every product/cost
 * field has been explicitly filled, regardless of the profit-to-preserve field.
 */
export function calculateProductContribution(form: CostForm): ContributionCalculation {
  const { amounts, errors } = readFields(form,COST_FIELDS);
  if (Object.keys(errors).length > 0) return { ok: false, errors };
  return { ok: true, result: calculateValidatedContribution(amounts) };
}

/** Always round the displayed ROAS UP to avoid understating its threshold. */
function roasCeiling(priceCents: number, cpaCents: number): number | null {
  if (cpaCents <= 0) return null;
  return Math.ceil(priceCents * 100 / cpaCents) / 100;
}

export function calculateProductTargets(form: CostForm): Calculation {
  const { amounts, errors } = readFields(form,PRICE_FIELDS);
  if (Object.keys(errors).length > 0) return { ok: false, errors };

  const contribution = calculateValidatedContribution(amounts);
  const targetCpaCentsRaw = contribution.contributionCents - amounts.desiredProfit;
  const status: CostResult["status"] = contribution.contributionCents <= 0
    ? "NO_CONTRIBUTION"
    : targetCpaCentsRaw <= 0 ? "GOAL_NOT_FEASIBLE" : "READY";

  return {
    ok: true,
    result: {
      ...contribution,
      desiredProfitCents: amounts.desiredProfit,
      breakEvenCpaCents: contribution.contributionCents > 0
        ? contribution.contributionCents : null,
      targetCpaCents: status === "READY" ? targetCpaCentsRaw : null,
      breakEvenRoas: roasCeiling(contribution.priceCents,contribution.contributionCents),
      targetRoas: status === "READY"
        ? roasCeiling(contribution.priceCents,targetCpaCentsRaw) : null,
      status,
    },
  };
}
