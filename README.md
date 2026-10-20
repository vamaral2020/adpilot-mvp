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

## Integracao Nuvemshop (PR 006 — somente leitura)

A PR 006 implementa **OAuth Authorization Code** com permissões
`read_products` e `read_orders` cadastradas no Portal de Parceiros.
Token associado à loja é salvo **criptografado com AES-256-GCM** no PostgreSQL;
o código de autorização não é armazenado. O parâmetro `state` tem 256 bits
aleatórios, é persistido somente como hash SHA-256 e deve ser consumido uma
única vez em até 15 minutos para permitir login/consentimento (o codigo OAuth emitido pela Nuvemshop expira separadamente apos 5 minutos). A conexão atual é limitada ao workspace pessoal
(uma loja); isolamento multi-tenant será realizado antes de uma implantação SaaS.

### Configuração

1. Criar um aplicativo no Portal de Parceiros Nuvemshop com **somente**
   `read_products` e `read_orders`. Não habilitar escopos de escrita.
2. Informar uma URL de redirecionamento HTTPS sob seu controle apontando
   para `/api/v1/nuvemshop/oauth/callback`. Um localhost isolado não pode
   receber o redirecionamento vindo do navegador em outro dispositivo; para
   desenvolvimento, usar um túnel HTTPS restrito e temporário e registrar
   a mesma URL no Portal de Parceiros e em `NUVEMSHOP_REDIRECT_URI`.
   O túnel não deve ficar exposto continuamente.
3. Preencher em `.env` os campos `NUVEMSHOP_CLIENT_ID`,
   `NUVEMSHOP_CLIENT_SECRET`, `NUVEMSHOP_REDIRECT_URI`,
   `NUVEMSHOP_USER_AGENT` (formato `AdPilot (contato@seu-dominio.com)`)
   e `NUVEMSHOP_ENCRYPTION_KEY` (gerar com `openssl rand -base64 32`).
   **Nunca compartilhar, imprimir ou commitar** essas credenciais.
   Faça backup seguro da chave: sem ela, os tokens armazenados não podem
   ser descriptografados.
4. Executar `docker compose up -d --build api`. Os endpoints protegidos
   exigem a autenticação Basic atual do AdPilot.

Para OAuth, o endpoint oficial brasileiro usa `https://www.nuvemshop.com.br`; não confunda com a URL do callback, que continua sendo o domínio temporário do Cloudflare. Caso `NUVEMSHOP_OAUTH_BASE` esteja definido no `.env`, seu valor sobrescreve o padrão; utilize `https://www.nuvemshop.com.br`. URLs OAuth oficiais documentadas: `https://www.nuvemshop.com.br` e `https://www.tiendanube.com`.

### Token OAuth: dois endpoints distintos

O link de autorizacao no navegador permanece em
`NUVEMSHOP_OAUTH_BASE=https://www.nuvemshop.com.br`.
A troca servidor-servidor usa **outro endereco**, documentado no guia de
autenticacao da Nuvemshop:
`NUVEMSHOP_TOKEN_URL=https://www.tiendanube.com/apps/authorize/token`.
O AdPilot envia um POST JSON com `Content-Type: application/json`,
`Accept: application/json` e `User-Agent`, sem colocar credenciais na URL.

Esse endpoint tem allowlist de hosts oficiais e nao deve apontar para o
Cloudflare Tunnel nem para o dominio de callback. Em testes isolados,
endpoints HTTP de loopback sao permitidos. Se o backend receber HTTP 200
com `text/html`, ele nao interpreta o HTML nem tenta reutilizar o codigo
OAuth; retorna erro seguro. O campo `cf-mitigated: challenge`, quando
presente, indica uma pagina de desafio Cloudflare, nao um token valido.

### Conectar loja

```bash
curl -u admin http://localhost:18080/api/v1/nuvemshop/status
curl -u admin http://localhost:18080/api/v1/nuvemshop/oauth/start
```

Alternativamente, após configurar o aplicativo, acesse
`http://localhost:13000/#integracoes` e clique em **Conectar minha loja**.
Esse botão usa uma rota protegida do Next.js (com verificação de Origin)
e redireciona diretamente para a Nuvemshop. A tela mostra o estado da conexão.

O segundo endpoint retorna `authorizationUrl` (contém o state anti-CSRF,
não um token). **Não divulgue esse link**; abra-o no seu navegador no mesmo
computador. Depois de autorizar a instalação, a Nuvemshop chama o callback
e o AdPilot redireciona ao dashboard local. Após isso, valide:

```bash
curl -u admin http://localhost:18080/api/v1/nuvemshop/status
curl -u admin 'http://localhost:18080/api/v1/nuvemshop/products?page=1&perPage=30'
curl -u admin 'http://localhost:18080/api/v1/nuvemshop/orders?page=1&perPage=30'
```

As listas são paginadas usando `page` (1 a 100) e `perPage` (1 a 100);
a resposta contém `hasNext` e `totalCount` quando informado pela Nuvemshop.
**Não** propagamos PII de clientes (nome, endereço, e-mail, telefone),
URLs de API nem tokens; os campos retornados são somente dados comerciais,
produtos, variantes e itens dos pedidos. Pedidos não são necessariamente
pagos — use `payment_status=paid` na análise econômica futura.
A Nuvemshop limita consultas de pedidos a 10 mil registros por pesquisa;
a sincronização incremental será tratada na próxima PR.

**Escopo desta PR:** conexão e leitura; sem gravação de produtos,
pedidos ou estoque na Nuvemshop, sem sincronização automática e sem
disponibilização SaaS pública.

## Escopo e segurança
- HTTP Basic com usuário/senha de ambiente no backend e na borda do Next.js (proxy). Só para localhost.
- Meta Graph API v26.0 por padrão, configurável via `META_GRAPH_VERSION`.
- Endpoints de leitura: `/api/v1/meta/status`, `/account`, `/campaigns`, `/insights`.
- Páginas com até 100 resultados, usando cursor `after` sem retornar `paging.next` (pode conter credenciais).
- Períodos de insights restritos a 90 dias por requisição.
- Tokens nunca persistidos no PostgreSQL. Nenhum endpoint modifica anúncios.
- Requer permissão `ads_read` e acesso à conta indicada.

**Não implantar na internet ainda:** Basic exige HTTPS fora de localhost. A futura versão SaaS deverá adotar sessões/OIDC, acesso por workspace, gestão de segredos e autorização mais granular.

## Resumo comercial — PR 008

O endpoint somente leitura abaixo consolida **pedidos pagos da Nuvemshop** e
**investimento/ROAS atribuído da Meta Ads**, sem conciliar pedidos a campanhas:

```bash
curl -u admin 'http://localhost:18080/api/v1/commerce/summary?since=2026-10-17&until=2026-10-19'
```

- `since` e `until`: datas inclusivas, intervalo máximo de 90 dias; validação
  segue o filtro de Insights da Meta.
- `pageSize`: 1–100 (padrão 100); `maxPages`: 1–100 (padrão 20);
  API da Nuvemshop impõe limite de 10.000 pedidos por pesquisa.
- `paidOrders`: pedidos únicos com `payment_status=paid` cujo `paid_at`
  pertence ao período em **UTC**. Pedidos pendentes não são receita confirmada.
- `paidRevenue`: soma de `total` dos pedidos pagos da loja (inclui os
  componentes já refletidos no total do pedido e **não equivale a lucro**).
  `averageTicket`: `paidRevenue / paidOrders` (nulo se não há pagos).
- `daily`: série de receita e quantidade por dia UTC de `paid_at`.
- `mediaSpend`: gasto retornado pelos Insights consolidados da Meta;
  `metaAttributedRevenue` e `metaRoas`: **atribuição da Meta**, não
  pagamentos efetivos reconciliados.
- `mer`: `paidRevenue / mediaSpend` da mesma janela. Mede a relação entre
  o **faturamento pago total da loja** e investimento em mídia, não a atribuição
  causal das vendas aos anúncios. É nulo se o investimento for zero, as moedas
  diferirem ou os dados das fontes estiverem incompletos.
- `ordersComplete`, `metaComplete`, `complete`: indicadores explícitos
  de integridade. Se a paginação da loja não terminar, `paidOrders`,
  `paidRevenue`, `averageTicket` e `daily` são omitidos/nulos em vez de
  apresentar totais parciais como definitivos. Se a Meta for parcial,
  `mediaSpend`, `metaAttributedRevenue` e `metaRoas` ficam nulos.
- `warnings`: códigos de cautela `ORDERS_INCOMPLETE`, `META_INCOMPLETE`,
  `CURRENCY_MISMATCH`, `CURRENCY_NOT_ESTABLISHED` e
  `REPORTING_TIMEZONE_MAY_DIFFER` (os Insights Meta usam o dia da conta,
  enquanto os pagamentos são agregados em UTC).

O endpoint é protegido por HTTP Basic, não emite PII de clientes e não altera
pedidos ou produtos. A implementação consulta as fontes sob demanda, sem
armazenar dados comerciais adicionais e sem migrations. Seu custo cresce com
a quantidade de páginas; consultas históricas extensas e sincronização
incremental com filtros server-side são trabalho futuro. Pedidos criados/
atualizados durante a paginação podem mudar o conjunto de resultados; neste
MVP, IDs duplicados são ignorados e inconsistências com o total informado
sinalizam dados incompletos, mas não há snapshot transacional externo.

**Importante:** a consulta de MER faz uma agregação adicional de Insights e
de conta Meta além da visão de campanhas do dashboard. Em volumes maiores,
será necessário compartilhar ou manter cache seguro de dados com limites de
frescura definidos. O fuso da loja, devoluções, cancelamentos posteriores,
taxas e CMV ainda exigirão um modelo financeiro mais refinado.


## Tratamento de pedidos pagos sem paid_at — PR 009

Alguns pedidos retornados pela API podem estar com `payment_status=paid`,
mas `paid_at` nulo, vazio ou malformado. Não é correto assumir `created_at`
como data de pagamento, pois criação e pagamento são eventos distintos.
O endpoint `/api/v1/commerce/summary` agora termina a paginação normalmente
e expõe:
- `ordersPagesComplete`: se a paginação terminou integralmente;
- `paidOrdersWithoutDate`: quantidade de pedidos pagos sem data confiável
  (sem IDs, dados pessoais nem valores desses pedidos);
- `datedPaidOrders` e `datedPaidRevenue`: somente pedidos pagos com data
  válida dentro da janela, disponibilizados apenas após paginação concluída.
  São **mínimos identificáveis**, não valores finais quando existem pedidos
  sem data conhecida.

Se houver pedidos pagos com `paid_at` desconhecido, retorna
`ordersComplete=false` e `PAID_AT_MISSING_OR_INVALID` em `warnings`, com
`paidOrders`, `paidRevenue`, `averageTicket`, `mer` e `daily` nulos/vazios.
A API devolve HTTP 200 com indicação de dados incompletos, sem fabricar
datas nem transformar receita potencial em resultado definitivo. A Meta
continua apresentando seu próprio gasto e ROAS atribuído separadamente.
A investigação da origem dos `paid_at` ausentes e eventual reconciliação
de transações ficam para uma etapa específica.

## Auditoria de pagamentos Nuvemshop — PR 010

A PR 009 revelou pagamentos com `payment_status=paid` mas `paid_at`
ausente ou inválido. A auditoria **somente leitura e sem PII** permite
investigar o padrão sem criar datas falsas nem habilitar permissões de escrita.

```bash
# Curl pede a senha local de forma interativa; não inclua a senha na URL.
curl -sS -u admin 'http://localhost:18080/api/v1/nuvemshop/payments/audit'
```

Parâmetros: `pageSize` (1 a 100, padrão 100), `maxPages` (1 a 100,
padrão 20). O endpoint varre todos os pedidos disponibilizados pela listagem
dentro desses limites — **não é filtrado por `since/until` do dashboard**.
Os contadores `complete`, `pagesFetched`, `recordsFetched`,
`uniqueOrders`, `upstreamTotalCount` e `duplicateRecords`
expõem a integridade da leitura. Sem paginação completa, as contagens
referem-se apenas à amostra consultada.

O diagnóstico exibe **apenas contagens** agregadas:
- `paidOrders`, `paidWithValidDate`, `paidWithoutDate` e
  `paidWithInvalidDate`: cobertura de `paid_at` dos pedidos pagos.
- `paymentStatuses`: distribuição dos status de pagamento (todos os pedidos).
- `undatedByCreationMonth`: mês de criação (UTC), como referência de época,
  **nunca** como data do pagamento.
- `undatedByGateway`, `undatedByStorefront`,
  `undatedByAppAssociation`, `undatedByOrderStatus`: categorias
  de origem para *investigação*, não causa provada.
- `datedPaymentsByMonth`: mês UTC de pagamentos com `paid_at` válido.
- `warnings`: qualidade do dado e limitações de integridade.

O backend solicita somente `id,payment_status,paid_at,created_at,updated_at,
status,shipping_status,gateway,storefront,app_id`, realiza deduplicação dos IDs
em memória e **nunca os envia ao cliente**. A resposta não inclui IDs, nomes,
endereços, produtos, valores, detalhes de transações ou dados de pagamento.
Strings desconhecidas em status, origem ou gateway são agrupadas em `other`
sem ecoar conteúdo de terceiros. O endpoint exige HTTP Basic e cabeçalho
`Cache-Control: no-store`. Não há migration nem armazenamento dos pedidos.

### Cautelas na interpretação

- `paid_at` e `created_at` têm significados distintos. O segundo não pode
  ser usado para recuperar o primeiro.
- `gateway=offline`, `storefront=api` e `app_id` preenchido podem
  sugerir caminhos de investigação, mas **não demonstram** importação,
  confirmação manual ou origem real do pagamento.
- A documentação oficial lista transações e eventos, mas a API de transações
  é reservada a aplicativos de pagamento. Portanto, com os escopos atuais
  `read_products,read_orders`, **não presumimos acesso a histórico transacional**.
- A API externa não oferece um snapshot consistente entre páginas. Contagens
  podem variar enquanto novos pedidos são criados ou alterados.
- O MER da PR 009 permanece `null` enquanto a data dos pedidos pagos não
  permitir fechar com segurança o intervalo financeiro.

Referências do provedor:
https://nuvemshop.dev/docs-api/resources/2025-03/order
https://tiendanube.github.io/api-documentation/v1/resources/transaction


## Diagnóstico temporário de `paid_at` — PR 011

Motivação: a listagem do AdPilot informa 45 pedidos com
`payment_status=paid` mas `paid_at=null`. A exportação administrativa
aponta datas para a maioria desses pedidos. Precisamos determinar **em qual
resposta o valor difere** antes de alterar o MER ou assumir datas de criação.

### Ativação somente local

O diagnóstico fica **desabilitado por padrão**. Adicione ao `.env` local:

```dotenv
ADPILOT_NUVEMSHOP_DIAGNOSTICS_ENABLED=true
```

A opção é passada pelo Docker Compose apenas para o container `api`.
Reconstrua/recrie somente a API após atualizar a branch:

```bash
docker compose up -d --build --force-recreate api
```

O endpoint exige HTTP Basic e não funciona com a opção desabilitada
(retorna HTTP 404).

### Comparar os pedidos sem expor PII

No Mac, rode (o curl pede a senha Basic, sem registrá-la no histórico):

```bash
umask 077
curl -fsS -u admin 'http://localhost:18080/api/v1/nuvemshop/diagnostics/orders/158' \
  > "$HOME/Downloads/adpilot-diagnostico-pedido-158.json"
python3 -m json.tool "$HOME/Downloads/adpilot-diagnostico-pedido-158.json"
```

Repita, se necessário, para `/orders/157` e `/orders/119` e salve
em arquivos separados. A consulta identifica o ID interno da API pela
**listagem paginada existente** e executa duas leituras individuais:

- `list`: valores recebidos no `GET /orders?fields=...` que o AdPilot
  atualmente usa na consolidação;
- `individualFiltered`: `GET /orders/{id}?fields=id,number,status,payment_status,created_at,paid_at,updated_at`;
- `individualFull`: `GET /orders/{id}` **sem** filtro `fields`;
- Cada campo tem `present` e `type` (`missing`, `null`, `string`,
  `number` ou `unexpected`) e `value` seguro, permitindo distinguir
  ausente de nulo sem inventar datas.

A resposta completa do terceiro GET **pode conter dados pessoais e
segredos de pagamento**, portanto é mantida somente em memória. **Nunca
imprimimos o corpo bruto**. Somente os campos escalares permitidos são
serializados como uma linha `NUVEMSHOP_PAYMENT_DIAGNOSTIC {JSON}` no
stdout do backend, sem contatos, endereços, cartões, tokens ou listas de
produtos.

Para salvar o log JSON estruturado em um arquivo no Mac (com permissão
restrita), execute **depois** de consultar os pedidos:

```bash
umask 077
docker compose logs --no-color --since=15m api \
  | sed -n 's/^.*NUVEMSHOP_PAYMENT_DIAGNOSTIC //p' \
  > "$HOME/Downloads/adpilot-nuvemshop-payment-diagnostic.jsonl"
chmod 600 "$HOME/Downloads/adpilot-nuvemshop-payment-diagnostic.jsonl"
```

Os arquivos JSON e JSONL devem permanecer locais; não envie logs gerais
da aplicação ou respostas não filtradas. O código **não altera** pedidos,
OAuth, permissões nem cálculo comercial.

### Desativação obrigatória após a análise

Troque o valor do `.env` por:

```dotenv
ADPILOT_NUVEMSHOP_DIAGNOSTICS_ENABLED=false
```

E execute `docker compose up -d --force-recreate api`. Apague os logs
locais após concluir a conferência. **Este endpoint é temporário e não
deve ser habilitado em produção.**

Documentação Nuvemshop 2025-03:
https://nuvemshop.dev/docs-api/resources/2025-03/order
