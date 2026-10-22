import type { Metadata } from "next";
import Calculator from "./Calculator";

export const metadata: Metadata = {
  title: "Calculadora CPA e ROAS | AdPilot",
  description: "Planeje CPA e ROAS a partir de preço de venda, custos variáveis e resultado desejado antes de anunciar.",
};

export default function CalculatorPage() {
  return <Calculator/>;
}
