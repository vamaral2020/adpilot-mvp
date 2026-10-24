# PR040 — Auditoria indicativa de valores brutos de pedidos

## Alcance

Esta PR adiciona uma verificação **somente leitura**, sob demanda, entre
os valores brutos atuais dos pedidos retornados pela Nuvemshop e as cópias
em analytics_order_snapshot no banco local do AdPilot.

Não é conciliação de pagamentos, validação contábil, apuração de lucro ou
comprovação de vendas originadas pela Meta. A API consultada apresenta
pedidos no momento da leitura, não um extrato transacional imutável.

Não há migration, atualização automática de snapshots, cursor, sync, Meta,
MER, ROAS, orçamento ou interface.

## Segurança operacional

- HTTP Basic global, Cache-Control: no-store, somente totais por moeda.
- Rota POST /api/v1/analytics/orders/value-audit
- Flag existente: ADPILOT_ORDER_LIST_DIAGNOSTICS_ENABLED=false por padrão.
- Limite por chamada: pageSize 1 a 100; maxPages 1 a 20 (até 2000 pedidos).
- São buscados somente id, status, payment_status, total e currency, sem
  nomes, clientes, produtos, tokens ou transações.
- A comparação usa IDs apenas internamente para a consulta em lote,
  parametrizada por workspace. Nenhum ID é retornado.

### Executar depois do merge manual

Atualize a main e habilite a flag temporariamente no arquivo .env:

    ADPILOT_ORDER_LIST_DIAGNOSTICS_ENABLED=true

Depois recrie a API:

    docker compose up -d --build api

Execute com suas credenciais HTTP Basic:

    curl -fsS -u admin -X POST \
      'http://localhost:18080/api/v1/analytics/orders/value-audit?pageSize=100&maxPages=20' \
      | jq .

Desabilite a flag ao final e recrie a API:

    ADPILOT_ORDER_LIST_DIAGNOSTICS_ENABLED=false
    docker compose up -d --force-recreate api

## Como interpretar

- upstreamPaginationReconciled=true exige esgotamento da paginação, um
  x-total-count presente e estável, todos os IDs únicos contabilizados e
  nenhum ID duplicado. Não é prova de consistência em tempo real.
- orderIdsMissingLocally conta pedidos da listagem sem snapshot local.
- orderStatusDifferences e paymentStatusDifferences apontam estados
  diferentes no momento da consulta; podem representar mudança legítima.
- eligibilityDifferences compara a regra upstream (paid + open/closed)
  à coluna elegível do snapshot local. Pedidos paid+cancelled não são
  tratados como receita elegível.
- upstreamCancelledOrders e upstreamRefundStatusOrders são contagens de
  status; NÃO equivalem a valores efetivamente estornados.
- amountDifferences conta pedidos com totais brutos diferentes quando
  ambos são válidos e as moedas coincidem.
- currencyDifferences conta moedas válidas e discordantes. Essas ordens
  NÃO são agregadas em comparableGrossByCurrency.
- comparableGrossByCurrency usa apenas pares presentes nas duas fontes,
  simultaneamente elegíveis, com o mesmo status e moeda, e valores válidos.
  Os valores são BRUTOS e estão segmentados por moeda (sem conversão).
  signedGrossDifference = upstreamGrossTotal - localGrossTotal.
- financiallyVerified=false e ordersMatchedToCampaigns=false sempre,
  independentemente de valores ou paginação. Nenhum evento de pagamento,
  taxa, frete, desconto, chargeback ou refund foi reconciliado.

## Critérios de revisão

Conferir o comportamento de paginação truncada, limites de leitura,
duplicidades, invalidez de valores, status paid+cancelled, campos da
projeção, consistência por moeda, falhas upstream e a ausência de dados
sensíveis no HTTP. Não ativar a rota permanentemente.
