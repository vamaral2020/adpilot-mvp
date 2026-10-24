# PR044 — Base segura de consulta Stone/Pagar.me Core v5

## Objetivo

O AdPilot reconhece marca Stone em 28 pedidos elegíveis, somando
R$ 6.415,31 de valores brutos. Ainda não diferencia Stone PSP de
Stone Gateway para esses pedidos (STONE_UNSPECIFIED) e NÃO possui
associação confiável entre pedido Nuvemshop e charge Pagar.me.

Esta PR permite apenas uma consulta manual de UMA cobrança por ID.
Não enumera cobranças, não as relaciona a pedidos nem valida repasses.

Referências oficiais:
- https://docs.pagar.me/reference/autentica%C3%A7%C3%A3o-2
- https://docs.pagar.me/reference/obter-cobran%C3%A7a
- https://docs.pagar.me/reference/cobran%C3%A7as-1
- https://docs.pagar.me/docs/pagamentos
- https://docs.pagar.me/reference/retornando-receb%C3%ADveis

## Segurança

- Integração DESABILITADA por padrão:
  ADPILOT_STONE_DIAGNOSTICS_ENABLED=false
- Chave configurada apenas no backend via PAGARME_SECRET_KEY.
  A chave real permite operações financeiras: nunca enviá-la para
  chat, PR, logs, frontend ou Git.
- Auth outbound: HTTP Basic, secret key como usuário, senha vazia.
- Apenas GET no destino FIXO do cliente produtivo:
  https://api.pagar.me/core/v5/charges/{charge_id}
  (não há base URL arbitrária configurável em produção).
- Somente IDs de cobrança ch_ + ASCII alfanumérico são aceitos
  (sem path traversal, query ou URL arbitrária).
- Auth Basic local AdPilot obrigatório; porta apenas loopback em
  Docker Compose e Cache-Control: no-store.
- HTTP erros 401/403/404/429 são sanitizados, sem corpo do provedor,
  URLs, segredo ou PII.
- Resposta somente inclui status e payment_method de vocabulário
  fixo, moeda BRL/UNKNOWN e flags de presença/validez para amount e
  paid_amount em centavos. Não expõe ID, comprador, cartão,
  identificadores de gateway ou quantias individuais.
- Sem retry automático ou operações POST/PUT/PATCH/DELETE upstream.

## API local

    POST /api/v1/integrations/stone/charge-probe
    Content-Type: application/json
    {"chargeId":"ch_IDENTIFICADOR_REAL"}

Requer charge_id legítimo obtido no dashboard autorizado. Nenhuma
chave secreta deve ser fornecida em chats.

Execução APÓS CI e merge manual da PR:

    git checkout main && git pull --ff-only origin main
    # .env: PAGARME_SECRET_KEY=<configurar localmente>
    # .env: ADPILOT_STONE_DIAGNOSTICS_ENABLED=true
    docker compose up -d --build --force-recreate api

    read -r "STONE_CHARGE_ID?Charge ID do Pagar.me: "
    printf '{"chargeId":"%s"}' "$STONE_CHARGE_ID" |
      curl -fsS -u admin -X POST \
        -H 'Content-Type: application/json' \
        --data-binary @- \
        'http://localhost:18080/api/v1/integrations/stone/charge-probe' | jq .
    unset STONE_CHARGE_ID

Por fim, alterar flag para false e remover chave do .env local se não
for necessária, recriando o container para atualizar seu ambiente.

## Interpretação

- PAID confirma somente o status reportado pela API da cobrança.
- amountPresent/paidAmountPresent não exibem valores e não
  comprovam recebimentos no banco.
- PAID, CANCELED e CHARGEDBACK são estados distintos, chargeback
  exigirá dados de disputa.
- Pode haver MAIS DE UMA cobrança por pedido, inclusive uma cobrança
  nova em reprocessamentos.
- Sem correspondência do pedido Nuvemshop com a charge,
  sem estorno, taxa, antecipação, recebível ou settlement.
- financiallyVerified=false, paymentSettlementVerified=false,
  matchedToNuvemshopOrder=false SEMPRE.

## Próxima etapa (fora do escopo)

Com uma referência de pedido ↔ cobrança comprovada, verificar se
o provedor permite consultar todas as cobranças pelo order_id,
preservando reprocessamentos, pagamentos parciais e status.
Depois explorar payables, settlements, refunds e disputes em
seus próprios recursos, com controles de acesso e conciliação.
