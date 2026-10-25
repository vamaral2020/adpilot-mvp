# PR050 — Diagnóstico agregado de correlação

Este ajuste acrescenta sondagens individuais de quatro campos financeiros na listagem da Nuvemshop, usando apenas uma linha por consulta e preservando a paginação principal. Quando a projeção ampliada retorna 404 e o fallback funciona, o relatório pode consultar até cinco pedidos com valores divergentes, usando somente os campos permitidos de total, subtotal, desconto e frete. A leitura de detalhes é exclusivamente em memória e produz contagens, sem IDs ou valores por pedido.

A janela de 48 horas passa a ser calculada com duração exata, sem truncamento. A classificação dos candidatos separa sinais alinhados, conflitantes e incompletos, mas não confirma captura, liquidação, pagamento ou atribuição Meta. Os campos financiallyVerified, paymentSettlementVerified, ordersMatchedToNuvemshop e ordersMatchedToCampaigns permanecem false.

A análise dos campos é limitada a quatro GETs extras; a de detalhes a cinco GETs extras. O diagnóstico só roda se as populações estiverem completas e o fallback da PR049 tiver ocorrido. Em caso de erro de autenticação, limite ou leitura dos detalhes, a investigação interrompe novas solicitações e mantém a ausência de evidências como indisponível. As rotas da aplicação não mudam.

Documentação da API de pedidos: https://nuvemshop.dev/docs-api/resources/2025-03/order

Homologação: após o merge manual, atualizar a branch main, reconstruir a API, habilitar temporariamente as duas flags de diagnóstico já utilizadas e repetir a chamada autenticada de correlação. Conferir o objeto supplemental e depois desabilitar as flags. Não compartilhar tokens ou dados individuais.
