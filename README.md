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

## Escopo e segurança
- HTTP Basic com usuário/senha de ambiente no backend e na borda do Next.js (proxy). Só para localhost.
- Meta Graph API v26.0 por padrão, configurável via `META_GRAPH_VERSION`.
- Endpoints de leitura: `/api/v1/meta/status`, `/account`, `/campaigns`, `/insights`.
- Páginas com até 100 resultados, usando cursor `after` sem retornar `paging.next` (pode conter credenciais).
- Períodos de insights restritos a 90 dias por requisição.
- Tokens nunca persistidos no PostgreSQL. Nenhum endpoint modifica anúncios.
- Requer permissão `ads_read` e acesso à conta indicada.

**Não implantar na internet ainda:** Basic exige HTTPS fora de localhost. A futura versão SaaS deverá adotar sessões/OIDC, acesso por workspace, gestão de segredos e autorização mais granular.
