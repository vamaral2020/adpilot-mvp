# AdPilot — PR 001 (Meta Ads somente leitura)

AdPilot: Next.js 16 + Java 21/Spring Boot 4.1 + PostgreSQL 17. Uso local por enquanto.

## Como iniciar
1. Copie o arquivo de exemplo: `cp .env.example .env`.
2. Troque DB_PASSWORD e ADPILOT_ADMIN_PASSWORD (mínimo 12 caracteres).
3. Configure opcionalmente META_AD_ACCOUNT_ID (somente dígitos), META_ACCESS_TOKEN e META_GRAPH_VERSION.
4. Execute `docker compose up --build -d`, seguido por `docker compose ps`.
5. Abra http://localhost:13000; o navegador solicitará usuário e senha do `.env`.

A API está em http://localhost:18080, e o health em http://localhost:18080/actuator/health.
As portas ficam vinculadas a **127.0.0.1**, não publique em servidor externo.
O token Meta nunca deve ser incluído no Git, em argumentos de linha de comando ou em capturas de tela.

## Testar a autenticação local
```bash
curl -i http://localhost:18080/api/v1/meta/status
# esperado: HTTP 401

# Entrar com usuário/senha sem imprimí-los no histórico:
curl -u "$ADPILOT_ADMIN_USERNAME" http://localhost:18080/api/v1/meta/status
```

Se usar variáveis do arquivo `.env`, carregue-as no seu shell ou substitua o usuário interativamente.
Nunca inclua a senha em argumentos visíveis do shell.

## Testar conexão Meta (somente leitura)
```bash
curl -u admin http://localhost:18080/api/v1/meta/status
curl -u admin http://localhost:18080/api/v1/meta/account
curl -u admin 'http://localhost:18080/api/v1/meta/campaigns?limit=25'
curl -u admin 'http://localhost:18080/api/v1/meta/insights?since=2026-10-01&until=2026-10-15'
```
`curl -u admin` solicita a senha via terminal. Ajuste o nome do usuário conforme `.env`.
O status CONFIGURED_UNVERIFIED significa que as variáveis estão presentes, **não** que a Meta aceitou o token.
Somente a consulta de conta ou campanhas prova conectividade operacional naquele momento.

## Resumo de Insights para o dashboard (PR 004)

O endpoint paginado existente continua disponível, sem alterar o contrato:
`GET /api/v1/meta/insights?since=2026-10-12&until=2026-10-18&limit=25`.

O novo endpoint agrega automaticamente todas as páginas retornadas pela Meta, até
um limite seguro de 20 páginas (100 linhas por página, no máximo 2.000 registros):

```bash
curl -u admin 'http://localhost:18080/api/v1/meta/insights/summary?since=2026-10-12&until=2026-10-18'
```

Parâmetros opcionais: `pageSize` (1 a 100, padrão 100) e `maxPages`
(1 a 20, padrão 20). A resposta contém `totals`, `campaigns`,
`pagesFetched`, `recordsFetched`, `complete` e `after`.

- `complete=true`: todas as páginas disponíveis foram consumidas.
- `complete=false`: o limite de páginas foi atingido; **os totais são parciais**
  e `after` indica que existem mais resultados. Não exibir como totais definitivos.
- `spend` e `purchaseValue` estão na moeda da conta Meta;
  `ctr` é percentual, `cpc` e `roas` podem ser `null` sem denominador.
- `purchases` e `purchaseValue` vêm da atribuição Meta. Para evitar duplicação,
  o cálculo privilegia a ação `purchase` e usa categorias alternativas apenas
  quando ela está ausente. Isso não equivale a vendas confirmadas na loja.
- O backend segue apenas o cursor `after`, não a URL `paging.next` da Meta;
  a URL pode conter dados sensíveis. Não há gravação em banco nem alteração de anúncios.

## Dashboard visual (PR 005)

O frontend em [http://localhost:13000](http://localhost:13000) usa consultas
**somente no servidor Next.js** aos endpoints `/api/v1/meta/account`,
`/campaigns` e `/insights/summary`. Token da Meta e credenciais Basic
permanecem no Docker/servidor; não são incluídos no JavaScript do navegador.

- Filtros de **7 dias / 30 dias** (dias completos em UTC) e intervalo manual
  por data, até 90 dias. A Meta reporta os eventos no fuso da própria conta.
- Cards de investimento, receita atribuída, ROAS, compras, impressões,
  cliques, CTR e CPC médio, além de comparativo gráfico e tabela de campanhas.
- Dinheiro formatado com a moeda retornada por `/meta/account`.
- Só considera conclusivo um relatório com `complete=true`.
  Caso `complete=false`, exibe um aviso explícito de totais parciais.
- Falhas de API ou de datas exibem erros, sem inventar valores ou usar dados demo.
- Compras e receita vêm da atribuição Meta; **não são pedidos confirmados**
  nem lucro líquido. Integração com a Nuvemshop continua futura.

Para executar testes do frontend localmente:

```bash
cd frontend
npm install
npm test
npm run build
```

## Escopo e segurança
- HTTP Basic com usuário/senha de ambiente no backend e na borda do Next.js (proxy). Só para localhost.
- Meta Graph API v26.0 por padrão, configurável via `META_GRAPH_VERSION`.
- Endpoints de leitura: `/api/v1/meta/status`, `/account`, `/campaigns`, `/insights`.
- Páginas com até 100 resultados, usando cursor `after` sem retornar `paging.next` (pode conter credenciais).
- Períodos de insights restritos a 90 dias por requisição.
- Tokens nunca persistidos no PostgreSQL. Nenhum endpoint modifica anúncios.
- Requer permissão `ads_read` e acesso à conta indicada.

**Não implantar na internet ainda:** Basic exige HTTPS fora de localhost. A futura versão SaaS deverá adotar sessões/OIDC, acesso por workspace, gestão de segredos e autorização mais granular.
