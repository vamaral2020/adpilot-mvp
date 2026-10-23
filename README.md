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

## Vendas efetivadas — PR 012

A consolidação comercial considera somente **pedidos com**
`payment_status=paid` **e** `status != cancelled`. Cancelados ficam
fora de receita, quantidade de vendas e ticket médio, mesmo quando o
provedor mantém o status de pagamento como `paid`. Pedidos com
`payment_status=refunded`, `voided` ou `pending` também não entram por
não satisfazerem o critério `paid`.

**Recuperação de datas:** a PR 011 comprovou, para os pedidos #158 e #119,
que a listagem devolve `paid_at=null` mas o detalhe individual com filtro
de campos devolve o `paid_at` correto. O novo fluxo consulta
`GET /orders/{id}?fields=id,number,status,payment_status,paid_at,total,currency`
**apenas** para pedidos pagos não cancelados com data ausente ou inválida
na listagem. A data retornada é convertida para UTC e usada na janela
selecionada. Não se usa `created_at` como substituto. Só se adiciona
receita se ID, número do pedido (quando presente), status, moeda e total
coincidirem entre listagem e detalhe.

A recuperação fica limitada a **100 requisições individuais por resumo**.
`paidDatesRecovered`, `individualLookups`, `cancelledOrdersIgnored`,
`inconsistentDetails` e `paidOrdersWithoutDate` informam o resultado.
O endpoint não faz gravações na loja ou no banco de dados. Com paginação
incompleta, detalhes inconsistentes, data ainda ausente ou limite atingido,
`ordersComplete=false`, e receita/ticket/MER finais permanecem
indisponíveis para não estimar valores indevidos. O cliente propaga erros
upstream, inclusive 429 (rate-limit), em vez de retornar falsamente um
total completo.

A **auditoria da PR 010 é uma visão bruta de qualidade dos dados** de
todos os pedidos retornados pela Nuvemshop, **inclusive cancelados**;
seus contadores de `paid_at` na listagem não representam o universo de
vendas efetivadas e não são alterados pela recuperação da PR 012.

Como o novo resumo executa consultas adicionais na Nuvemshop, a chamada
da seção comercial do frontend tem timeout de até 120 segundos no
ambiente local. Em volumes maiores, será necessário melhorar cache/
limites da API antes de ampliar o uso. O MER é relação entre toda a
receita paga **não cancelada** na loja e o gasto na Meta; não constitui
ROAS atribuído nem lucro líquido. O tratamento de estornos tardios,
tarifas e ajustes de períodos será feito em evolução específica.

## PR 013 — Eficiência de mídia e comparação de fontes

O dashboard adiciona a seção **Eficiência do investimento** usando
exclusivamente a consolidação da Meta e a consolidação comercial de
pedidos **pagos e não cancelados** (PR 012). É um diagnóstico
somente leitura; não modifica campanhas nem pedidos.

A tabela de campanhas passa a exibir **% do gasto Meta**,
**receita atribuída Meta**, **custo por compra atribuída** e
**ROAS atribuído**, além de investimento, cliques, compras e status.
Custo por compra atribuída = investimento Meta / compras atribuídas
pela Meta (`null` sem compras). Não é custo de aquisição de cliente
(CAC) e não pode ser confundido com CPA baseado em pedidos conciliados.

A seção agrega **investimento nas campanhas que gastaram sem ter compra
atribuída pela Meta** (não significa que foram ineficientes), a
concentração do investimento na campanha com maior gasto, e o custo
médio da Meta por compra atribuída, sempre com período idêntico ao
filtro. Sem gasto, percentuais e razões não são fabricados.

O comparativo mostra **duas fontes independentes**:
- Nuvemshop: receita e número de pedidos `payment_status=paid` que
  não estejam com `status=cancelled`, filtrados pelo `paid_at` em UTC.
- Meta: receitas e compras atribuídas de Insights por campanhas,
  segundo as configurações de atribuição da conta.

**Não** calcula diferença entre essas receitas como
"faturamento não atribuído", **não** vincula individualmente pedidos
a campanhas, nem recomenda automaticamente aumentar ou cortar orçamento.
MER da loja = receita paga não cancelada / gasto na Meta; ROAS atribuído
Meta = receita atribuída / gasto na Meta. Nenhuma razão representa lucro.

A comparação entre fontes só aparece se ambos os relatórios estão
completos, os intervalos e as moedas coincidem, e as duas consultas
concordam nos totais de gasto e receita atribuída Meta. A análise
de campanhas requer paginação Meta completa, métricas finitas não
negativas, IDs distintos e somas coerentes com o agregado da conta;
caso contrário, os indicadores derivados são ocultados com explicação.

Não há novas APIs, migrations, escopos OAuth, permissões ou
credenciais. Implementação concentrada na apresentação e em funções
puras testadas por Node.

## PR 014 — Diagnóstico de atribuição e clareza na auditoria

**Objetivo:** antes de sugerir alterações de investimento, verificar as
taxonomias `Purchase` que sustentam as compras e receitas **atribuídas
pela Meta**, e esclarecer a diferença entre ausência de `paid_at` na
**listagem bruta** da Nuvemshop e data recuperada pelo resumo comercial.

### Endpoint Meta somente leitura

`GET /api/v1/meta/conversions/audit?since=2026-10-12&until=2026-10-18`

- Usa `MetaAdsClient.insights` com os mesmos campos e permissões
  `ads_read` existentes; paginação até 20 páginas de 100 linhas, janela
  de até 90 dias, sem gravar dados ou fazer alterações.
- Agrega separadamente as taxonomias `purchase`, `omni_purchase`,
  `offsite_conversion.fb_pixel_purchase`, `onsite_web_purchase`.
  **Não são números aditivos**: um único evento pode aparecer sob
  múltiplas taxonomias.
- Exibe contagens e valores por categoria, quantas linhas selecionaram
  cada categoria e a escolha efetiva do AdPilot:
  `purchase` > `omni_purchase` > `offsite_conversion.fb_pixel_purchase`
  > `onsite_web_purchase`. Contagem e valor são selecionados de maneira
  independente, como no consolidado existente.
- A auditoria e o resumo de Insights são consultas independentes:
  a comparação só confirma concordância se ambos tiverem paginação
  completa, mesmo período e totais coincidentes.
- Respostas parciais **não** exibem totais globais como definitivos.
  O endpoint contém somente agregados, sem detalhes de pessoas,
  URLs com token, dados de eventos ou identificadores de clientes.
- **Limitação explícita:** os Insights **não confirmam** se o Pixel está
  instalado corretamente, se a Conversions API entrega eventos, se
  `event_id` está correto ou se há deduplicação. Esses testes exigem
  Gerenciador de Eventos, acessos e dados apropriados, e não são
  inventados pelo AdPilot.

Exemplo local (Basic Auth requer senha solicitada pelo terminal):

```bash
curl -sS -u admin \
  'http://localhost:18080/api/v1/meta/conversions/audit?since=2026-10-12&until=2026-10-18'
```

### Auditoria Nuvemshop

A antiga mensagem "não é possível determinar a data de pagamento" foi
substituída por uma distinção explícita: o agregado `payments/audit`
mostra dados **brutos da listagem** (incluindo cancelados), enquanto
`commerce/summary` resolve datas de pedidos elegíveis por consulta
individual e descarta cancelados. Os contadores de fontes diferentes
são rotulados separadamente, sem assumir equivalência entre datas
ausentes na listagem e vendas finais.

### Revisão longitudinal futura

A revisão de uma nova campanha de maior duração foi registrada em
[GitHub Issue #14](https://github.com/vamaral2020/adpilot-mvp/issues/14),
em backlog até serem definidos campanha, orçamento e datas de início/
encerramento. A PR 014 **não** inicia, agenda ou altera campanhas.

## PR 015 — Base analítica própria e sincronização incremental (opt-in)

Esta entrega funda a primeira base analítica do AdPilot para sustentar o
motor de recomendações, mantendo a Meta Ads e a Nuvemshop **somente leitura**.
Os registros persistidos no PostgreSQL são exclusivamente projeções
comerciais e agregados publicitários, separados do OAuth e sem PII de
clientes, emails, telefones, endereços, IPs ou tokens.

### Banco: Flyway V3

- `analytics_sync_run` e `analytics_sync_state`: trilha da execução,
  status, erro categorizado e checkpoint por provedor/recurso/workspace.
- `analytics_order_snapshot`: estado mais recente do pedido, valor,
  moeda, status, `paid_at`, proveniência da data, `updated_at` e
  elegibilidade comercial (`paid` e não `cancelled`).
- `analytics_meta_insight_daily`: métricas diárias por conta/campanha
  na data **da conta Meta**, separadas da data UTC do pedido.
- `analytics_meta_campaign_snapshot`: nome, objetivo e status de campanha
  **observados** em cada dia de coleta, sem inferir status histórico exato.
- `analytics_audience_definition`, `analytics_recommendation` e
  `analytics_recommendation_evidence`: **estrutura de dados apenas**;
  a PR não classifica pessoas, gera recomendações nem cria públicos.

### Modo de operação

Por padrão `ADPILOT_ANALYTICS_SYNC_ENABLED=false`, sem agendamento ativo
e sem chamadas externas de batch. Para executar um teste **local e
supervisionado**, defina no `.env`:

```dotenv
ADPILOT_ANALYTICS_SYNC_ENABLED=true
ADPILOT_ANALYTICS_SYNC_CRON=0 5 */3 * * *
```

Recrie a API depois da configuração. O cron usa UTC e por padrão executa
a cada três horas, 5 minutos após o início da hora. A sincronização
também pode ser iniciada manualmente com Basic Auth:

```bash
curl -sS -u admin -X POST http://localhost:18080/api/v1/analytics/sync
curl -sS -u admin http://localhost:18080/api/v1/analytics/status
curl -sS -u admin http://localhost:18080/api/v1/analytics/runs
```

Não passe a senha na URL ou no histórico do terminal.
A resposta do POST informa `SUCCEEDED`, `FAILED` ou `SKIPPED`
separadamente por integração, sem despejar respostas brutas dos provedores.

### Estratégia de coleta e suas restrições

- Nuvemshop: filtro oficial `updated_at_min/max`; **primeiro
  backfill apenas dos últimos 30 dias**, depois sobreposição de 10
  minutos no checkpoint. O limite é 100 páginas × 100 pedidos por
  execução. Se ultrapassar, **a execução falha sem avançar o cursor**;
  intervalos grandes serão particionados em evolução posterior.
- Datas pagas ausentes na listagem: até 100 `GET /orders/{id}`
  adicionais para pedidos pagos não cancelados. Se o detalhe não
  resolver ou divergir, falha sem atualizar o cursor, em vez de
  fabricar pagamento pela data de criação.
- Meta: catálogo de campanhas atualizado a cada ciclo (até 20 páginas)
  com status/objetivo/nome observados. Não há escrita na Meta.
- Meta: janela móvel dos **30 últimos dias completos UTC**, usando
  `time_increment=1` (as datas de Insights seguem o calendário
  da conta de anúncios). Até 20 páginas × 100 linhas. Os registros
  dessa janela são substituídos atomicamente para contemplar ajustes
  posteriores de atribuição. A regra de seleção de eventos Purchase
  é exatamente a já usada no resumo existente; taxonomias
  correlacionadas não são somadas.
- Em cada fonte os dados completos, o checkpoint e a marca de sucesso
  são persistidos **na mesma transação**. Consultas HTTP ocorrem
  antes da transação. Uma falha registra código de erro genérico,
  sem nomes, payloads, URL de token ou PII.
- Não há garantia de snapshot remoto durante a paginação. Webhooks,
  backfill histórico por janelas menores, rate-limit/backoff,
  retenção, gestão de exclusão de dados e verificação de fuso da
  conta Meta exigirão outras etapas antes de uso SaaS.

### Segurança e limites para futuros públicos

**Não há ingestão de clientes, leads ou eventos identificáveis nesta
PR.** As tabelas de audiência e evidência permanecem sem dados até que
tenhamos consentimentos/bases legais, políticas de retenção, controles
de acesso, exclusão de titulares e a estratégia de integração com
audiências da Meta aprovada. A aprovação de uma audiência jamais é
aprovação para iniciar uma campanha ou alterar orçamento.

Documentação oficial dos filtros da Nuvemshop:
https://nuvemshop.dev/docs-api/resources/2025-03/order


## PR 016 — Histórico analítico local

O endpoint autenticado `GET /api/v1/analytics/history?since=YYYY-MM-DD&until=YYYY-MM-DD`
consulta exclusivamente o PostgreSQL do workspace local, sem chamar APIs da Meta
ou Nuvemshop. O período é inclusivo, limitado a 90 dias e não aceita futuro.
A nova tela `/historico` apresenta séries de gasto Meta e receita paga Nuvemshop,
com filtros de 7/30 dias ou datas manuais.

Apenas `eligible_paid_sale=true` entra nas receitas da loja: pagamento com
`paid_at` válido, sem cancelamento, com total e moeda. Não se utiliza
`created_at` como substituto para pagamento. Os eventos da Meta são agrupados
na data reportada pela conta Meta; a data dos pagamentos é UTC.

**Restrições conhecidas:** a tabela de pedidos contém o snapshot mais recente,
não um histórico completo de alterações de status. A sincronização inicial da
Nuvemshop considera atualizações em janela limitada e não prova cobertura de
todos os pagamentos efetuados no período. Ausência de linha diária pode
significar ausência de coleta, não zero atividade. Consequentemente
`complete=false` e `HISTORICAL_COVERAGE_NOT_VERIFIED` são deliberados até
existir controle de cobertura temporal demonstrável. A tela não apresenta
comparações financeiras como resultados conclusivos sem essa comprovação.

Verificação manual pós-deploy:

```bash
curl -u admin 'http://localhost:18080/api/v1/analytics/status'
curl -u admin 'http://localhost:18080/api/v1/analytics/runs'
curl -u admin 'http://localhost:18080/api/v1/analytics/history?since=2026-10-01&until=2026-10-20'
```

O endpoint permanece em HTTP Basic local e devolve `Cache-Control: no-store`.
Não há novos escopos OAuth, alteração de campanhas ou migration.


## Hotfix PR 016.1 — diagnóstico seguro da sincronização incremental Nuvemshop

A sincronização de pedidos agora confere os dois limites de `updated_at`
contra a janela enviada ao provedor, com 60 segundos de tolerância para
precisão de timestamp e pequenos desvios de relógio. Um pedido anterior ao
limite inferior causa `ORDER_BEFORE_LOWER_BOUND`; um pedido além do limite
superior causa `ORDER_AFTER_UPPER_BOUND`. Nenhuma das duas condições autoriza
a confirmação parcial de uma janela nem o avanço do checkpoint.

Falhas são classificadas por etapa, sem expor mensagens livres de exceção,
dados de clientes, resposta bruta, URL, token ou credenciais:

- `ORDER_API_HTTP_XXX`: rejeição HTTP da Nuvemshop (somente código de status).
- `ORDER_API_UNAVAILABLE`: falha de comunicação sem status HTTP disponível.
- `ORDER_BEFORE_LOWER_BOUND`/`ORDER_AFTER_UPPER_BOUND`: filtro temporal incompatível.
- `ORDER_PAGE_*`, `ORDER_DETAIL_*`, `ORDER_SOURCE_DATA_INVALID`:
  falhas reconhecidas de paginação ou validação.
- `ORDER_DB_WRITE_FAILED`/`ORDER_PERSIST_FAILED`: falha na persistência.
- `ORDER_COLLECTION_FAILED`: erro inesperado não identificado por allowlist.

O código seguro aparece no `POST /api/v1/analytics/sync`, em
`GET /api/v1/analytics/runs` e em uma linha de log
`ANALYTICS_ORDER_SYNC_FAILURE` contendo apenas `runId`, `stage` e `code`.
A coleta de Meta Ads continua independente. Snapshots já armazenados, inclusive
os pedidos anteriores à janela, **não são removidos** por este hotfix.

Validação operacional após deploy, com sincronização manual explicitamente
habilitada no ambiente local:

```bash
curl -sS -u admin 'http://localhost:18080/api/v1/analytics/runs' | jq
docker compose logs --no-color --since=10m api | grep 'ANALYTICS_ORDER_SYNC_FAILURE'
```

**Limitação:** o hotfix detecta quando o provedor ignora, interpreta
diferentemente ou descumpre os limites enviados; não presume a causa nem
reinterpreta os filtros silenciosamente. Se surgir
`ORDER_BEFORE_LOWER_BOUND`, investigar contrato e tráfego sem registrar
pedidos brutos, antes de corrigir a estratégia de busca. O backfill histórico
integral e a reconstituição de estados anteriores seguem fora do escopo.

## PR 017 — Recomendações consultivas Meta Ads

O motor analisa apenas os Insights diários de campanha da Meta Ads previamente persistidos. Não associa pedidos Nuvemshop nem diagnostica criativos, públicos ou conjuntos de anúncios. Os indicadores são atribuídos pela Meta, sujeitos a revisões posteriores.

### Interface e API

- Página: http://localhost:13000/recomendacoes (também acessível a partir do histórico).
- Prévia GET /api/v1/analytics/recommendations?since=2026-10-14&until=2026-10-20&targetRoas=3&targetCpa=50. Apenas leitura.
- POST /api/v1/analytics/recommendations/evaluations: JSON com since, until, targetRoas e targetCpa. Persiste somente no PostgreSQL local, nunca na Meta Ads.
- Metas de ROAS e CPA opcionais. Nenhum objetivo financeiro é inventado. Períodos de 1 a 90 dias, sem datas futuras. HTTP Basic e Cache-Control: no-store.

### Regras v1

- LOW_SAMPLE: menos de cinco compras atribuídas, menos de três dias registrados ou menos de sete dias selecionados. Apenas monitorar.
- SPEND_WITHOUT_PURCHASES: zero compras, pelo menos três dias e investimento >= 2 vezes o CPA máximo informado. Investigar, não pausar automaticamente.
- ROAS_BELOW_TARGET / CPA_ABOVE_TARGET: cinco ou mais compras, sete dias ou mais no período e três dias com registros; comparação com meta fornecida. Investigar.
- CTR_DECLINING / CPC_INCREASING: quatorze dias no período, cada metade com pelo menos dois dias registrados, 500 impressões e dez cliques. Detecta queda de CTR >= 30% ou aumento CPC >= 30%. Métricas usam cliques totais, não cliques no link.
- FAVORABLE_PERFORMANCE: dez ou mais compras, metas informadas e atendidas. Apenas monitorar possível oportunidade, nunca aumentar orçamento automaticamente.
- NO_META_INSIGHTS / INVALID_CAMPAIGN_DATA: dados ausentes, moedas divergentes ou dias duplicados, sem inventar valores zero nem converter moedas.
- CONFIGURE_GOALS: ausência de metas econômicas informadas.

### Integridade e auditoria

A cobertura histórica permanece não comprovada, mesmo após sincronização bem-sucedida. Assim, a confiança é sempre LOW ou INSUFFICIENT; prioridade indica atenção, não autorização de ação. Não existem recomendações executáveis de pausa, mudança de orçamento ou alteração de anúncios.

O POST explícito gera um evaluationId em analytics_recommendation_evaluation e salva recomendações DRAFT com regra/versão, campanha, justificativa e evidências numéricas nas tabelas analytics_recommendation e analytics_recommendation_evidence. A migration V4__recommendation_evaluations.sql preserva os dados anteriores. Cada execução é registrada separadamente.

Exemplo de preview (senha solicitada pelo terminal):

```bash
curl -sS -u admin 'http://localhost:18080/api/v1/analytics/recommendations?since=2026-10-14&until=2026-10-20&targetRoas=3&targetCpa=50' | jq
```

Exemplo de gravação local deliberada:

```bash
curl -sS -u admin -H 'Content-Type: application/json' --data '{"since":"2026-10-14","until":"2026-10-20","targetRoas":3,"targetCpa":50}' 'http://localhost:18080/api/v1/analytics/recommendations/evaluations' | jq
```

### PR 017.1 — Correção de metas decimais no formulário

Os campos ROAS mínimo e CPA máximo na página de recomendações aceitam vírgula ou ponto como separador decimal, sem separador de milhar (exemplos: `3,4`, `3.4`, `75,50`). O frontend valida até quatro casas decimais, exige valor positivo e respeita os tetos vigentes do backend. Antes de consultar a API, converte `3,4` em `3.4` para manter a precisão sem permitir interpretações ambíguas.

Corrigido o conflito anterior entre `min=0.0001` e `step=0.01`, que bloqueava valores como `3,4`. Entradas inválidas exibem erro claro em vez de apagar silenciosamente a meta e retornar `CONFIGURE_GOALS`. A interface mostra as metas efetivamente usadas no último resultado e informa que alterações requerem clicar em **Analisar campanhas**.

A explicação da regra `LOW_SAMPLE` agora exibe quantidades sem zeros decimais desnecessários e usa singular/plural em português, preservando atribuições fracionárias da Meta quando ocorrerem. O motor e suas limitações de cobertura, a API e a persistência permanecem inalterados. Adicionados testes unitários e smoke de API.

## PR 018 — Experiência comercial e recomendações em primeiro plano

A página inicial mantém os mesmos indicadores financeiros consultados diretamente dos Insights da Meta Ads, mas agora traz o painel **O que fazer agora?** imediatamente depois dos quatro KPIs. O painel lê a prévia **somente leitura** em `GET /api/v1/analytics/recommendations` para o mesmo período de datas, utilizando exclusivamente os snapshots Meta previamente armazenados. Não realiza sincronização, gravação, alteração de anúncios ou nova atribuição por pedido.

A orientação diferencia explicitamente os dados **históricos locais** dos KPIs consultados diretamente da Meta. O histórico pode estar desatualizado/incompleto: um erro ou ausência de linhas nunca se transforma em zero compras ou zero investimento. Recomendações por campanha têm prioridade visual sobre solicitações genéricas de configuração de metas; a falta dessas metas segue visível na página própria `/recomendacoes`.

### Navegação e hierarquia

- O menu principal passa a oferecer **Recomendações** (funcional), removendo o antigo módulo incorretamente rotulado 'Recomendações IA — Em breve'. Links para Histórico e Recomendações preservam o período selecionado no dashboard.
- Os quatro KPIs principais continuam com os mesmos cálculos e origem. A seção de orientação exibe, quando disponíveis, compras, ROAS e CPA **do histórico** de campanha, sem misturá-los com números ao vivo.
- A seção comercial da Nuvemshop aparece antes das informações técnicas. Eficiência comparada, eventos Meta e auditoria de pagamentos permanecem acessíveis dentro de **Diagnósticos e auditorias**, um componente `<details>` sem JavaScript (acessível por teclado).
- A tabela de campanhas torna-se um conjunto de cartões em telas estreitas, preservando campanha, status, investimento, compras atribuídas, receita atribuída e ROAS; os detalhes de custo, cliques e participação permanecem disponíveis na tabela em telas maiores.
- A versão de impressão prioriza KPIs, recomendações, campanhas e resultados comerciais, evitando imprimir colunas técnicas fora da largura da página.

### Limitações preservadas

O painel histórico nunca apresenta a ausência de registros como vendas iguais a zero; os status das campanhas continuam atuais e não retroativos. ROAS Meta não é lucro, MER da loja não é atribuição, e a Nuvemshop não dispõe de vínculo comprovado por pedido. Não há alterações em API Java, banco, migração, OAuth ou motor de regras.

Testes adicionados validam a escolha conservadora da recomendação em diferentes cenários, enquanto o smoke test do Compose verifica a renderização da homepage sem dados históricos e sem degradação dos endpoints existentes.

## PR 019 — Calculadora de CPA e ROAS antes da campanha

Rota autenticada do frontend: **`http://localhost:13000/calculadora`**, acessível pelo menu **Planejar campanha** e pela página de Recomendações. A ferramenta é **totalmente local ao navegador**: não chama APIs, não salva dados em banco/cookies/localStorage, não lê valores da Nuvemshop e **não cria nem modifica campanhas na Meta Ads**. Serve apenas para planejar metas econômicas a serem consultadas ou configuradas manualmente.

### Entradas e premissas

- Cálculo **por pedido com uma unidade do produto**. O preço de R$ 199 é exemplo preenchido; substitua pelos valores da campanha planejada.
- **Todos os campos são obrigatórios**: preço bruto de venda (maior que zero), custo da mercadoria, impostos (%), taxas de pagamento (% e tarifa fixa), frete subsidiado, embalagem, outros custos variáveis e valor mínimo que deseja preservar após anúncios. Insira **0** explicitamente quando uma despesa não existir ou não desejar preservar resultado depois da publicidade; campo vazio **não equivale** a zero.
- Resultado da venda antes da publicidade é **calculado automaticamente** como preço menos todos os custos variáveis declarados. É exibido assim que os campos de custos estiverem completos, mesmo que o valor a preservar depois dos anúncios ainda não tenha sido informado.
- O valor que deseja preservar **após anúncios** permanece um objetivo separado da margem calculada, porque reservar 100% da margem não deixa orçamento positivo para aquisição.
- Aceita `199,90` e `199.90`, **sem separador de milhar**, no máximo duas casas decimais. Valores não negativos, cada moeda até R$ 1 milhão e percentuais de 0% a 100%. Valor inválido não é silenciosamente convertido em zero.
- Taxas percentuais calculadas sobre o **preço bruto**, arredondadas individualmente a centavos; os resultados monetários são calculados em **centavos inteiros**, sem somas monetárias em ponto flutuante.

### Equações

- Custos variáveis = mercadoria + percentual de impostos + percentual/tarifa fixa de pagamentos + frete subsidiado + embalagem + outros.
- Margem de contribuição pré-anúncios = preço de venda − custos variáveis.
- **CPA de equilíbrio** = margem pré-anúncios (se positiva). **ROAS de equilíbrio** = preço de venda ÷ CPA de equilíbrio.
- **CPA-alvo** = margem pré-anúncios − resultado desejado após anúncios (deve ser **positivo**). **ROAS-alvo** = preço de venda ÷ CPA-alvo.
- ROAS sugerido é **arredondado para cima em duas casas**, para que o alvo exibido não fique abaixo do necessário. O CPA mantém centavos inteiros.
- Se a margem for zero/negativa, ou se o resultado desejado consumir toda a margem, a calculadora exibe **inviável** em vez de CPA zero/ROAS infinito.

### Uso na Meta Ads

A calculadora apresenta CPA-alvo e ROAS-alvo para **referência comercial e cópia**. Dependendo do objetivo e estratégia de lance, a Meta pode oferecer meta de custo por resultado ou ROAS mínimo; não há garantia de que as duas configurações possam ser aplicadas em conjunto. São metas econômicas, **não uma previsão** de resultados nem garantia de lucro. Não equivalem ao ROAS atribuído real da Meta sem confirmar o ticket e o produto comprado. Também não alocam automaticamente despesas fixas, devoluções ou impostos mais complexos.

### Escopo técnico

Somente frontend Next.js/React: `lib/product-targets.ts` contém funções puras; `app/calculadora` implementa a interface interativa; `tests/product-targets.test.mjs` cobre entradas brasileiras, precisão, limites, arredondamento e margens inviáveis. Não há migration, alteração no backend Java, OAuth, permissão de Meta/Nuvemshop nem alteração do motor de recomendações.


### PR 019.1 — Margem automática e custos obrigatórios

Para evitar metas superestimadas, a calculadora agora **não interpreta campos de
despesa em branco como zero**. Preço, custo da mercadoria, imposto, taxa percentual
e tarifa fixa de pagamento, frete subsidiado, embalagem, outros custos e resultado
mínimo a preservar após anúncios são obrigatórios. **Informe 0 explicitamente**
em cada despesa que não existir; o preço de venda precisa ser positivo.

**Resultado antes dos anúncios** = preço de venda − soma dos custos variáveis,
incluindo percentuais convertidos em valores monetários e arredondados por venda.
O resultado automático aparece independentemente de já ter sido informado o
valor a preservar após anúncios. A calculadora só mostra metas finais de CPA e
ROAS quando **todos** os campos estão preenchidos e validados.

O **resultado mínimo a preservar depois dos anúncios** é um objetivo econômico
editável e separado: CPA-alvo = resultado antes dos anúncios − valor a preservar;
ROAS-alvo = preço ÷ CPA-alvo (arredondado para cima). Não é correto preencher
automaticamente o objetivo pós-anúncio com toda a margem, pois isso resultaria
em CPA zero e ROAS indefinido. Se quiser apenas conhecer o ponto de equilíbrio,
informe **0** no campo de reserva e compare com CPA/ROAS de equilíbrio.

Os custos continuam apenas na memória da interface; nenhuma persistência,
migration ou chamada à Meta/Nuvemshop foi adicionada.

### PR 019.2 — Terminologia do lucro desejado

A interface apresenta o campo econômico anteriormente chamado **Resultado mínimo a preservar** como **Lucro desejado por venda**. Ele significa quanto se deseja conservar **após os custos variáveis e a publicidade**, antes dos custos fixos não modelados. Evitamos denominá-lo “lucro bruto” porque, na terminologia contábil, esse conceito usualmente é apurado antes das despesas de publicidade e não equivale ao valor deste campo.

É uma alteração exclusiva de nomenclatura e explicação da interface. O parâmetro interno `desiredProfit`, todas as fórmulas, validações, persistência inexistente e integrações continuam inalterados.

### Atualização do PR 019.2 — Lucro desejado em percentual

Apenas o campo **Lucro desejado por venda** passa a aceitar um **percentual do preço de venda** (17% pré-preenchido, editável, inclusive 0%). O valor em reais é **calculado automaticamente**, com arredondamento para centavos, e apresentado ao lado. Ao alterar o preço, o lucro calculado acompanha a nova base de cálculo; editar os custos altera a margem e os objetivos, mas não o percentual de lucro.

O campo percentual aceita vírgula ou ponto decimal com até duas casas, entre 0% e 100%. Entrada inválida ou vazia bloqueia as metas e apresenta erro; nenhuma entrada é convertida silenciosamente em zero. A aplicação continua considerando todos os campos de custos obrigatórios, com 0 explícito para custos não aplicáveis. O cálculo existente de CPA e ROAS **não muda**: o valor derivado em reais alimenta o parâmetro interno `desiredProfit`.

Com preço de R$ 199 e custos variáveis de R$ 111,91: 17% = R$ 33,83 de lucro desejado; CPA-alvo máximo de R$ 53,26; ROAS-alvo mínimo de 3,74x. São metas por unidade vendida, antes dos custos fixos não informados. A calculadora não persiste dados nem altera campanhas na Meta.


## PR 020 — Qualidade e auditoria da cobertura histórica

A migration **Flyway V5** cria `analytics_sync_window`. Esta tabela registra,
**na mesma transação que a gravação dos dados e a confirmação do checkpoint**,
os filtros temporais que uma coleta percorreu integralmente.

- **Meta Ads / INSIGHTS_DAILY:** conta de anúncios, `meta_from` e
  `meta_through` (datas da consulta por dia Meta). O coletor já rejeita
  paginação truncada e cursores inválidos, portanto apenas janelas de
  consultas concluídas são registradas.
- **Nuvemshop / ORDERS:** `shop_updated_from` e `shop_updated_through`,
  horários UTC efetivamente usados no filtro `updated_at`. **Isso não
  comprova** que foram capturadas todas as vendas segundo `paid_at`.
- Os registros de sincronização anteriores à V5 **não são reinterpretados
  retroativamente como janelas completas**. Janelas de runs `FAILED`,
  `RUNNING` ou `INCOMPLETE` nunca contribuem para o diagnóstico.
- A consulta de histórico é somente leitura, autenticada, filtrada pelo
  workspace, com período máximo de 90 dias. Não dispara uma coleta externa.

### Campos novos em /api/v1/analytics/history

O endpoint preserva `complete=false` e todos os campos existentes;
acrescenta `coverage.meta` e `coverage.store`:

- `coverage.meta.status`: `NO_COLLECTION_RECEIPT`,
  `PARTIAL_WINDOWS`, `WINDOWS_OBSERVED` ou `MULTIPLE_ACCOUNTS`.
- `requestedDays` / `observedDays`: dias pedidos / incluídos em
  janelas de consultas Meta **concluídas**; união das janelas, sem dupla contagem.
- `datesWithRows`: dias com linhas de dados Meta persistidas;
  `datesWithoutRows` e `observedWithoutRows`: dias consultados sem
  linhas Meta persistidas. **Isso não significa zero investimento ou vendas.**
- `unobservedDates`: dias para os quais não existe comprovante de coleta
  Meta nessa versão. Mesmo que existam snapshots legados, a cobertura
  **não** é inferida deles.
- `coverage.store.status=UPDATED_AT_ONLY_NOT_PAID_DATE` sempre:
  o total de janelas de atualização não prova cobertura da data do pagamento.
- A observação de **todas** as datas Meta não prova a exatidão/finalidade
  da atribuição, nem a conciliação com pedidos da Nuvemshop, nem
  equivalência de fusos. O histórico agregado continua **não verificado**.

Exemplo de verificação segura pós-implantação:

```bash
curl -sS -u admin \
  'http://localhost:18080/api/v1/analytics/history?since=2026-10-14&until=2026-10-20' |
  jq '{complete,coverage,warnings}'
```

Após aplicar V5, não há cobertura retroativa. Uma nova coleta **opcional,
local e supervisionada** (com `ADPILOT_ANALYTICS_SYNC_ENABLED=true`)
poderá registrar as janelas da Meta e da Nuvemshop. O frontend apresenta
essas diferenças na seção **O quanto do período foi consultado?** do Histórico.

### Custos iniciais da calculadora

O mesmo PR incorpora os valores iniciais **editáveis** combinados:
impostos `4%`, taxa de pagamento `5%`, tarifa fixa `R$ 2`,
frete subsidiado `R$ 3`, embalagem `R$ 2` e outros
custos variáveis `R$ 10`. O preço continua ilustrativo (`R$ 199`);
o custo da mercadoria continua obrigatório e em branco; o lucro
desejado mantém `17%`, editável. **Restaurar valores iniciais**
reaplica esses valores e não salva dados.

### Critérios de aceitação

- Flyway V5 aplicada e consulta de histórico com `coverage` sem erros.
- Sem novas coletas, snapshots antigos não criam falsa cobertura.
- Uma janela Meta concluída, mesmo sem linhas, é distinguível de
  uma data não coletada; o resultado comercial não é arbitrariamente zero.
- Janelas `FAILED` não contam para cobertura e períodos sobrepostos
  contam cada data uma única vez.
- Fonte Nuvemshop continua expressamente não auditada por data de pagamento.
- Sem alterações no motor de recomendações, Meta Ads ou Nuvemshop
  externos, políticas de OAuth ou escrita em anúncios.


## PR 021 — Recomendações orientadas pela qualidade da coleta

O motor de regras passa da versão 1 para a **versão 2**, mantendo os limiares
comerciais de amostra e as fórmulas de ROAS/CPA. A principal alteração é o
**controle de qualidade anterior à análise financeira**, utilizando as janelas
de Meta Ads consultadas com sucesso no PR 020.

### Fontes e limites

- A prévia `GET /api/v1/analytics/recommendations` continua **somente leitura**.
  Consulta snapshots de métricas locais, o estado do último sync e os recibos
  de janela `analytics_sync_window`; **não chama** Meta nem Nuvemshop.
- `report.quality` informa `status`, dias solicitados/consultados,
  dias com linhas/sem linhas, datas sem comprovante, instante de conclusão
  da janela, `syncFresh` e `analysisEligible`.
- Status: `NO_COLLECTION_RECEIPT`, `PARTIAL_WINDOWS`,
  `WINDOWS_OBSERVED`, `MULTIPLE_ACCOUNTS`,
  `ACCOUNT_MISMATCH`, `CURRENT_DAY_INCOMPLETE`.
- `syncFresh` exige que **a execução mais recente tenha estado
  `SUCCEEDED`**, que tanto o último checkpoint quanto o último recibo
  estejam dentro de **24 horas** e que nenhum deles esteja mais de
  cinco minutos no futuro em relação ao relógio da avaliação.
- `analysisEligible=true` **só** quando todos os dias do período
  pertencem a janelas observadas de uma mesma conta, o período não
  inclui o dia UTC atual e o sync está recente. Essa condição é apenas
  permissão para emitir recomendações **consultivas**. **Nunca**
  equivale a dados financeiramente auditados. Por isso
  `coverageVerified=false` permanece em todos os relatórios.
- Dias consultados sem linhas registradas não são inferidos como
  investimento ou vendas iguais a zero. A Meta pode retificar
  atribuições; a Nuvemshop não tem pedido-campanha conciliado.
- A regra `DATA_QUALITY_LIMITED` orienta revisar a coleta, com contagem
  de dias usada como evidência. Quando ela é acionada, as regras
  `ROAS_BELOW_TARGET`, `CPA_ABOVE_TARGET`,
  `SPEND_WITHOUT_PURCHASES`, `CTR_DECLINING`,
  `CPC_INCREASING`, `WITHIN_TARGETS` e
  `FAVORABLE_PERFORMANCE` ficam bloqueadas.
- `LOW_SAMPLE` continua independente: menos de cinco compras
  atribuídas, período menor que sete dias ou menos de três dias com
  linhas da campanha não justificam alterações de orçamento.
  Se houver também lacunas de coleta, as duas limitações são
  comunicadas separadamente.
- Mesmo com dados recentes e dias observados, o motor só permite
  `INVESTIGATE`, `MONITOR_ONLY`, `REVIEW_DATA` ou
  `CONFIGURE_TARGETS`, **nunca** pausa, escala ou altera campanhas
  automaticamente.

### Interface

A página **Recomendações** agora mostra o bloco “Qualidade dos dados usados
na análise”, com resumo de cobertura, quantidade de dias e aviso de
sincronização. A **Visão geral** dá prioridade a um alerta de qualidade
`DATA_QUALITY_LIMITED` quando existir uma campanha afetada.

**Exemplo homologado**: de 14 a 21/10/2026, a Meta foi consultada nos 8 dias,
6 com linhas e 2 sem linhas. A janela, se ainda recente e bem-sucedida,
passa no gate de consulta. A campanha anterior continua `LOW_SAMPLE`
por possuir apenas três compras atribuídas. A conclusão financeira
permanece preliminar; o recibo não é prova de venda paga.

**Sem Flyway novo**, sem integração adicional, sem mudança de orçamento,
sem novas dependências. Avaliações armazenadas via POST permanecem
apenas registros `DRAFT` locais e as recomendações guardam métricas
da qualidade da coleta como evidência.


## PR 022 — Metas econômicas imutáveis por produto e associação manual à Meta

O planejamento deixa de exigir que CPA e ROAS sejam redigitados em cada
análise: agora é possível salvar um plano de um produto e associá-lo
**explicitamente** a campanhas específicas. Não existe associação automática
entre produtos, campanhas, IDs de pedidos ou atribuições de anúncios.

### Arquitetura e segurança

- Migration **V6**: `analytics_product_goal` armazena uma versão **imutável**
  dos custos, percentuais e metas de um produto manualmente nomeado, em BRL;
  `analytics_campaign_goal` relaciona **uma** meta por
  `(workspace_id,account_id,campaign_id)`, com confirmação explícita de
  que a campanha representa uma unidade de um único produto. O mesmo plano
  pode ser associado a campanhas diferentes.
- As operações de planejamento usam o workspace local, autenticação Basic
  existente e SQL parametrizado. Somente campanhas conhecidas dos snapshots
  Meta, com conta e moeda BRL comprovadas nos Insights locais, podem ser
  associadas. Campanhas de moedas desconhecidas/outras moedas são recusadas.
- O backend recalcula todas as metas em centavos e basis points e não aceita
  CPA/ROAS vindos do navegador. Todos os custos são obrigatórios, incluindo
  zeros explícitos; preço e lucro são validados; um CPA não positivo é
  rejeitado. O ROAS-alvo é arredondado **para cima** até 0,01x.
- Atualizar os custos não modifica uma meta já armazenada: salve uma
  **nova versão** e associe-a novamente. O vínculo é reversível;
  a meta salva e avaliações anteriores continuam preservadas.
- `analytics_recommendation.product_goal_id` aponta para o plano imutável
  usado na avaliação, quando ao menos um dos limiares veio do plano. A
  avaliação com ambos limiares digitados manualmente não é rotulada como
  derivada do plano. O histórico de avaliações permanece `DRAFT` e local.
- Nenhuma rota do PR chama os endpoints de escrita da Meta ou da Nuvemshop.
  O frontend usa proxy Next.js de rotas permitidas, com credenciais somente
  no servidor, verificação da origem nas operações de escrita e `no-store`.

### Uso da interface

1. Acesse `/calculadora`, ajuste preço, custos e lucro desejado.
2. Informe o **nome manual** do produto e clique em **Salvar meta do produto**.
   É criado um snapshot imutável no PostgreSQL, identificado por UUID.
3. Acesse `/planejamento`; o catálogo de campanhas vem dos snapshots
   locais da Meta. Escolha uma meta e confirme que a campanha corresponde
   àquele produto e a uma unidade por pedido.
4. Em `/recomendacoes`, deixe os campos ROAS e CPA vazios para aplicar
   **as metas salvas da respectiva campanha**. Metas informadas manualmente
   substituem os **respectivos** campos, sem alterar os planos armazenados.
5. Para uma campanha não associada, o motor continua pedindo metas
   comerciais, em vez de copiar a meta de outra campanha.

A análise ainda é **consultiva**. A proteção do PR 021 continua ativa:
histórico desatualizado ou período parcialmente coletado bloqueia
investigações financeiras; amostras pequenas continuam recebendo
`LOW_SAMPLE`. Metas de produto não autorizam orçamento ou pausa automáticos.

**Limite deliberado:** a aplicação não identifica automaticamente o produto
vendido em uma compra atribuída pela Meta. Não vincule uma meta a campanhas
de catálogo, múltiplos produtos ou pedidos com cestas diferentes, a menos
que a hipótese de uma unidade de um produto seja de fato representativa.

### Contratos autenticados

```
GET    /api/v1/planning/catalog
POST   /api/v1/planning/goals
PUT    /api/v1/planning/campaigns/{accountId}/{campaignId}/goal
DELETE /api/v1/planning/campaigns/{accountId}/{campaignId}/goal
GET    /api/v1/analytics/recommendations?since=YYYY-MM-DD&until=YYYY-MM-DD
```

JSON para criar uma meta (o backend calcula CPA e ROAS):

```json
{
  "productLabel": "Vestido midi azul",
  "priceCents": 19900,
  "productCostCents": 7800,
  "taxBasisPoints": 400,
  "paymentBasisPoints": 500,
  "paymentFixedCents": 200,
  "shippingSubsidyCents": 300,
  "packagingCents": 200,
  "otherCostsCents": 1000,
  "desiredProfitBasisPoints": 1700
}
```

Resultado esperado no exemplo: `targetCpaCents=5226` (R$ 52,26),
`targetRoasHundredths=381` (3,81x). No vínculo PUT, envie o UUID
`goalId` e `singleProductConfirmed=true`. Sem esse consentimento, a
alteração local é recusada. O relatório de recomendações inclui
`linkedGoals` com identificadores, produto, CPA e ROAS salvos.

### Verificações

O CI valida a migration V6, criação e leitura de meta, contas
desconhecidas/moeda não comprovada, confirmação obrigatória, vínculo e
remoção, proveniência das avaliações em PostgreSQL, ausência de mutação
em provedores, proteção da rota Next contra POST cross-origin e interface
com o produto vinculado. Nenhuma sincronização externa é iniciada pelo
fluxo de planejamento.


### Diagnóstico seguro de 404 na sincronização Nuvemshop

O sincronizador consulta `GET /orders` com janela por `updated_at`.
Se a listagem trouxer um pedido pago sem `paid_at`, ele faz uma segunda
leitura individual `GET /orders/{id}`. Essas leituras podem falhar com
HTTP 404 por motivos diferentes.

Os códigos de falha na resposta de `POST /api/v1/analytics/sync`
e em `GET /api/v1/analytics/runs` distinguem esses casos:

- `ORDER_LIST_HTTP_404`: o endpoint de listagem incremental retornou 404
  (investigar versão/base da API, rota e parâmetros do intervalo).
- `ORDER_DETAIL_HTTP_404`: a listagem ocorreu, mas a consulta individual
  de um pedido pago sem `paid_at` retornou 404 (investigar disponibilidade
  do pedido e comportamento da API de detalhes).
- `ORDER_API_HTTP_404`: 404 de origem não identificada pela allowlist;
  código conservador para falhas desconhecidas.

A classificação é **somente diagnóstica** e não ignora registros,
não altera o cursor e não avança janelas incompletas. Os logs persistidos
contêm apenas códigos fixos de erro, sem URLs, identificadores de pedidos,
conteúdo do provedor ou dados de clientes. Não reinicie o cursor para
contornar um 404; investigue a etapa indicada pelo novo código.

Para verificar sem exibir pedidos:
```bash
curl -fsS -u admin -X POST http://localhost:18080/api/v1/analytics/sync \
  | jq '.results[] | {provider,resource,status,code,fetched,stored}'
```
