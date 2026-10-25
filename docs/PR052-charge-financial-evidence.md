# PR052 — Cobranças Pagar.me e evidência financeira agregada

## Contexto
Homologação PR051: 31 candidatos exatos Pagar.me/Nuvemshop, 26 sem conflito
observado e 5 com diferença de valor bruto. Nas 5 diferenças, o desconto
Nuvemshop coincidiu aritmeticamente com a diferença, mas isso não
comprova captura de valor, estorno ou repasse. A seleção da Nuvemshop
com subtotal, discount e discount_gateway respondeu em todas as páginas;
o detalhe de cada um dos 5 candidatos foi comparável.

## Novo diagnóstico
Adiciona chargeEvidence ao relatório já existente do endpoint
POST /api/v1/integrations/stone/nuvemshop-correlation.

Somente se as três populações foram paginadas integralmente e existem
de 1 até 5 candidatos únicos com valores BRL diferentes:
- usa os vínculos de cobranças já observadas via GET /charges paginado;
- exige EXATAMENTE uma charge relacionada ao pedido candidato;
- consulta GET /charges/{id} para até 5 cobranças; sem retry;
- exige ID da cobrança igual à listada, vínculo order_id confirmado,
  status inalterado e currency BRL; rejeita dados ambíguos ou alterados;
- classifica amount (valor da cobrança), paid_amount (valor pago
  reportado), refunded_amount (valor estornado reportado),
  status e presença de valores; devolve somente CONTAGENS;
- em caso de erro HTTP interrompe as consultas seguintes;
- não interpreta paid_amount ausente como zero; idem refunded_amount.

chargeEvidence contém status, targeted, singleChargeCandidates,
missingOrMultipleCharges, attempted, comparable, rejectedOrChanged,
unavailable, observedStatuses, chargeAmountObserved,
chargeAmountMatchesNuvemshopTotal, chargeAmountMatchesPagarmeOrder,
paidAmountObserved, paidAmountMatchesNuvemshopTotal,
paidAmountMatchesPagarmeOrder, paidAmountDiffersFromNuvemshopTotal,
refundAmountObserved e positiveRefundAmount.

Uma única cobrança PAID com paid_amount igual ao total Nuvemshop é
uma observação da API, NÃO prova de liquidação bancária ou recebimento.
Não combina discount + discount_gateway para não fazer dupla contagem;
frete continua desconhecido e nenhum dado de taxas ou repasses é lido.
Uma cobrança múltipla não é somada de maneira arbitrária a um pedido.

## Limites de segurança
- Apenas GET de API Pagar.me Core v5 e Nuvemshop.
- O novo GET de cobrança usa identificador validado e um domínio base fixo
  do cliente Stone, com customização acessível só em testes do pacote.
- Até 5 detalhes adicionais na execução; total de até 44 GETs
  considerando 10 páginas x 50 por fonte, 1 fallback, 3 probes Nuvemshop,
  5 detalhes Nuvemshop e 5 detalhes de charge Pagar.me.
- Sem PII, cartão, segredo, URL, ID, códigos externos, dinheiro individual,
  cobranças individuais ou timestamps no JSON agregado.
- Sem alterações em bancos, migrações, filas, dashboard, webhooks,
  sincronização, Meta ou operações monetárias.
- Indicadores financiallyVerified, paymentSettlementVerified,
  ordersMatchedToNuvemshop e ordersMatchedToCampaigns seguem false.
- As evidências são exploratórias e não atômicas; as APIs podem mudar
  de estado entre os GETs.

## Homologação depois de CI e merge manual
1. git checkout main && git pull --ff-only origin main
2. Ativar temporariamente as duas flags já existentes no .env:
   ADPILOT_STONE_DIAGNOSTICS_ENABLED=true
   ADPILOT_ORDER_LIST_DIAGNOSTICS_ENABLED=true
3. docker compose up -d --build --force-recreate api
4. Executar no terminal:

   curl -fsS -u admin -X POST \
     'http://localhost:18080/api/v1/integrations/stone/nuvemshop-correlation?pageSize=50&maxPages=10' \
     | jq '{uniqueOneToOneCandidates,amountsDifferent,investigation,supplemental,chargeEvidence,warnings}'

5. Interpretar apenas contagens e estados. Se receber 502, repetir
   sem -f/jq para obter código sanitizado. Desligar as flags ao fim e recriar API.

Referência oficial de consulta de cobrança:
https://docs.pagar.me/reference/obter-cobran%C3%A7a
Referência oficial dos atributos de cobrança:
https://docs.pagar.me/reference/cobran%C3%A7as-1