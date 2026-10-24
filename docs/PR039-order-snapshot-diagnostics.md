# PR039 — Diagnóstico de correspondência entre pedidos e snapshots

## Objetivo e limite

O endpoint autenticado e opt-in compara IDs e datas, sem valores monetários,
entre uma leitura paginada ao vivo de pedidos da Nuvemshop e a tabela
analytics_order_snapshot do workspace local.

Isso não verifica faturamento, pagamentos históricos, exclusões, devoluções,
estornos, pedidos orgânicos ou causalidade de campanhas. Um scan sem divergências
não significa conciliação financeira aprovada.

A listagem da Nuvemshop é lida sem filtro updated_at_min para investigar
possíveis lacunas locais mesmo quando a sincronização incremental falha com 404.
O endpoint não altera o filtro, o cursor ou as regras da sincronização normal.

## Habilitação temporária

A rota usa a flag já existente ADPILOT_ORDER_LIST_DIAGNOSTICS_ENABLED=false.
Habilite-a somente durante a investigação, após conectar a Nuvemshop e
configurar HTTP Basic.

    curl -fsS -u admin -X POST \
      'http://localhost:18080/api/v1/analytics/orders/snapshot-diagnostics?pageSize=100&maxPages=20' \
      | jq .

Ao terminar, desabilite a flag novamente. A leitura utiliza até 20 páginas
de 100 pedidos (máximo 2.000), sob demanda, sem gravações nem migrations.
A API retorna somente contadores e códigos de alerta; jamais IDs de pedidos.

## Interpretação

- upstreamPaginationReconciled=true exige paginação esgotada, x-total-count
  presente e estável, contagem única igual ao total e nenhuma duplicação.
  Mesmo assim a consulta não é um snapshot transacional da Nuvemshop.
- paidOrderIdsMissingLocally sinaliza pedidos pagos retornados na leitura
  que não existem no snapshot local, apenas entre os pedidos consultados.
- locallyNotPaid significa divergência de status no instante da observação,
  não prova inconsistência definitiva.
- upstreamPaidAtMissing registra pedidos pagos sem paid_at na listagem.
  Isso pode ser compatível com paid_at resolvido via detalhe individual no
  snapshot local; não conte automaticamente como data divergente.
- paidAtDifferent exige datas válidas nas duas fontes; não normaliza para
  calendário local (compara instantes).
- financiallyVerified e ordersMatchedToCampaigns são sempre false.
  O diagnóstico não consulta transações nem eventos de pagamento.

Não usar esses contadores para atualizar automaticamente o dashboard financeiro
ou a alocação de orçamento. Investigar faltas/diferenças e a falha
updated_at_min antes de uma futura coleta verificável.
