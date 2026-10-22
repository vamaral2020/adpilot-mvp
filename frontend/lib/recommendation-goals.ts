/**
 * Goals are entered by Brazilian advertisers and may use a decimal comma.
 * Normalize only simple ungrouped decimal values before calling the Java API.
 * The backend remains authoritative (positive, <= max, <= 4 decimal places).
 */
export type ParsedGoal = { value: string | null; error: string | null };

export function parseOptionalGoal(raw: string, label: string, max: number): ParsedGoal {
  const trimmed = raw.trim();
  if (!trimmed) return { value: null, error: null };

  // Do not silently guess thousands separators or accept scientific notation.
  if (!/^\d+(?:[.,]\d{1,4})?$/.test(trimmed)) {
    return {
      value: null,
      error: `Informe ${label} como número positivo, sem separador de milhar e com até 4 casas decimais. Ex.: 3,4.`,
    };
  }
  const numeric = Number(trimmed.replace(",", "."));
  if (!Number.isFinite(numeric) || numeric <= 0 || numeric > max) {
    return {
      value: null,
      error: `O valor de ${label} deve ser maior que zero e no máximo ${max.toLocaleString("pt-BR")}.`,
    };
  }
  return { value: String(numeric), error: null };
}
