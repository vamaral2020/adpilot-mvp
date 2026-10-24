# PR042 — Diagnóstico de valores informados pelo comprador e provedores

## Contexto

A loja usa Pagar.me para cartão, Nuvem Pago ocasionalmente para Pix e
está testando Mercado Pago. Os provedores são identificados somente
quando os campos informados pela Nuvemshop permitem classificação
conservadora. Não inferir automaticamente o método de pagamento pelo
nome do gateway.

A API Orders 2025-03 documenta:
- gateway e gateway_name: pistas do provedor do pedido;
- payment_details.method: método reportado (somente esse subcampo);
- total: total bruto do pedido;
- total_paid_by_customer: valor informado pela Nuvemshop como pago
  pelo cliente, não necessariamente igual ao total do pedido;
- total_paid_by_customer_including_fees: valor pago pelo comprador
  incluindo eventuais taxas cobradas ao cliente, NÃO taxas MDR do lojista.

Referência: https://nuvemshop.dev/docs-api/resources/2025-03/order

## API de diagnóstico do AdPilot

    POST /api/v1/analytics/orders/payment-signals?pageSize=100&maxPages=20

- HTTP Basic da aplicação, Cache-Control: no-store;
- Exige ADPILOT_ORDER_LIST_DIAGNOSTICS_ENABLED=true (padrão false);
- Somente GET upstream com allowlist de campos, sem PII, IDs de cobrança,
  identificadores de transação, gateway_link ou número/bandeira do cartão;
- Até 20 páginas de 100 pedidos (no máximo 2.000);
- Nenhuma escrita ou alteração de sync/cursor, tabela, dashboard ou Meta;
- ID usado somente para detectar duplicidade em memória, nunca retornado;
- Totalização APENAS de pedidos com status paid e order status open/closed;
- Grupos por provedor identificado, método de pagamento e moeda;
- Totais de pedido, reportado pago e reportado pago incluindo taxas
  mantêm denominadores independentes; não subtrair tais somas
  indiscriminadamente. Os contadores de pares comparáveis são explícitos.

## Classificação de provedores (não verificação)

PAGARME, NUVEM_PAGO, MERCADO_PAGO, OFFLINE, INTERNAL, UNRECOGNIZED,
MISSING, AMBIGUOUS. Os identificadores raw nunca são devolvidos:
somente rótulos da whitelist; se gateway e gateway_name indicarem
provedores diferentes, a categoria é AMBIGUOUS.

O método é independente: CREDIT_CARD, DEBIT_CARD, PIX, BOLETO,
BANK_TRANSFER, CASH, CUSTOM, OTHER ou MISSING.

gateway representa o gateway registrado no pedido, não prova de qual
provedor liquidou a operação. Pix não implica Nuvem Pago ou Mercado
Pago; nem cartão implica necessariamente Pagar.me.

## Limitações não negociáveis

- financiallyVerified=false, paymentSettlementVerified=false e
  ordersMatchedToCampaigns=false SEMPRE.
- total_paid_by_customer pode ser alterado por edições e não garante
  liquidação ou valor líquido após reembolso.
- total_paid_by_customer_including_fees são taxas eventualmente cobradas
  do comprador, e não a taxa de processamento do comerciante.
- Não são validados chargebacks, reembolsos liquidados, antecipações,
  bandeiras, MDR, parcelas, status da cobrança ou extratos.
- Sem ligação order_id ↔ ID da transação no Pagar.me/Mercado Pago
  não é possível afirmar conciliação um a um.
- O resultado é snapshot temporal da API de pedidos, não um ledger.
- MISSING/UNRECOGNIZED são resultados aceitáveis; jamais atribuir
  provedores a partir de valores desconhecidos.

## Execução após CI e merge manual

Atualizar main, habilitar temporariamente no .env:

    ADPILOT_ORDER_LIST_DIAGNOSTICS_ENABLED=true

    docker compose up -d --build --force-recreate api
    curl -fsS -u admin -X POST \
      'http://localhost:18080/api/v1/analytics/orders/payment-signals?pageSize=100&maxPages=20' \
      | jq .

Desabilitar a flag após homologação e recriar a API.

## Próximas integrações

Em etapa futura, autenticar cada provedor com suas credenciais
específicas e escopo de leitura (sem copiar segredos para chats ou PRs),
e correlacionar ID de pedido + gateway_id / ID de cobrança autenticado
com valores, status, estornos e, depois, liquidação. O endpoint de
transações do domínio Nuvemshop Payment Apps não é assumido disponível
para aplicativos comuns de lojistas.

APIs oficiais disponíveis para planejamento:
- Pagar.me Core v5: GET /charges/{charge_id}
  https://docs.pagar.me/reference/obter-cobran%C3%A7a
- Mercado Pago: GET /v1/payments/{id}
  https://www.mercadopago.com.br/developers/pt/reference/online-payments/checkout-pro-preferences/get-payment/get

Nenhuma integração financeira externa é criada nesta PR.
