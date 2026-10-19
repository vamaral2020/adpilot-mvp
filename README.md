# AdPilot — fundação do MVP

Projeto inicial do sistema próprio de acompanhamento e futura automação supervisionada de anúncios Meta Ads, integrado à Nuvemshop.

## O que já existe

- Backend Java 21 + Spring Boot 4.1.1, `GET /api/v1/system/overview` e Actuator health.
- Frontend Next.js 16.3 + TypeScript com painel inicial e estados reais de conectividade (sem dados fictícios).
- PostgreSQL 17 com migração Flyway (workspace e estrutura para conexões externas, **sem guardar tokens**).
- Docker Compose com portas exclusivas (13000, 18080, 15432) e bind em `127.0.0.1`.
- Spring Security permitindo somente as rotas públicas estritamente necessárias à fundação.
- CI básico para testes de backend e build de frontend.

## Primeira execução com Docker Desktop

1. Instale Docker Desktop e verifique se `docker compose version` funciona.
2. Extraia este pacote e abra o terminal na pasta `adpilot-mvp`.
3. Crie o `.env`: no macOS/Linux execute `cp .env.example .env`; no PowerShell execute `Copy-Item .env.example .env`.
4. Edite `DB_PASSWORD` no `.env` (somente uma senha local de desenvolvimento).
5. Execute:

   ```bash
   docker compose up --build -d
   docker compose ps
   ```

6. Teste os recursos:
   - Dashboard: http://localhost:13000
   - API status: http://localhost:18080/api/v1/system/overview
   - Health: http://localhost:18080/actuator/health
   - PostgreSQL (local): `localhost:15432`

7. Para acompanhar os serviços: `docker compose logs -f api web`.
8. Para desligar preservando o banco: `docker compose down`.

**Não rode `docker compose down -v`** se precisar manter os dados.

## Desenvolvimento fora do Docker

Banco: `docker compose up -d postgres`. Execute backend com Maven 3.9.9+ e Java 21:

```bash
cd backend
SPRING_DATASOURCE_PASSWORD=<sua-senha> mvn spring-boot:run
```

No Windows PowerShell, defina `$env:SPRING_DATASOURCE_PASSWORD="..."` antes de executar Maven.

Frontend (Node.js 22):

```bash
cd frontend
npm install
npm run dev
```

Nesse modo o frontend acessa `http://localhost:18080` como padrão; caso o backend esteja diretamente em `8080`, configure `INTERNAL_API_URL=http://localhost:8080`. Em Docker esse endereço é configurado automaticamente pelo Compose.

## Segurança e escopo

**Atenção: esta versão não tem autenticação no frontend.** Por isso as portas ficam vinculadas a `127.0.0.1`. NÃO publicar em produção nem expor com proxy reverso ou túnel antes de implementar login/autorização e proteção de sessões.

Nenhuma campanha Meta foi importada ou alterada. Tokens `ads_read`, App Secret, token Nuvemshop ou dados de lojas reais não fazem parte deste pacote. O sistema de integração permanece `NOT_CONFIGURED` até implementação posterior.

## Próximo desenvolvimento (após validar o ambiente)

1. Autenticação local de usuário e autorização do painel.
2. Implementação do cliente Meta Ads somente leitura e gestão segura do token.
3. OAuth Nuvemshop e sync de produtos/pedidos.
4. Dashboard de métricas reais e regras de otimização.

## Notas de arquitetura

- Monólito modular; a estrutura inicial possui os módulos `auth` e `system`. Os demais serão adicionados conforme implementação real.
- O `workspace` padrão prepara a modelagem multiempresa, sem implementar isolamento multi-tenant completo.
- A tabela `integration_connection` não persiste segredos. Para a etapa de integração, implementar uma estratégia segura de criptografia e rotação antes de salvar tokens.
- `spring.jpa.hibernate.ddl-auto=validate`; Flyway é dono do schema.
- A configuração do `DATABASE_URL` usa `SPRING_DATASOURCE_URL`. Credenciais apenas em variáveis de ambiente para a fundação local.
- Dependências são resolvidas por Maven Central e npm Registry ao construir os containers. É necessário acesso à internet no ambiente de desenvolvimento na primeira execução.
