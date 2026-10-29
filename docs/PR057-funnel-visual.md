# PR057 — Interface visual e consultiva do funil Meta Ads

## Produto

A página já existente `/interpretar` mantém a escolha de campanha, intervalo
de até 31 dias, configuração de conjuntos e tabelas de Insights. Quando uma
campanha é selecionada, a PR057 consulta também, no servidor Next.js:

`GET /api/v1/meta/campaigns/{campaignId}/funnel-diagnostics?since=...&until=...`

O backend da PR055 e o hotfix PR056 são reutilizados **sem mudanças**.
Nenhum token é exposto ao navegador; a autenticação interna Basic e
`cache: no-store` existentes continuam. Ambos os relatórios são
consultados concorrentemente. Se um falhar, o outro continua acessível.
Antes de renderizar o funil, a UI verifica o ID da campanha e as datas
retornadas, evitando apresentação cruzada de dados.

## Componentes

- Painel de dados Meta com gasto observado, visualizações, compras
  atribuídas e CPA atribuído (sem confundir receita atribuída com paga).
- Lista visual de cinco etapas: cliques no link, visitas, carrinhos,
  inícios de checkout e compras atribuídas.
- Barras são **volumes de eventos**, dimensionadas pela maior contagem
  disponível. A visualização NÃO é uma coorte rastreada de usuários.
- Relações da API entre eventos são descritas como observações. Se a etapa
  posterior exceder a anterior ou a razão for maior que 100%, exibe
  `Não comparável`, nunca `1.100%` como taxa comercial.
- `null` é `Não informado`; zero explícito continua `0`.
  Divisão inválida, janela com investimento zero e atribuições tardias
  não geram CPA fictício.
- Datas incluindo hoje recebem identificação de dado provisório,
  advertência de atribuição e nenhuma decisão de orçamento.
- Paginação `complete=false` identifica amostra; razões e custos
  não são apresentados como consolidados.
- Findings backend traduzidos por catálogo pt-BR com títulos,
  observação, ação de verificação e confiança. Regras desconhecidas
  recebem explicação neutra, jamais texto externo sem qualificação.
- Avisos contextualizados: dado não sequencial, gasto zero com
  compra atribuída, métricas ausentes, compra ausente, atribuição desconhecida.
- Detalhamento separado por conjunto e por anúncio.
- Estilos responsivos e tabela com rolagem horizontal em celular,
  sem depender de gráficos/JavaScript client-side.

## Interpretação

O resultado é **consultivo**. Não se promete monitoramento de Pixel/CAPI,
medição de visitantes únicos, atribuição causal, vinculação pedido→campanha
ou conciliação financeira. Quando não há findings, isso NÃO significa
que o desempenho foi aprovado ou que esteja ótimo.

## Testes automatizados

`frontend/tests/funnel-diagnostics.test.mjs` verifica:
1. taxas de contagem observadas abaixo de 100%;
2. relação 1.100% e outras relações não sequenciais ocultadas;
3. zero, null e paginação incompleta separados;
4. conversões tardias com gasto zero sem CPA de R$ 0;
5. estados provisório, atribuição desconhecida e indisponibilidade;
6. tradução em português de findings novos ou desconhecidos.

CI executa `npm test`, `npm run build`, JUnit/Maven e Docker Compose smoke.

## Homologação manual

Após merge manual e atualização local:

```bash
git checkout main
git pull --ff-only origin main
docker compose up -d --build --force-recreate web
```

Abrir e validar (sem expor senha/tokens):

- `http://localhost:13000/interpretar?campaignId=52596120828588&since=2026-10-17&until=2026-10-23`
  — janela com gasto e 3 compras atribuídas;
- `http://localhost:13000/interpretar?campaignId=52596120828588&since=2026-10-18&until=2026-10-25`
  — gasto zero, 2 compras atribuídas, 11 carrinhos e 1 visita;
  **não** exibir 1.100% ou CPA de R$ 0;
- campanha ativa `52597606088988` numa janela com evidência de
  `Purchase` ausente: mostrar `Não informado`, não zero.

Confirme layout mobile, distinção dia atual/histórico, e o fato de
que um erro numa das duas APIs não oculta a outra.

Escopo exclusivo de frontend, sem writes de campanha, banco, webhooks,
sincronização, audiencias, fornecedores de pagamento ou custos.
