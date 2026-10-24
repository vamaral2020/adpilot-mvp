# PR043 — Stone PSP / Gateway e cobertura LIST vs DETAIL

## Motivação

Na PR042, a loja apresentou 111 pedidos, 80 elegíveis, dos quais 29
(R$ 6.415,31 em valores brutos) tinham gateway UNRECOGNIZED.
Os campos total_paid_by_customer vieram ausentes nos 111 pedidos, embora
total_paid_by_customer_including_fees estivesse preenchido.

O usuário informou uso de Pagar.me para cartão, Nuvem Pago para alguns
Pix e Mercado Pago em teste. Pagar.me passou à marca Stone, mas isso não
transforma Stone Gateway e Stone PSP em produtos equivalentes.

Fontes:
- https://www.pagar.me/
- https://atendimento.nuvemshop.com.br/pt_BR/pagarme/como-instalar-o-aplicativo-pagar-me
- https://atendimento.nuvemshop.com.br/pt_BR/pagarme/como-integrar-o-pagarme-gateway-na-minha-loja

## Mudança de classificação na auditoria PR042

A rota existente POST /api/v1/analytics/orders/payment-signals passa a
reconhecer SOMENTE aliases exatos, com pontuação/espaços normalizados:

- STONE_PSP: stonepsp / pspstone
- STONE_GATEWAY: stonegateway / gatewaystone
- STONE_UNSPECIFIED: stone (marca sem produto conhecido)
- PAGARME: pagarme / pagarmepsp (rótulos legados)
- MERCADO_PAGO, NUVEM_PAGO, OFFLINE e INTERNAL preservados

Se ambos os campos gateway e gateway_name tiverem classificação e
concordarem, ela é usada. Um "STONE_UNSPECIFIED" junto de um produto
Stone específico pode ser refinado pelo segundo campo. Divergências
entre dois provedores/produtos específicos continuam AMBIGUOUS.
Não inferir a adquirente subjacente nem afirmar que PAGARME=STONE_PSP
numa operação individual sem fonte adicional.

### Diagnóstico seguro para unknowns

O response ganhou unknownGatewayEvidence, somente para
UNRECOGNIZED / AMBIGUOUS, agrupando:
- gatewayField/gatewayNameField: PRESENT ou ABSENT_OR_NULL;
- brandHint: STONE_TEXT, PAGARME_TEXT, MERCADO_PAGO_TEXT,
  NUVEM_PAGO_TEXT, MULTIPLE_BRAND_TEXTS ou NO_RECOGNIZED_BRAND_TEXT;
- orders e eligiblePaidOrders.

Atenção: brandHint é indício vocabular, NÃO classificação nem prova:
mesmo substrings como "notstone" podem gerar STONE_TEXT, sem alterar
o provedor classificado. NUNCA expor identificadores brutos,
nomes arbitrários, hashes, pedido, cartão, conta ou dados pessoais.

## Novo endpoint de cobertura de campo

    POST /api/v1/analytics/orders/payment-field-probe
       ?pageSize=100&maxPages=20&maxDetailOrders=5

A rota é autenticada, Cache-Control: no-store e depende da flag já
existente ADPILOT_ORDER_LIST_DIAGNOSTICS_ENABLED=true
(desabilitada por padrão).

- Lista até 2.000 pedidos, sem filtros de atualização;
- Consulta no máximo 5 detalhes GET /orders/{id}, priorizando
  pedidos elegíveis com total_paid_by_customer ausente/nulo na LIST;
- Usa projeção id,status,payment_status,total,currency,gateway,
  gateway_name,total_paid_by_customer,
  total_paid_by_customer_including_fees,payment_details.method;
- Conta ABSENT, NULL, NUMERIC, INVALID em LIST e DETAIL
  separadamente para os dois campos monetários;
- NumericInDetailWhenListAbsentOrNull apenas sinaliza recuperação
  da presença de um valor válido no detalhe;
- Nunca exibe valores individuais, IDs, nome de gateway cru, tokens
  ou PII na resposta; não grava ou altera cursores;
- Falhas upstream, 404 e detalhes com ID divergente são interrompidas,
  NUNCA traduzidas para valor zero/ausência de recebimento.

Tanto o total_paid_by_customer quanto o IncludingFees são campos de
pedido, NÃO são ledger de cobrança do provedor, valor líquido liquidado,
taxa MDR, extrato bancário ou validação de estorno. Não reclassificar
financiallyVerified/paymentSettlementVerified como true.

## Procedimento após CI e merge manual

1. Atualizar main, confirmar flag temporária no .env e reconstruir API:

    git checkout main && git pull --ff-only origin main
    # ADPILOT_ORDER_LIST_DIAGNOSTICS_ENABLED=true
    docker compose up -d --build --force-recreate api

2. Comparar novamente a auditoria por provedor:

    curl -fsS -u admin -X POST \
      'http://localhost:18080/api/v1/analytics/orders/payment-signals?pageSize=100&maxPages=20' | jq .

3. Verificar presença dos campos nos detalhes (máximo 5):

    curl -fsS -u admin -X POST \
      'http://localhost:18080/api/v1/analytics/orders/payment-field-probe?pageSize=100&maxPages=20&maxDetailOrders=5' | jq .

4. Voltar ADPILOT_ORDER_LIST_DIAGNOSTICS_ENABLED=false e recriar API.

## Critérios de homologação

- Ver se parte dos antigos 26 cartões UNRECOGNIZED corresponde realmente
  a Stone PSP/Stone Gateway; não assumir resultado a priori.
- Contabilizar quantidade remanescente de gateways desconhecidos e
  AMBIGUOUS, sem enviar raw strings ao chat.
- Se campo continuar ausente nos detalhes, encerrar essa investigação
  sem preencher lacunas com o campo incluindo taxas automaticamente.
- Finanças reconciliadas exigirão provedor de pagamento, transações,
  estornos e repasses confirmados em outra integração futura.
