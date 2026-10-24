# PR048 — Datas ISO-8601 Nuvemshop e investigação aritmética de diferenças

## Contexto

Na PR047, de 31 candidatos por referência externa numérica exata:
- 26 apresentaram igualdade de valores brutos, 5 diferenças;
- em todas as 5 diferenças o Pagar.me retornou valor bruto maior;
- 31 timestamps Nuvemshop como UNPARSEABLE, contra 31 PARSED Pagar.me.

A documentação oficial da Nuvemshop exemplifica created_at em
ISO 8601 com offset compacto (ex.: 2022-11-15T19:47:32+0000).
Java OffsetDateTime.parse não aceita esse deslocamento compacto diretamente.
Fonte: https://tiendanube.github.io/api-documentation/resources/order

## Mudanças

Sem nova rota: acrescenta o objeto investigation à resposta atual do
POST /api/v1/integrations/stone/nuvemshop-correlation?pageSize=50&maxPages=10

### Datas

Novo parser CrossProviderTimestamp exige deslocamento UTC explícito:
- UTC Z (UTC_Z);
- ISO com offset +HH:MM (ISO_OFFSET_EXTENDED);
- ISO compacto +HHMM / -HHMM (ISO_OFFSET_COMPACT_HHMM);
- horário local sem offset (LOCAL_WITHOUT_OFFSET): NÃO comparável;
- ABSENT_OR_NULL, NON_STRING, INVALID_STRING,
  UNRECOGNIZED_STRING_FORMAT: não comparáveis.

Não infere fuso do servidor ou da loja. Não retorna timestamp bruto.
A coluna audit.candidateNuvemshopCreatedAtFields permanece com
PARSED / UNPARSEABLE / ABSENT_OR_NULL.
Novo campo investigation.candidateNuvemshopTimestampFormats resume
a classificação das datas dos candidatos únicos.

### Campos monetários

Projeção Nuvemshop expandida apenas com campos primitivos documentados:
subtotal, discount, discount_gateway, shipping_cost_customer.
Sem cliente, itens, cartões, transações, estornos ou extrato financeiro.

Para candidatos um-para-um com valores comparáveis e diferentes entre
Pagar.me amount (centavos) e Nuvemshop total (reais), retorna apenas
contagens agregadas:
- mismatchedGrossComponentAvailability: quantos divergentes possuem
  cada componente numérico válido;
- grossMismatchesWhereProviderEqualsSubtotal: quantas vezes amount
  Pagar.me equivale ao subtotal;
- grossMismatchesWhereDifferenceEqualsDiscount: quantas vezes o delta
  Pagar.me maior que Nuvemshop equivale ao discount;
- grossMismatchesWhereDifferenceEqualsCustomerShipping: quantas vezes
  o delta nesse sentido equivale ao frete do cliente;
- grossMismatchesWithConsistentShopSubtotalPlusShippingMinusDiscount:
  quantos pedidos obedecem à identidade aritmética
  subtotal + frete - desconto = total.

Esses indicadores são HIPÓTESES ARITMÉTICAS, NÃO diagnóstico causal.
discount_gateway só tem presença contabilizada; não é adicionado ao
discount para evitar provável dupla contagem.
A fórmula de composição pode não se aplicar a todos os pedidos.

### Segurança e invariantes

- Requer HTTP Basic, Nuvemshop conectada e as DUAS flags de
  diagnósticos ativas temporariamente.
- As flags são false por padrão.
- Somente GET ao provedor, até 10 páginas x 50 por coleção;
  não segue URL externa de paginação.
- Sem alterações em banco, cursor, worker, dashboard, webhooks,
  migrations, pedidos, pagamentos, Meta ou credenciais.
- Sem divulgar IDs, códigos, valores individuais, datas,
  dados de cliente ou texto de gateway cru.
- Os indicadores financiallyVerified, paymentSettlementVerified,
  ordersMatchedToNuvemshop e ordersMatchedToCampaigns permanecem false.

### Testes

- +0000, -0300, -03:00, Z, frações de segundo, offsets inválidos
  e ausência de timezone;
- datas equivalentes entre fontes com offsets diferentes;
- evidência de desconto, frete e subtotal em agregados;
- campos financeiros ausentes e invariantes financeiros false;
- regressão de colisões e IDs ambíguos.

## Homologação APÓS aprovação CI e merge MANUAL

git checkout main
git pull --ff-only origin main

Temporariamente no .env privado:
ADPILOT_STONE_DIAGNOSTICS_ENABLED=true
ADPILOT_ORDER_LIST_DIAGNOSTICS_ENABLED=true
PAGARME_SECRET_KEY já deve estar presente, não divulgar.

docker compose up -d --build --force-recreate api

curl -fsS -u admin -X POST \
  'http://localhost:18080/api/v1/integrations/stone/nuvemshop-correlation?pageSize=50&maxPages=10' \
  | jq .

Se houver erro HTTP, repetir sem -f e sem jq.
Depois desabilitar as duas flags e recriar a API.
