# PR045 — Descoberta histórica de pedidos e cobranças Stone/Pagar.me

## Objetivo e limites

Disponibilizar um diagnóstico controlado sobre recursos já existentes na
API Pagar.me Core v5, sem criar webhook, sem movimentação financeira,
sem salvar dados e sem converter os pedidos da Nuvemshop em pagamentos
confirmados.

Endpoints upstream:
- GET https://api.pagar.me/core/v5/orders?page=1&size=20
- GET https://api.pagar.me/core/v5/charges?page=1&size=20

Paginação limitada a pageSize 1..50 e maxPages 1..10 por recurso.
Por padrão, 5 páginas x 20 = no máximo 100 pedidos e 100 cobranças
(10 chamadas HTTP no limite). Os recursos são consultados separadamente;
a falta de referência entre amostras nunca significa cobrança órfã.

As respostas da Stone são reduzidas em memória a:
- Pedido: ID do provedor usado somente na associação interna,
  status limitado a vocabulário fixo, indicador se code existe.
- Cobrança: ID do provedor em memória, ID do pedido Pagar.me referenciado,
  status e método conhecidos, presença de amount/paid_amount.

Nenhum desses IDs, raw code, valor individual, transaction/card/customer,
email, documento ou URL de paginação será devolvido, persistido ou logado.
As respostas externas são processadas sem registrar JSON de origem.

## Endpoint administrativo

    POST /api/v1/integrations/stone/history-discovery?pageSize=20&maxPages=5

Requer HTTP Basic do AdPilot, a chave PAGARME_SECRET_KEY no servidor,
e ADPILOT_STONE_DIAGNOSTICS_ENABLED=true (default false).
O output é agregado e HTTP Cache-Control: no-store.

Como já existe a chave local na PR044, basta manter o arquivo .env
seguro e habilitar a flag SOMENTE na execução manual:

    git checkout main && git pull --ff-only origin main
    # .env: ADPILOT_STONE_DIAGNOSTICS_ENABLED=true
    docker compose up -d --build --force-recreate api
    curl -fsS -u admin -X POST \
      'http://localhost:18080/api/v1/integrations/stone/history-discovery?pageSize=20&maxPages=5' | jq .

Após o teste desabilitar a flag, remover a chave do .env quando não
necessária e recriar o container para aplicar o ambiente.

## Resumo seguro devolvido

- source e keyEnvironment (SANDBOX/PRODUCTION/UNDETERMINED).
- providerOrders/providerCharges: páginas, linhas, IDs únicos,
  duplicidades, total reportado e distribuição de status.
- paginationExhausted/paginationReconciled são conservadores:
  somente true se houver metadados válidos e consistentes,
  sem duplicidades, com total conferido e sem página pendente.
  A API não fornece visão transacional no tempo, então isto NÃO
  representa prova de cobertura histórica de todos os pedidos.
- ordersWithExternalCode: quantidade de pedidos Pagar.me com campo
  code preenchido (NUNCA prova match Nuvemshop).
- chargesWithProviderOrderReference / chargesWithMissingProviderOrderReference.
- chargesLinkedToObservedProviderOrders apenas relaciona recursos
  DO MESMO PROVEDOR, dentro da amostra lida; não é match Nuvemshop.
- chargesReferencingProviderOrdersOutsideSample:
  o pedido pai não apareceu na amostra consultada — não significa órfão.
- observedProviderOrdersWithMultipleCharges:
  sinaliza reprocessamentos ou múltiplas cobranças possíveis; não
  define por si só a causa ou valor líquido.
- paymentMethodCounts: agrupamentos por lista fixa.

## Proteções

- Nenhum POST, PUT, PATCH ou DELETE no provedor.
- Basic auth outbound com SecretKey como usuário e senha vazia.
- HTTPS fixo em produção, sem redirects e sem base URL configurável.
- Sem retransmitir links/paging.next de upstream; paginação sempre
  construída localmente via números inteiros limitados.
- Falhas 401/403, 404, 429 e payload malformado abortam a operação,
  sem retorno de resultado parcial que pareça uma conciliação.
- Não buscar dados sensíveis de transações, cartões ou clientes.
- Sem uso de identifiers de pedido da Nuvemshop nesta PR.

## Alertas e próximos passos

financiallyVerified=false
paymentSettlementVerified=false
ordersMatchedToNuvemshop=false
ordersMatchedToCampaigns=false

É possível que a credencial Stone/Pagar.me desta conta NÃO possua
acesso histórico às vendas geradas pela Nuvemshop (ou esteja em
sandbox). Uma listagem vazia é apenas ausência de registros acessíveis
naquele contexto de API, não prova de que não houve vendas.

Fonte de autenticação e modelo de paginação:
- https://docs.pagar.me/reference/autentica%C3%A7%C3%A3o-2
- https://docs.pagar.me/reference/pagina%C3%A7%C3%A3o-1
- https://docs.pagar.me/docs/pagamentos

Etapa futura, APENAS com evidências de vínculos reais: projetar
correlação Nuvemshop ↔ Pagar.me com múltiplas cobranças por pedido,
verificar montantes, status, taxas, estornos e repasses nas APIs
apropriadas. Webhook pode ser desenvolvido posteriormente para
atualizações incrementais.
