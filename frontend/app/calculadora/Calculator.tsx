"use client";

import Link from "next/link";
import { useMemo, useState } from "react";
import { money, number } from "../../lib/dashboard";
import {
  calculateProductTargets, EMPTY_COST_FORM,
  type CostForm, type PriceField, type CostErrors,
} from "../../lib/product-targets";

const formatMoney = (cents: number) => money(cents / 100,"BRL");
const formatRoas = (value: number) => number(value,2) + "x";

function Field({field,label,description,value,errors,onChange,percentage=false}:{
  field: PriceField;
  label: string;
  description: string;
  value: string;
  errors: CostErrors;
  onChange: (name: PriceField, value: string)=>void;
  percentage?: boolean;
}) {
  const error=errors[field];
  const id="calc-"+field;
  return <div className="calcField">
    <label htmlFor={id}>{label}</label>
    <div className={"calcFieldControl"+(error?" calcFieldInvalid":"")}>
      <span aria-hidden="true">{percentage?"%":"R$"}</span>
      <input id={id} name={field} type="text" inputMode="decimal" autoComplete="off"
        maxLength={18} spellCheck={false}
        placeholder={percentage?"0,00":"0,00"} value={value}
        aria-invalid={Boolean(error)}
        aria-describedby={id+"-help"+(error?" "+id+"-error":"")}
        onChange={event=>onChange(field,event.target.value)}/>
    </div>
    <p id={id+"-help"} className="calcFieldHelp">{description}</p>
    {error && <p id={id+"-error"} className="calcFieldError" role="alert">{error}</p>}
  </div>;
}

export default function Calculator() {
  const [form,setForm]=useState<CostForm>({...EMPTY_COST_FORM});
  const [showValidation,setShowValidation]=useState(false);
  const [copyState,setCopyState]=useState<"idle"|"done"|"error">("idle");
  const calculation=useMemo(()=>calculateProductTargets(form),[form]);
  const errors: CostErrors={};
  if(!calculation.ok) {
    for(const [field,description] of Object.entries(calculation.errors)) {
      if(showValidation || form[field as PriceField].trim()) {
        errors[field as PriceField]=description;
      }
    }
  }
  const update=(field: PriceField,value: string)=>{
    setForm(previous=>({...previous,[field]:value}));
    setCopyState("idle");
    if(!showValidation) setShowValidation(true);
  };
  const result=calculation.ok?calculation.result:null;
  const feasible=result?.status==="READY"
    && result.targetCpaCents!==null && result.targetRoas!==null;

  async function copyTargets() {
    if(!result || !feasible || result.targetCpaCents===null || result.targetRoas===null) return;
    const text=[
      "AdPilot — metas econômicas por venda (1 produto por pedido)",
      "Preço de venda: "+formatMoney(result.priceCents),
      "CPA-alvo máximo: "+formatMoney(result.targetCpaCents),
      "ROAS-alvo mínimo: "+formatRoas(result.targetRoas),
      "CPA de equilíbrio: "+formatMoney(result.breakEvenCpaCents??0),
      "ROAS de equilíbrio: "+formatRoas(result.breakEvenRoas??0),
      "Lucro desejado após publicidade: "+formatMoney(result.desiredProfitCents),
      "Cálculo interno, não garante resultados. Compare com o ticket real e as opções de lance da Meta.",
    ].join("\n");
    try {
      if(!navigator.clipboard?.writeText) throw new Error("Clipboard unavailable");
      await navigator.clipboard.writeText(text);
      setCopyState("done");
    } catch {
      setCopyState("error");
    }
  }

  const initialMissing=!form.productCost.trim() || !form.desiredProfit.trim();

  return <main className="calcPage">
    <header className="calcHeader">
      <nav className="calcNav" aria-label="Navegação da calculadora">
        <Link href="/">← Visão geral</Link>
        <Link href="/recomendacoes">Recomendações</Link>
        <span>AdPilot / Planejamento</span>
      </nav>
      <div className="calcHero">
        <div className="smallEyebrow">PLANEJAMENTO DE CAMPANHAS · FERRAMENTA LOCAL</div>
        <h1>Calculadora de CPA e ROAS<span className="titlePeriod">.</span></h1>
        <p>Descubra quanto pode investir para vender um produto sem comprometer a margem
          e quais objetivos econômicos usar ao planejar a campanha.</p>
      </div>
    </header>

    <div className="calcLayout">
      <section className="calcInputPanel" aria-labelledby="calc-input-heading">
        <div className="calcSectionTop">
          <span className="calcStep">01</span>
          <div>
            <h2 id="calc-input-heading">Dados do produto</h2>
            <p>Considere uma venda com <strong>uma unidade</strong> do produto anunciado.
              O preço de R$ 199 é apenas o exemplo inicial e pode ser alterado.</p>
          </div>
        </div>
        <div className="calcFieldGrid">
          <Field field="price" label="Preço de venda" description="Valor bruto cobrado pelo produto, sem frete adicional."
            value={form.price} errors={errors} onChange={update}/>
          <Field field="productCost" label="Custo da mercadoria" description="Quanto custa adquirir ou produzir uma unidade."
            value={form.productCost} errors={errors} onChange={update}/>
          <Field field="desiredProfit" label="Resultado desejado por venda" description="Quanto deseja reservar após pagar anúncios; antes dos custos fixos não informados."
            value={form.desiredProfit} errors={errors} onChange={update}/>
        </div>
        <details className="calcOptional">
          <summary>
            <span>Adicionar impostos, taxas e outros custos</span>
            <span className="calcOptionalHint">Detalhes opcionais <span aria-hidden="true">⌄</span></span>
          </summary>
          <p>Campos vazios contam como <strong>zero</strong>. Preencha os custos aplicáveis
            para evitar metas de publicidade excessivamente otimistas.</p>
          <div className="calcFieldGrid">
            <Field field="taxPercent" label="Impostos sobre venda (%)"
              description="Percentual do preço bruto destinado aos tributos."
              value={form.taxPercent} errors={errors} onChange={update} percentage/>
            <Field field="paymentPercent" label="Taxa de pagamento (%)"
              description="Percentual cobrado pelo intermediador do pagamento."
              value={form.paymentPercent} errors={errors} onChange={update} percentage/>
            <Field field="paymentFixed" label="Tarifa fixa do pagamento"
              description="Cobrança adicional fixa por transação."
              value={form.paymentFixed} errors={errors} onChange={update}/>
            <Field field="shippingSubsidy" label="Frete pago pela loja"
              description="Parcela do frete que não é repassada ao cliente."
              value={form.shippingSubsidy} errors={errors} onChange={update}/>
            <Field field="packaging" label="Embalagem"
              description="Caixa, sacola, proteção, etiqueta e materiais."
              value={form.packaging} errors={errors} onChange={update}/>
            <Field field="otherCosts" label="Outros custos variáveis"
              description="Comissões e provisões por venda não incluídas acima."
              value={form.otherCosts} errors={errors} onChange={update}/>
          </div>
        </details>
        <div className="calcPanelFooter">
          <p>Use ponto ou vírgula decimal, por exemplo: 85,50 ou 85.50.
            Não use separador de milhares.</p>
          <button type="button" className="calcReset" onClick={()=>{
            setForm({...EMPTY_COST_FORM});setShowValidation(false);setCopyState("idle");
          }}>Limpar valores</button>
        </div>
      </section>

      <section className="calcResults" aria-labelledby="calc-results-heading"
        aria-live="polite" aria-atomic="false">
        <div className="calcSectionTop">
          <span className="calcStep">02</span>
          <div>
            <h2 id="calc-results-heading">Seus objetivos de campanha</h2>
            <p>Metas calculadas a partir do preço, dos custos e da margem desejada.</p>
          </div>
        </div>

        {!result && <div className="calcEmpty" role="status">
          <strong>{initialMissing?"Informe o custo e o resultado desejado":"Revise os valores informados"}</strong>
          <p>{initialMissing
            ? "Para calcular metas utilizáveis, preencha o custo da peça e quanto deseja reservar por venda. Pode informar zero explicitamente, se for o caso."
            : "Os campos precisam conter valores não negativos e percentuais de até 100%."}</p>
        </div>}

        {result && <>
          {result.status==="NO_CONTRIBUTION" && <div className="calcWarning" role="status">
            <strong>O preço não cobre os custos variáveis.</strong>
            <p>Não existe margem disponível para pagar anúncios. Reveja o preço
              de venda ou os custos antes de definir metas de CPA e ROAS.</p>
          </div>}
          {result.status==="GOAL_NOT_FEASIBLE" && <div className="calcWarning" role="status">
            <strong>A margem não comporta o resultado desejado.</strong>
            <p>O lucro desejado consome toda a margem antes da publicidade.
              Não há um CPA positivo compatível com essa meta.</p>
          </div>}

          <div className="calcGoalGrid">
            <div className="calcGoalCard">
              <span>CPA-alvo máximo</span>
              <strong>{feasible&&result.targetCpaCents!==null
                ? formatMoney(result.targetCpaCents):"Não viável"}</strong>
              <small>Máximo de publicidade por compra, preservando o resultado desejado.</small>
            </div>
            <div className="calcGoalCard calcGoalCardEmphasis">
              <span>ROAS-alvo mínimo</span>
              <strong>{feasible&&result.targetRoas!==null
                ? formatRoas(result.targetRoas):"Não viável"}</strong>
              <small>Retorno atribuído necessário para manter a margem planejada.</small>
            </div>
          </div>

          <div className="calcSummary">
            <div><span>Custos variáveis por venda</span><strong>{formatMoney(result.totalCostCents)}</strong></div>
            <div><span>Margem antes dos anúncios</span>
              <strong className={result.contributionCents<=0?"calcNegative":""}>
                {formatMoney(result.contributionCents)}</strong></div>
            <div><span>Margem de contribuição</span>
              <strong>{number(result.marginPercent,2)}%</strong></div>
            <div><span>CPA de equilíbrio (sem sobra)</span>
              <strong>{result.breakEvenCpaCents===null?"Não viável":
                formatMoney(result.breakEvenCpaCents)}</strong></div>
            <div><span>ROAS de equilíbrio</span>
              <strong>{result.breakEvenRoas===null?"Não viável":
                formatRoas(result.breakEvenRoas)}</strong></div>
          </div>

          <details className="calcBreakdown">
            <summary>Como chegamos a esses números <span aria-hidden="true">⌄</span></summary>
            <div className="calcBreakdownRows">
              {([
                ["Preço de venda",result.priceCents],
                ["Custo da mercadoria",-result.breakdown.product],
                ["Impostos",-result.breakdown.tax],
                ["Taxa percentual do pagamento",-result.breakdown.paymentPercent],
                ["Tarifa fixa",-result.breakdown.paymentFixed],
                ["Frete subsidiado",-result.breakdown.shippingSubsidy],
                ["Embalagem",-result.breakdown.packaging],
                ["Outros custos",-result.breakdown.other],
              ] as [string,number][]).map(([label,cents])=>
                <div key={label}><span>{label}</span><strong>{formatMoney(cents)}</strong></div>)}
              <div className="calcBreakdownTotal"><span>Margem para anúncios e resultado</span>
                <strong>{formatMoney(result.contributionCents)}</strong></div>
              <div><span>Resultado desejado após anúncios</span>
                <strong>{formatMoney(result.desiredProfitCents)}</strong></div>
            </div>
          </details>

          <button type="button" className="calcCopy" disabled={!feasible}
            onClick={copyTargets}>Copiar metas para planejar campanha</button>
          {copyState==="done" && <p className="calcCopyStatus" role="status">
            Metas copiadas. Confira as opções de estratégia de lance disponíveis na Meta.</p>}
          {copyState==="error" && <p className="calcCopyStatus calcCopyError" role="status">
            O navegador não permitiu copiar. Selecione os valores acima manualmente.</p>}
        </>}
      </section>
    </div>

    <section className="calcGuidance" aria-labelledby="calc-guidance-heading">
      <div className="smallEyebrow">03 · PLANEJE NA META ADS</div>
      <h2 id="calc-guidance-heading">Como usar as metas antes de anunciar</h2>
      <div className="calcGuidanceGrid">
        <article>
          <span className="calcGuidanceNumber">01</span>
          <h3>Defina o custo por compra</h3>
          <p>Use o CPA-alvo como referência para acompanhar os resultados.
            Se sua estratégia de lance disponibilizar meta de custo por resultado,
            confira se ela se aplica à otimização por compra.</p>
        </article>
        <article>
          <span className="calcGuidanceNumber">02</span>
          <h3>Compare o retorno esperado</h3>
          <p>Use o ROAS-alvo para monitorar o retorno atribuído. Algumas estratégias
            podem oferecer controle de ROAS mínimo, dependendo da configuração.
            Nem sempre é possível aplicar CPA e ROAS como controles simultâneos.</p>
        </article>
        <article>
          <span className="calcGuidanceNumber">03</span>
          <h3>Acompanhe as vendas reais</h3>
          <p>As metas são limites econômicos, não previsão de conversões. Confirme
            taxas, ticket médio, devoluções e diferenças entre a receita atribuída
            pela Meta e os pedidos da loja.</p>
        </article>
      </div>
      <a className="calcMetaLink" href="https://adsmanager.facebook.com/"
        target="_blank" rel="noopener noreferrer">
        Abrir Gerenciador de Anúncios da Meta <span aria-hidden="true">↗</span>
      </a>
      <p className="calcDisclaimer">Os cálculos consideram uma unidade por pedido e receita
        exclusivamente do produto informado. Despesas fixas não informadas,
        reembolsos e impostos complexos não são modelados. Os dados digitados
        não são salvos ou enviados às integrações do AdPilot.</p>
    </section>
  </main>;
}
