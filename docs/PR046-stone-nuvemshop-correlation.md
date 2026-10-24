# PR046 — Correlação exploratória Pagar.me / Nuvemshop

## Contexto

A PR045 confirmou que a API Core v5 retorna 181 pedidos e 181 cobranças
na conta configurada, com paginação reconciliada. A Nuvemshop continha
111 pedidos na consulta de PR043, dos quais 80 elegíveis como pagos.
Essas populações NÃO são necessariamente contemporâneas ou coincidentes.

O campo order.code do Pagar.me é uma referência informada pela loja:
https://docs.pagar.me/reference/pedidos-1
Não há garantia de que a integração Stone/Nuvemshop use o número
comercial da Nuvemshop ou seu ID interno nesse campo.

## Endpoint de diagnóstico

    POST /api/v1/integrations/stone/nuvemshop-correlation?pageSize=50&maxPages=10

Requer:
- HTTP Basic local de administrador, como outros endpoints de diagnóstico
- Nuvemshop conectada
- PAGARME_SECRET_KEY configurada somente no backend
- ADPILOT_STONE_DIAGNOSTICS_ENABLED=true
- ADPILOT_ORDER_LIST_DIAGNOSTICS_ENABLED=true

Ambas as flags são false por padrão. O operador habilita as duas
temporariamente. Sem mudanças de banco, sincronização, worker,
cursor, webhooks, painel, Meta Ads ou credenciais persistidas.
A resposta envia Cache-Control: no-store.

## Leituras e limites

- Nuvemshop: GET orders, campos id, number, status, payment_status,
  total, currency, created_at, payment_details.method.
- Pagar.me Core v5: GET orders?page=N&size=M — id, code, status,
  currency, amount (inteiro em centavos, quando disponível),
  created_at.
- Pagar.me Core v5: GET charges?page=N&size=M — id, order_id,
  status, payment_method; somente para comparação interna de método.

pageSize 1..50 e maxPages 1..10, por coleção. Padrão: 50 e 10.
NO MÁXIMO 30 requisições HTTP por execução; não há retry ou
persistência. O cliente ignora URL retornada no campo paging.next
e monta requisições com página/limite inteiros fixados pelo servidor.
Paginação somente é reconhecida como reconciliada quando os totais
reportados conferem, a iteração termina, não há duplicidades e os
metadados estão completos. Nenhuma consulta representa um
snapshot atômico das três coleções.

## Regras explícitas de correspondência

- São tentados SOMENTE códigos order.code inteiramente numéricos,
  ASCII [0-9]{1,30}, sem transformar prefixos, barras, sinais,
  espaços ou zeros à esquerda.
- Compara-se exatamente com Nuvemshop id e number.
- Um código deve referenciar um único pedido da Nuvemshop.
- Se coincidir com IDs/números de pedidos diferentes, é AMBÍGUO.
- Se dois pedidos Pagar.me apontarem para o mesmo pedido Nuvemshop,
  há colisão muitos-para-um: ambos são excluídos das comparações.
- Uma chave que casa com id e number do MESMO pedido é uma
  categoria BOTH, não duas associações.
- Valores só são comparados em BRL com ambos os montantes íntegros;
  Pagar.me amount em centavos, Nuvemshop total convertido sem
  arredondamentos implícitos.
- Situação de pagamento apenas compara booleano PAID/não PAID
  nas duas fontes. Não significa captura/liquidação.
- Datas de criação, quando presentes, são comparadas por diferença
  absoluta de no máximo 48 horas (sinal auxiliar, não prova).
- Método é comparado somente se houver EXATAMENTE UMA cobrança
  observada para o pedido do Pagar.me e métodos reconhecidos;
  tentativas múltiplas nunca são colapsadas em uma única.
- Candidatos dependem da completude das páginas. Resultados
  incompletos mantêm aviso explícito e não são declarados
  associações verificadas.

## Saída e privacidade

O relatório agrega quantidades de:
- pedidos/cobranças por fonte, paginação, IDs duplicados
- códigos ausentes, não numéricos, sem correspondência, ambíguos
- candidatos por id / number / ambos; colisões muitos-para-um
- comparabilidade, igualdade e diferenças de valores
- igualdade ou conflito do indicador PAID
- proximidade de timestamps
- igualdade ou conflito de método de pagamento
- ausência ou multiplicidade de cobranças na amostra

NÃO retorna ID individual de pedido, cobrança ou referência, valores,
datas, nomes, documentos, e-mails, endereço, cartões, metadados,
dados brutos upstream ou chaves. Nenhuma associação é gravada.
As referências em memória são descartadas após cada chamada.

Flags invariavelmente false:
financiallyVerified
paymentSettlementVerified
ordersMatchedToNuvemshop
ordersMatchedToCampaigns

A última é intencional: uma correspondência textual de código ainda
é evidência exploratória, não prova sobre o vínculo contratual das
integrações. Requer conferência de uma amostra auditável e descoberta
de como a Stone cria code antes da promoção do status de match.

## Homologação local

Depois de passar CI e realizar merge MANUAL:

    git checkout main
    git pull --ff-only origin main

Alterar apenas no arquivo .env local:

    ADPILOT_STONE_DIAGNOSTICS_ENABLED=true
    ADPILOT_ORDER_LIST_DIAGNOSTICS_ENABLED=true

Recriar API:

    docker compose up -d --build --force-recreate api

Executar:

    curl -fsS -u admin -X POST \
      'http://localhost:18080/api/v1/integrations/stone/nuvemshop-correlation?pageSize=50&maxPages=10' \
      | jq .

Se houver qualquer erro HTTP, repetir SEM -f para obter o código
sanitizado do AdPilot. Não compartilhar logs completos que contenham
segredos nem valores de .env.

Depois voltar as duas flags para false e recriar a API.
A chave da Stone pode ser removida do .env local se desnecessária.

## Próximos passos, fora desta PR

1. Se aparecerem candidatos, validar os significados de code e
   a integridade de IDs com documentação/integração contratual e
   amostras controladas sem PII.
2. Caso nenhum código numérico coincida, NÃO adotar comparações
   aproximadas; verificar outra referência técnica oferecida pela
   integração antes de criar mapeamento.
3. Desenvolver trilha de conciliação: cobrança, pagamento,
   captura, cancelamento, disputa, estorno, taxas e recebíveis.
4. Somente após comprovação independente os indicadores e a
   atribuição ao Meta poderão ser promovidos.
