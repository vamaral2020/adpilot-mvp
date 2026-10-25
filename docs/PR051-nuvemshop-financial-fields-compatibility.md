# PR051 — Compatibilidade da projeção financeira Nuvemshop

## Evidência
Na homologação PR050, 31 candidatos por código exato, 31 datas dentro de 48 horas,
26 com sinais alinhados e cinco com divergência de valor.
Sondagens GET de um item confirmaram FIELD_PRESENT para subtotal, discount e
discount_gateway; shipping_cost_customer retornou HTTP 404.
Uma consulta de detalhe retornou falha e o diagnóstico interrompeu novas leituras.

## Correções
- Exclui shipping_cost_customer da projeção ampliada da listagem e da seleção
  do detalhe. Mantém apenas subtotal, discount e discount_gateway com moeda e total.
- O campo de frete não é inferido, arredondado nem substituído por zero.
  Métricas dependentes de frete ficam sem evidência e devem ser interpretadas
  juntamente com o warning NUVEMSHOP_SHIPPING_COMPONENT_NOT_REQUESTED_OR_VERIFIED.
- O fallback PR049 continua disponível somente diante de HTTP 404 na primeira
  página da seleção ampliada, sem misturar projeções entre páginas.
- Na listagem ampliada bem-sucedida, até cinco detalhes de candidatos com valores
  divergentes são consultados para conferir que ID, moeda e total permanecem iguais.
- No fallback, a sondagem de campos individuais usa somente os três campos
  restantes, sem nova tentativa deliberada do campo com HTTP 404 documentado.
- A janela exata de 48 horas e as classes de qualidade de candidatos da PR050
  são preservadas. Nenhum candidato é promovido a vínculo financeiro verificado.

## Segurança e limites
- Somente GET aos fornecedores, sem dados pessoais ou valores individuais na resposta.
- Até 10 páginas x 50 por população, um fallback, no máximo três sondagens e cinco
  detalhes, quando elegíveis: teto de 39 chamadas GET à Nuvemshop/Pagar.me.
- Nenhuma nova rota, migration, escrita no banco, cursor, job, webhook ou Meta.
- Permanecem false: financiallyVerified, paymentSettlementVerified,
  ordersMatchedToNuvemshop, ordersMatchedToCampaigns.
- Mesmo soma de subtotal e desconto não comprova origem financeira, captura ou
  repasse bancário. Frete desconhecido impede concluir composição completa do total.

## Testes
- Simulação upstream retorna 404 quando o fields inclui shipping_cost_customer.
- A seleção com os três campos funciona em duas páginas e no detalhe do pedido.
- Verificação de projeção allowlist sem PII, cartão, token e frete bruto na resposta.
- Regressão da janela temporal exata e integridade das flags financeiras.
- Verificação de que detalhes funcionam após sucesso da listagem ampliada.

## Homologação após aprovação CI e merge manual
git checkout main
git pull --ff-only origin main

Habilitar temporariamente no arquivo .env as duas flags existentes:
ADPILOT_STONE_DIAGNOSTICS_ENABLED=true
ADPILOT_ORDER_LIST_DIAGNOSTICS_ENABLED=true

docker compose up -d --build --force-recreate api

curl -fsS -u admin -X POST \
  'http://localhost:18080/api/v1/integrations/stone/nuvemshop-correlation?pageSize=50&maxPages=10' \
  | jq '{uniqueOneToOneCandidates,amountsDifferent,creationTimesComparable,
      investigation,supplemental,warnings}'

Confirmar investigation.nuvemshopReadMode e supplemental.financialDetails.
Desabilitar novamente as flags e recriar a API ao fim da homologação.
Não compartilhar tokens, logs completos ou dados individuais.