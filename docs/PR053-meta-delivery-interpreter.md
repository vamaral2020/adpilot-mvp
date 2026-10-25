# PR053 — Intérprete de entrega Meta Ads

## Objetivo de produto
O AdPilot traduz parâmetros e sinais de desempenho da Meta para ajudar o
gestor a escolher hipóteses de público, criativo, objetivo e investimento.
Não tenta reproduzir o algoritmo da Meta nem altera campanhas automaticamente.

## Entrega
GET /api/v1/meta/campaigns/{campaignId}/delivery-diagnostics
  ?since=YYYY-MM-DD&until=YYYY-MM-DD&pageSize=100&maxPages=3

Página Next.js: /interpretar, ligada ao menu principal da visão geral.
Consulta somente sob seleção explícita de campanha e intervalo.

- Autoriza via HTTP Basic existente.
- Verifica que a campanha pertence à conta Meta configurada ANTES de
  consultar seus conjuntos e Insights.
- Busca objetivo e status declarados da campanha; configuração allowlist de
  cada conjunto (optimization_goal, billing_event, bid_strategy,
  destination_type, effective_status).
- Promoted object é lido apenas para extrair um custom_event_type
  de vocabulário fixo, sem publicar pixel ID, custom event ID ou targeting.
- Busca Insights agrupados no período para level=adset e level=ad,
  incluindo spend, impressions, clicks totais, inline_link_clicks,
  actions, action_values e attribution_setting quando informados.
- Identifica LPV, AddToCart e Purchase usando precedência de eventos do
  backend, nunca somando purchase e omni_purchase simultaneamente.
- Ausência de ação de compra ou clique no link = NULL/não informado,
  nunca automaticamente zero.
- Calcula CTR de cliques no link, CPC de link, CPA de compra atribuída e
  ROAS atribuído apenas quando existem os respectivos denominadores.
- Mostra configuração declarada como orientação consultiva:
  revisão de objetivo de tráfego em campanha OUTCOME_SALES, conversões
  declaradas e presença/ausência de indicação do evento PURCHASE.

## O que NÃO é inferido
- Público efetivamente alcançado, expansão Advantage+, segmentação, interesses,
  qualidade da audiência e parâmetros de targeting.
- Fase de aprendizado, conteúdo integral do anúncio ou a causa de uma
  variação de CPA. Não coleta dados de identidade pessoal.
- Compra da Nuvemshop ligada a campanha, ROAS incremental, lucro líquido,
  atribuição causal ou valor realmente liquidado.
- Recomendação automática para aumentar, pausar ou redistribuir orçamento.

## Integridade e limites
- Datas UTC completas, sem hoje: 1 a 31 dias por requisição.
- pageSize de 1 a 100 e maxPages de 1 a 3 por coleção.
- Até 1 GET de perfil da campanha, 1 GET da conta, e 3 páginas de
  cada coleção (configurações, Insights adset, Insights ad): até 11 GETs.
- Nunca segue paging.next (contém URLs e possíveis credenciais);
  usa apenas cursor opaque after, com detecção de repetição e limite.
- Rejeita IDs e referências pertencentes a campanhas diferentes.
- Relatório complete=false quando paginação for limitada;
  nunca apresenta ranking global como conclusivo nesse caso.
- Não modifica modelos existentes de snapshots, PR052, Nuvemshop,
  autenticação, campanhas, orçamento, banco ou filas.
- Sem post, patch, delete ou permissão ads_management; permanece somente leitura.

## Testes
- Testes unitários do intérprete: objetivos de vendas vs tráfego,
  nomes sanitizados, prevenção de compras duplicadas por aliases,
  qualidade de amostras, ausência de ações, diferenças entre cliques
  totais e cliques no link, dados fora da campanha e paginação parcial.
- Teste de cliente HTTP em servidor local: chamadas GET, campos allowlist,
  validação da conta, descartar PII, pixel ID e targeting;
  validações de página, período e nível.
- Backend, frontend e smoke Compose no GitHub Actions.

## Homologação local, após revisão e merge manual
git checkout main
git pull --ff-only origin main
docker compose up -d --build --force-recreate api web

Abrir http://localhost:13000/interpretar
Selecionar campanha e intervalo de até 31 dias completos.
Se a tela reportar erro 400/502, conferir os campos permitidos pela
versão Graph e as permissões ads_read na conta real. Não expor token.
Também é possível consultar com curl -u admin a rota acima.

## Referências
Meta Marketing API: https://developers.facebook.com/docs/marketing-api/insights/
Meta Business SDK (inventário de campos de AdsInsights e AdSet):
https://github.com/facebook/facebook-nodejs-business-sdk