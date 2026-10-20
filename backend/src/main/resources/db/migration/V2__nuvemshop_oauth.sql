-- OAuth anti-CSRF states are short-lived, one-time and stored only as SHA-256 hashes.
CREATE TABLE nuvemshop_oauth_state (
    state_hash BYTEA PRIMARY KEY,
    workspace_id UUID NOT NULL REFERENCES workspace(id),
    expires_at TIMESTAMPTZ NOT NULL
);

-- Never store the raw OAuth access_token. The IV is included in token_encrypted.
CREATE TABLE nuvemshop_installation (
    workspace_id UUID PRIMARY KEY REFERENCES workspace(id),
    store_id VARCHAR(40) NOT NULL,
    scope VARCHAR(500) NOT NULL,
    token_encrypted BYTEA NOT NULL,
    connected_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
