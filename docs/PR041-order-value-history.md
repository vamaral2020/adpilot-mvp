# PR041 — Diagnóstico de histórico de valores da Nuvemshop

## Regra de escopo (importante)

A documentação da Nuvemshop 2025-03 diz que
GET /orders/{order_id}/transactions é exclusivo de Payment Apps.
O AdPilot tem uma integração de lojista, que não deve assumir essa permissão.

Esta PR consulta SOMENTE o histórico de alterações de valor de pedidos:

    GET /orders/{order_id}/history/values

Os registros acima podem indicar movimentos de pagamento e estorno, mas
NÃO SÃO transações do provedor financeiro. Um histórico vazio NÃO comprova
ausência de pagamento ou reembolso.

Documentação oficial:
- https://nuvemshop.dev/docs-api/resources/2025-03/transaction
- https://nuvemshop.dev/en-US/docs-api/resources/2025-03/order

## Endpoint do AdPilot

    POST /api/v1/analytics/orders/value-history-diagnostics
         ?pageSize=100&maxPages=20&maxHistoryOrders=30

- HTTP Basic, Cache-Control: no-store.
- Flag já existente ADPILOT_ORDER_LIST_DIAGNOSTICS_ENABLED=false por padrão.
- pageSize: 1..100, maxPages: 1..20, maxHistoryOrders: 1..50.
- Primeiro lista até 2.000 pedidos; depois consulta no máximo 50
  históricos individuais, sem gravar dados ou avançar os cursores.
- Prioridade intencional de investigação:
  1. Status de pagamento refunded / partially_refunded;
  2. Pedidos cancelados;
  3. Pedidos marcados como paid;
  4. Outros pedidos.
- A seleção NÃO é amostra aleatória nem representativa.
- Nunca retorna IDs de pedido, IDs de transações, dados pessoais,
  informações de gateway ou valores monetários.
- Os eventos retornam internamente apenas status e total_paid_diff.
  O serviço considera somente o SINAL do campo: positivo (pagamento
  reportado), negativo (reembolso reportado), zero, inválido/ausente.
  Não converte unidades do provedor em BRL, nem soma os valores.
- Eventos PAID e total_paid_diff negativo indicam possível reembolso
  no histórico de alterações do pedido; NÃO garantem transferência,
  liquidação, repasse bancário nem a totalidade dos estornos.
- Status PENDING e CANCELLED no histórico de valores NÃO contam como
  recibos de pagamento ou de reembolso.
- Sem prova de recebimentos, taxas, chargebacks, provedor, liquidação,
  repasses, atribuição Meta, CAC ou lucro.
- financiallyVerified=false, paymentSettlementVerified=false e
  ordersMatchedToCampaigns=false SEMPRE.

## Operação local após CI, revisão e merge manual

Habilite temporariamente no .env:

    ADPILOT_ORDER_LIST_DIAGNOSTICS_ENABLED=true

Reconstrua a API:

    docker compose up -d --build api

Execute com HTTP Basic:

    curl -fsS -u admin -X POST \
      'http://localhost:18080/api/v1/analytics/orders/value-history-diagnostics?pageSize=100&maxPages=20&maxHistoryOrders=30' \
      | jq .

Desabilite a flag ao terminar, e recrie a API:

    docker compose up -d --force-recreate api

## Interpretar corretamente

- upstreamPaginationReconciled: recibos de paginação da LISTA de pedidos
  coerentes; não estabelece snapshot transacional do provedor.
- entireOrderPopulationExamined: somente se a lista foi reconciliada
  e houve consulta de histórico para todos os IDs observados.
  Não comprova exaustividade real dos eventos/recebimentos.
- historiesQueried: tamanho da seleção; compare com
  uniqueUpstreamOrders para detectar amostra limitada.
- selectedRefundStatusOrders, selectedCancelledOrders,
  selectedPaidOrders podem se sobrepor; não somá-los.
- ordersWithNegativePaidMovement: dentre históricos consultados,
  pedidos com algum evento PAID de total_paid_diff negativo.
- ordersWithEmptyValueHistory: histórico vazio, SEM inferir ausência
  de pagamento ou reembolso.
- HISTORY_VALUES_UNINTERPRETABLE: dados inválidos/inesperados;
  não preencher lacunas com zero.

## Comportamento de falhas

Se qualquer consulta individual retornar HTTP 401/403/404/429 ou falhar
no transporte, o diagnóstico é INTERROMPIDO com código sanitizado.
404 não é traduzido em "sem transações" ou "sem reembolso".
Nenhuma resposta parcial é apresentada como uma auditoria concluída.

Não habilitar permanentemente em produção sem limites operacionais,
observabilidade e revisão de permissões.
