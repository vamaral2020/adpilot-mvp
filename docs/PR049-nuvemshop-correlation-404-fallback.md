# PR049 — Fallback controlado para 404 na listagem Nuvemshop

## Evidência observada

Após o merge da PR048, o diagnóstico real reportou:
- HTTP AdPilot: 502
- code: NUVEMSHOP_UPSTREAM_ERROR
- upstream_http_status: 404
- message: Nao foi possivel consultar orders na Nuvemshop.

PR047 havia conciliado a paginação da mesma fonte e retornado 111 pedidos.
PR048 ampliou a projeção da listagem com:
subtotal, discount, discount_gateway, shipping_cost_customer.

O HTTP 404 por si só NÃO demonstra que esses campos são inválidos; pode
significar endpoint indisponível, falha transitória, roteamento ou política
específica da API. O aplicativo não lê nem divulga o corpo de erro remoto.

## Alteração

Mantém o mesmo endpoint autenticado e protegido por duas flags:
POST /api/v1/integrations/stone/nuvemshop-correlation

- Na **primeira página** da execução, tenta a projeção ampliada da PR048.
- Exclusivamente se a API Nuvemshop retornar upstream HTTP 404,
  executa **uma** tentativa com a seleção original da PR047.
- Se a projeção antiga funcionar, todas as páginas seguintes usam
  SOMENTE a projeção antiga — nenhuma mistura de campos entre páginas.
- Se a projeção antiga também falhar, devolve o erro sanitizado; não
  emite relatório vazio ou números falsamente reconciliados.
- Outros status (400, 401, 403, 429, 500 etc.) **não** iniciam fallback.
- Não repete o caminho expandido dentro da mesma execução após o 404.
- Não usa cache global de escolha de projeção nem altera conexões OAuth.

### Campos novos no JSON (adições à PR048)

- investigation.nuvemshopReadMode:
  - EXPANDED: listagem consultada com campos adicionais.
  - BASELINE_AFTER_EXPANDED_404: Nuvemshop rejeitou a seleção expandida
    com 404 e a seleção antiga respondeu.

Se foi necessário fallback, warnings inclui:
- NUVEMSHOP_EXPANDED_ORDER_FIELDS_RETURNED_404_BASELINE_SUCCEEDED
- NUVEMSHOP_FINANCIAL_COMPONENTS_NOT_REQUESTED_AFTER_FALLBACK

Os componentes adicionais ficam null/indisponíveis, nunca zero.
Isso implica que métricas aritméticas das diferenças podem ser zero
por ausência de dados, sem significar que nenhuma causa financeira existe.

## Segurança e comportamento

- Não altera domínio, path, ID da loja, token OAuth ou protocolo.
- Requisições upstream continuam GET e paginadas, com no máximo 10
  páginas x 50 itens por coleção. No fallback há até UM GET Nuvemshop
  adicional na primeira página: limite máximo 31 GETs por execução.
- Não segue URLs de paginação externas; nenhuma leitura de PII;
  nenhum ID, código bruto, timestamp ou montante por pedido é retornado.
- Sem writes, migrations, sincronização ou alterações no dashboard.
- Flags de verificação permanecem sempre false:
  financiallyVerified, paymentSettlementVerified,
  ordersMatchedToNuvemshop, ordersMatchedToCampaigns.
- A confirmação em CI usa respostas simuladas, não credenciais reais.

## Testes

- Expanded 404 + baseline 200: relatório sem inventar componentes.
- Segunda página usa baseline sem consultar novamente a versão expandida.
- 400, 401, 403, 429, 500 propagam erro sem fallback.
- Expanded 404 + baseline 404 propagam falha, sem produzir relatório.
- Testes anteriores de referências exatas, colisões, valores e timestamps
  permanecem intactos.

## Teste local após CI e merge manual

Atualizar main:
  git checkout main
  git pull --ff-only origin main

Habilitar temporariamente no .env:
  ADPILOT_STONE_DIAGNOSTICS_ENABLED=true
  ADPILOT_ORDER_LIST_DIAGNOSTICS_ENABLED=true

Recriar backend:
  docker compose up -d --build --force-recreate api

Testar:
  curl -fsS -u admin -X POST \
    'http://localhost:18080/api/v1/integrations/stone/nuvemshop-correlation?pageSize=50&maxPages=10' \
    | jq .

Verificar investigation.nuvemshopReadMode e warnings.
Se ainda houver 502, usar curl sem -f e sem jq para obter apenas o
código de erro sanitizado; não compartilhar chaves nem logs completos.

Ao terminar, desabilitar as flags e recriar API.

## Próxima investigação

Caso BASELINE_AFTER_EXPANDED_404 funcione, há evidência de falha
associada ao conjunto ampliado de parâmetros, mas não de qual campo.
Uma futura investigação poderá consultar variações controladas do
parâmetro fields sem exibir conteúdo upstream, para isolar campo.
Não remover campos ou atribuir origem financeira sem comprovação.
