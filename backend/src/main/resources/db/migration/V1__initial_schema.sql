-- Estrutura inicial SaaS-ready; nenhuma credencial externa é persistida nesta fase.
CREATE TABLE workspace (
    id UUID PRIMARY KEY,
    name VARCHAR(160) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE integration_connection (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL REFERENCES workspace(id),
    provider VARCHAR(32) NOT NULL CHECK (provider IN ('META_ADS','NUVEMSHOP')),
    status VARCHAR(32) NOT NULL CHECK (status IN ('PENDING','CONNECTED','ERROR','DISABLED')),
    external_account_id VARCHAR(128),
    last_sync_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_workspace_provider UNIQUE (workspace_id, provider)
);

INSERT INTO workspace (id,name)
VALUES ('00000000-0000-4000-8000-000000000001','Workspace pessoal');
