import {
  calculateProductTargets, desiredProfitFromPercentage,
  type CostForm,
} from "./product-targets";

export type ProductGoalInput = {
  productLabel:string;
  priceCents:number; productCostCents:number; taxBasisPoints:number;
  paymentBasisPoints:number; paymentFixedCents:number;
  shippingSubsidyCents:number; packagingCents:number; otherCostsCents:number;
  desiredProfitBasisPoints:number;
};

export type ProductGoal = ProductGoalInput & {
  id:string; targetCpaCents:number; targetRoasHundredths:number;
  createdAt:string;
};

export type LinkedCampaign = {
  accountId:string; campaignId:string; campaignName:string; currency:string;
  linkedGoalId:string|null; linkedProductLabel:string|null;
  linkedCpaCents:number|null; linkedRoasHundredths:number|null;
  linkedAt:string|null;
};

export type PlanningCatalog = {goals:ProductGoal[];campaigns:LinkedCampaign[]};

function fixed(raw:string):number|null {
  if(!/^\d+(?:[.,]\d{1,2})?$/.test(raw.trim())) return null;
  const [whole,fraction=""]=raw.trim().replace(",",".").split(".");
  const cents=Number(whole)*100+Number(fraction.padEnd(2,"0"));
  return Number.isSafeInteger(cents)&&cents>=0&&cents<=100_000_000?cents:null;
}
function bps(raw:string):number|null {
  const value=fixed(raw);
  return value!==null&&value<=10000?value:null;
}

export function makeProductGoalInput(form:CostForm,profitPercent:string,
    label:string): {ok:true;value:ProductGoalInput}|{ok:false;error:string} {
  const cost=calculateProductTargets({
    ...form,
    desiredProfit:desiredProfitFromPercentage(form.price,profitPercent).ok
      ? (() => {
          const profit=desiredProfitFromPercentage(form.price,profitPercent);
          return profit.ok?profit.amountInput:"";
        })()
      : "",
  });
  if(!cost.ok || cost.result.status!=="READY")
    return {ok:false,error:"Informe custos e lucro válidos para obter um CPA-alvo positivo."};
  if(label.trim().length<2 || label.trim().length>120 || /[\u0000-\u001f\u007f]/.test(label))
    return {ok:false,error:"Informe um nome de produto entre 2 e 120 caracteres."};
  const desired=desiredProfitFromPercentage(form.price,profitPercent);
  if(!desired.ok) return {ok:false,error:"Informe o lucro desejado em percentual."};
  const parsed={
    priceCents:fixed(form.price),
    productCostCents:fixed(form.productCost),
    taxBasisPoints:bps(form.taxPercent),
    paymentBasisPoints:bps(form.paymentPercent),
    paymentFixedCents:fixed(form.paymentFixed),
    shippingSubsidyCents:fixed(form.shippingSubsidy),
    packagingCents:fixed(form.packaging),
    otherCostsCents:fixed(form.otherCosts),
  };
  if(Object.values(parsed).some(x=>x===null))
    return {ok:false,error:"Informe todos os custos explicitamente, mesmo quando forem zero."};
  // The backend recomputes all goal values independently and never trusts
  // client-provided CPA or ROAS.
  return {ok:true,value:{
    productLabel:label.trim(),
    priceCents:parsed.priceCents!,productCostCents:parsed.productCostCents!,
    taxBasisPoints:parsed.taxBasisPoints!,paymentBasisPoints:parsed.paymentBasisPoints!,
    paymentFixedCents:parsed.paymentFixedCents!,
    shippingSubsidyCents:parsed.shippingSubsidyCents!,packagingCents:parsed.packagingCents!,
    otherCostsCents:parsed.otherCostsCents!,
    desiredProfitBasisPoints:desired.percentBasisPoints,
  }};
}
