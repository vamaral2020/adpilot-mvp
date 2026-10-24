# PR047 — Auditoria agregada de correlações Stone / Nuvemshop

## Motivação

Na homologação da PR046 foram encontrados 31 candidatos por code
numérico exato, 26 valores iguais, 5 diferentes, 1 sinal PAID
divergente, 1 método divergente, nenhum timestamp comparável,
56 códigos não numéricos e 3 pedidos Pagar.me em alvos duplicados.
Esses são resultados anteriores, não valores fixos na nova PR.

## Alterações

Preserva a rota e campos da PR046. Adiciona SOMENTE o campo
JSON audit no resultado agregado:

POST /api/v1/integrations/stone/nuvemshop-correlation?pageSize=50&maxPages=10

Dentro de audit:
- unsupportedCodeFormats: categorias fixas de formatos numéricos
  não canônicos / alfanuméricos / outros, sem retornar code bruto,
  sem remover prefixos e sem tentativa de matching aproximado.
- duplicateNuvemshopTargetGroups: número de IDs Nuvemshop atingidos
  por múltiplos pedidos Pagar.me. Candidatos duplicados continuam
  excluídos das comparações um-para-um.
- grossDifferenceDirectionAndMagnitude: contagens por direção
  PAGARME_GREATER / NUVEMSHOP_GREATER e diferença absoluta de
  até R$ 1, entre R$ 1,01 e R$ 10 ou acima de R$ 10.
  Apenas campos de pedido Pagar.me amount (centavos) e Nuvemshop
  total (reais), após conversão exata e comparação BRL/BRL.
- grossDifferencesAlsoPaymentStateMismatch: contagem cruzada entre
  montante e PAID divergentes.
- grossDifferencesAlsoMethodMismatch: contagem cruzada entre
  montante e método divergentes, somente com charge única observada.
- grossDifferencesWithOtherGatewayHint: valor divergente e marca
  Nuvemshop de outro gateway/conflito de rótulos.
- candidateProviderCreatedAtFields e candidateNuvemshopCreatedAtFields:
  contagens PARSED, ABSENT_OR_NULL, UNPARSEABLE. Sem divulgar datas.
- candidateGatewayHints: STONE_OR_PAGARME_REPORTED, OTHER_REPORTED,
  CONFLICTING_LABELS ou UNKNOWN_OR_MISSING. Um rótulo comercial
  não comprova processadora contratada nem settlement.

## Proteções

- Apenas GET nas duas APIs. Sem webhooks, writes, migrations, cursor,
  financeiro, Meta ou alterações de dashboard.
- Sem IDs de pedido/cobrança, códigos externos, valores individuais,
  datas, customer, nome, documento, cartão, endereço, token e
  texto de gateway cru nas respostas ou logs.
- Limites da PR046 preservados: 1..50 itens/página, 1..10 páginas
  por coleção; máximo 30 GETs, sem retry e sem seguir paging.next.
- Sem inferir origem das diferenças: descontos, frete, reembolsos,
  antecipações, taxas, capturas e repasses requerem fontes próprias.
- Somente igualdade literal de code numérico e ID/number Nuvemshop.
  Colisões e ambiguidades não são vínculos verificados.
- Totais da API são reconciliação de paginação, não snapshot atômico.
- Invariantes: financiallyVerified=false,
  paymentSettlementVerified=false, ordersMatchedToNuvemshop=false,
  ordersMatchedToCampaigns=false.
- Controle HTTP Basic do AdPilot, Nuvemshop conectada,
  ADPILOT_STONE_DIAGNOSTICS_ENABLED=true E
  ADPILOT_ORDER_LIST_DIAGNOSTICS_ENABLED=true, ambas false por padrão.
- HTTP Cache-Control no-store; dados processados somente em memória.

## Homologação APÓS CI e merge manual

1. git checkout main && git pull --ff-only origin main
2. Temporariamente no .env local:
   ADPILOT_STONE_DIAGNOSTICS_ENABLED=true
   ADPILOT_ORDER_LIST_DIAGNOSTICS_ENABLED=true
   PAGARME_SECRET_KEY deve existir apenas localmente.
3. docker compose up -d --build --force-recreate api
4. curl -fsS -u admin -X POST \
     'http://localhost:18080/api/v1/integrations/stone/nuvemshop-correlation?pageSize=50&maxPages=10' \
     | jq .
5. Analisar apenas JSON agregado. Desabilitar ambas as flags e
   recriar API ao terminar. Não enviar chave ou logs brutos.

## Próximos passos

Se datas faltarem no list endpoint, confirmar contrato do endpoint
e possivelmente diagnosticar pequena amostra por detalhe, sem
exposição de PII. Se gateway for outro, revisar hipótese de vínculo.
Se códigos alfanuméricos dominarem, investigar convenção contratual,
mas não criar associações por semelhança de texto.

Esta PR não substitui verificação de pagamento capturado,
refunds, chargebacks, taxas, recebíveis ou extrato bancário.
