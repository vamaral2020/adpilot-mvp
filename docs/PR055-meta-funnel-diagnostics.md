# PR055 — Diagnóstico interpretativo de funil de anúncios Meta

## Objetivo

Explicar onde há sinais de avanço entre cliques, visitas, carrinhos,
inícios de checkout e compras atribuídas, **sem inventar jornadas individuais
nem culpar uma etapa** pela ausência de eventos.

Esta PR entrega o contrato/backend; a interface visual consultiva fica para a PR056.

## API read-only

`GET /api/v1/meta/campaigns/{campaignId}/funnel-diagnostics?since=YYYY-MM-DD&until=YYYY-MM-DD&pageSize=100&maxPages=3`

Autenticação Basic existente, `Cache-Control: no-store`. É chamada
única a `MetaDeliveryInterpreterService.inspect`, que valida ID, vínculo
à conta Meta configurada, janela UTC de até 31 dias, página <=100 e
no máximo 3 páginas para cada tipo de consulta. Nenhum write, banco,
Pixel, Conversions API, pedido individual, customer ID ou segmentação
é consultado pela PR055.

Retorna `campaign` (agregado dos insights **somente de conjuntos**),
`adsets` e `ads` (indicadores individuais) sem somar adsets com ads.
Contém `findings`, `warnings`, `analysisMode`, `partialCurrentDay`,
`complete` e cobertura de paginação, com `currency` vindo da Meta.

### Campos do funil

- `impressions`, `allClicks`, `linkClicks`;
- `landingPageViews`, `addToCarts`, `initiatedCheckouts`;
- `attributedPurchases`;
- `visitsPerLinkClickPercent`, `cartsPerVisitPercent`,
  `checkoutsPerCartPercent`, `purchasesPerCheckoutPercent`;
- `costPerLandingPageView`, `costPerAddToCart`,
  `costPerInitiatedCheckout`, `attributedCpa`.

Relações usam divisões de *contagens de eventos agregados*, com quatro
casas decimais. **Não são taxas de conversão de pessoas únicas,
nem um funil coorte-sequencial ou prova de atribuição causal.**

Para qualquer indicador opcional, zero explicitamente retornado pela
Meta é preservado. Ausência de uma ação, denominador zero e qualquer
evento ausente em um dos conjuntos tornam a respectiva razão `null`.
As somas opcionais só são apresentadas se todos os conjuntos observados
reportarem aquela contagem; uma amostra de paginação incompleta não
deve ser tratada como valor integral da campanha.

### Checkout

PR055 lê `InitiateCheckout` das ações da própria consulta Insights.
Aliases reconhecidos (em ordem de precedência, nunca somados):
`initiate_checkout`, `omni_initiated_checkout`,
`offsite_conversion.fb_pixel_initiate_checkout`,
`offsite_conversion.initiate_checkout`.
Nenhuma nova permissão Meta ou chamada adicional é necessária;
a compatibilidade e disponibilidade desses aliases dependem do
provedor e serão verificadas na homologação real.

### Findings conservadores

- `FUNNEL_INCOMPLETE_EVIDENCE`: paginação incompleta / sem linhas;
- `PROVISIONAL_CURRENT_DAY`: inclui hoje UTC;
- `TRAFFIC_OPTIMIZES_VISITS_NOT_PURCHASES`: objetivo de tráfego;
- `PURCHASE_ACTION_NOT_REPORTED`: compra não informada, não zero;
- `CHECKOUT_ACTION_NOT_REPORTED`: carrinhos presentes, checkout não informado;
- `SPARSE_CART_ACTIONS`: observação exploratória **apenas para objetivo
  de vendas**, >=100 visualizações e menos de 5 ações de carrinho;
- `NON_MONOTONIC_EVENT_COUNTS`: eventos posteriores excedem anteriores,
  o que pode acontecer sem qualquer erro causal.

Limite >=100 / menos de 5 é **heurística para investigar**, nunca
benchmark Meta nem threshold de pausa/escala. Confiança dos findings
é somente `INSUFFICIENT` ou `LOW`, não recomendação de orçamento.

Se `complete=false` (paginação), os findings ficam limitados à
necessidade de completar a coleta; observações de linhas seguem
visíveis apenas como amostra. Se `partialCurrentDay=true`,
warning extra impede análise como dia final, mesmo quando `complete=true`.
A janela usa UTC para comparação com PR053/PR054, mas a conta Meta
pode ter outro fuso e a atribuição pode mudar retroativamente.

## Fora do escopo

- Não há associação evento/pedido/conjunto/anúncio.
- Sem conclusão de CPA financeiro verificado, margem, lucro ou
  incremento de vendas; receita atribuída não é pagamento conciliado.
- Sem rankear campanhas de diferentes objetivos, nem alterar orçamentos.
- Sem coleta histórica persistida de funil, nem auto refresh ou dashboard
  visual (PR056).

## Testes e homologação

JUnit: precedência de aliases, nulidade versus zero, contagens inválidas,
soma por nível, derivação monetária, denominador zero, controle de
paginação, parcialidade de hoje, evento crescente, dados vazios,
objetivo de tráfego e `no-store` do controller.

Após merge manual e atualização local:

```bash
curl -fsS -u admin \
  'http://localhost:18080/api/v1/meta/campaigns/52597606088988/funnel-diagnostics?since=2026-10-20&until=2026-10-26' \
  | jq '{
      campaignId, campaignObjective, analysisMode,
      partialCurrentDay, complete, campaign, findings, warnings
    }'
```

**Atenção:** exemplo datado de 26/10/2026. Para monitorar hoje,
atualize a data `until`; se ela já passou, será análise histórica.

A segunda campanha para comparação exploratória é
`52597352471388` (objetivo tráfego). Conferir `initiatedCheckouts`
com os dados reais da Meta; se `null`, não concluir abandono de checkout.

CI: Maven JUnit, build/test frontend inalterado, Compose smoke.
