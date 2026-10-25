# PR054 — Monitoramento de hoje com dados parciais no Intérprete Meta

## Motivação

A PR053 restringia o frontend de `/interpretar` a datas até ontem,
mesmo que o backend já aceitasse até hoje. Isso impedia acompanhar
uma campanha em andamento sem motivo técnico na API Graph.

## Comportamento

- O intervalo de 1–31 dias (referência UTC) pode terminar **hoje**.
- Datas futuras, datas impossíveis e intervalos maiores que 31 dias
  continuam rejeitados antes de consultar Insights.
- `until == hoje UTC`: resposta do endpoint de diagnóstico inclui:
  - `analysisMode: "CURRENT_DAY_MONITORING"`
  - `partialCurrentDay: true`
  - `warnings` com `PARTIAL_CURRENT_DAY` e
    `INTRADAY_METRICS_ARE_PROVISIONAL_NO_BUDGET_DECISIONS`.
- Intervalos anteriores incluem `analysisMode: "HISTORICAL_CONTEXT"`
  e `partialCurrentDay: false`.
- Na página `/interpretar`, um aviso destaca que os números de hoje
  são provisórios, têm atraso de atribuição e **não autorizam recomendações
  conclusivas de pausa, escala ou redistribuição de orçamento**.
- Dados históricos também podem sofrer revisão da atribuição Meta.
- `complete` permanece **exclusivamente** o indicador de paginação;
  `complete=true` NÃO significa dados finais nem vendas confirmadas.
- Termos "monitoramento" não implicam transmissão em tempo real:
  os valores vêm das consultas GET à Meta feitas quando o usuário
  solicita a análise; nenhuma atualização automática foi adicionada.

## Fuso e limitações

- A janela de referência aceita pelo intérprete usa datas UTC,
  como na PR053; a Meta pode reportar usando o fuso horário da conta.
- A presença de hoje na janela gera marcação conservadora de
  parcialidade. Para auditoria por fuso de conta precisaremos coletar
  `timezone_name` e padronizar datas numa futura alteração.
- Nenhuma alteração no motor de recomendações, histórico ou coleta
  de dados Nuvemshop/Pagar.me; nenhum resultado atribuído pela Meta
  equivale a faturamento verificado, lucro ou venda incremental.
- Não inclui ajuste de anúncios, audiências, orçamentos ou lances.

## Testes

- Frontend: datas de hoje, ontem, futuras e inválidas, 31/32 dias,
  virada UTC e classificação de modo.
- Backend: consulta de hoje mesmo com paginação concluída marca
  `partialCurrentDay=true`; histórico não é assim marcado;
  amanhã continua rejeitado.
- CI: testes backend, testes/build Next.js e smoke Docker Compose.

## Homologação após merge manual

```bash
git checkout main
git pull --ff-only origin main
docker compose up -d --build --force-recreate api web
```

Abra `http://localhost:13000/interpretar`; escolha uma campanha com
`until` na data atual. A tela deve mostrar `MONITORAMENTO DE HOJE —
RESULTADOS PARCIAIS`. Compare com o mesmo filtro até ontem.

Ou consulte a API local autenticada:

```bash
curl -fsS -u admin \
  'http://localhost:18080/api/v1/meta/campaigns/52597606088988/delivery-diagnostics?since=2026-10-18&until=2026-10-25' \
  | jq '{analysisMode,partialCurrentDay,complete,warnings}'
```

O exemplo usa 25/10/2026 apenas para homologação nessa data;
após 25/10 utilize a data corrente real na URL.
