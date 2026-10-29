# PR058 — Ajuste de experiência em tela do intérprete

## Escopo

Melhorar a leitura do diagnóstico em `/interpretar` **somente no navegador**,
com base na captura real enviada em 29/10/2026.
O contraste na tela está adequado; o defeito de legibilidade visto na
exportação para PDF será tratado separadamente, **não nesta PR**.

## Alterações de UI

1. Contexto da campanha mais compacto: objetivo, status, moeda e quantidade
   de conjuntos/anúncios sem um bloco longo antes do funil.
2. Cards de KPIs mais enxutos e menor espaçamento entre as cinco etapas.
3. Em telas de 1.000px ou mais, etapas usam duas colunas:
   volume/barra à esquerda, relação e custo à direita. Todas as cinco
   etapas continuam visíveis e preservam a ordem.
4. Para telas menores, o layout permanece em blocos, com grade de
   dois KPIs ou uma coluna em larguras muito estreitas.
5. Tabelas detalhadas por conjunto/anúncio e as tabelas legadas de
   Insights ficam dentro de disclosures nativos (`details/summary`)
   para consulta sob demanda. O funil, avisos e orientações continuam
   visíveis por padrão.
6. Estados de foco visível no teclado em disclosures. Nenhuma interação
   client-side adicional.

## Garantias

- Sem mudanças em backend, endpoints, lógica, fórmulas, critérios,
  aquisição de dados, campanhas ou permissões.
- Mantém avisos de atribuição, zero versus ausente, dados parciais,
  janelas com gasto zero e relação de eventos não sequenciais.
- Preserva a distinção entre Meta Purchase e pedidos pagos da Nuvemshop.
- CSS novo limitado a `@media screen`; **não altera regras de impressão**.
- Testes novos verificam hierarquia, conteúdo visível, detalhes acessíveis,
  breakpoints desktop/mobile e manutenção dos endpoints autenticados.
- Compatível com Next.js server components e sem bibliotecas adicionais.

## Homologação em tela

Após aprovação, merge manual e atualização local do container `web`:

```bash
git checkout main
git pull --ff-only origin main
docker compose up -d --build --force-recreate web
```

Na página `/interpretar` valide:

- Janela 18–25/10/2026 da campanha `52596120828588`:
  0 gasto, 1 visita, 11 carrinhos, 7 checkouts e 2 compras atribuídas.
  Não mostrar 1.100% como conversão, nem CPA de R$ 0.
- Janela 17–23/10/2026: campanha pausada com investimento e 3 compras
  atribuídas; custo por etapa segue disponível quando informado.
- Navegador desktop (largura de ~1.500px), notebook (~1.050px)
  e celular (~390px).
- Abrir e fechar `Detalhamento por conjunto e anúncio` e
  `Ver configuração técnica e tabelas completas de Insights`.
- Navegar pelos disclosures usando Tab e Enter.
- A interface inteira permanece somente leitura.

Nenhum teste ou ajuste visual de impressão/PDF integra esta PR.
