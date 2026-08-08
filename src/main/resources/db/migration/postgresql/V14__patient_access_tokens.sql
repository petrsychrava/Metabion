CREATE TABLE patient_access_tokens (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    client_type VARCHAR(40) NOT NULL,
    display_label VARCHAR(120) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    last_used_at TIMESTAMPTZ,
    revoked_at TIMESTAMPTZ,
    revocation_reason VARCHAR(120)
);

CREATE INDEX idx_patient_access_tokens_user_id
    ON patient_access_tokens(user_id);

CREATE INDEX idx_patient_access_tokens_active_user
    ON patient_access_tokens(user_id, revoked_at, created_at DESC);

CREATE TABLE patient_access_token_scopes (
    token_id BIGINT NOT NULL REFERENCES patient_access_tokens(id) ON DELETE CASCADE,
    scope VARCHAR(80) NOT NULL,
    PRIMARY KEY (token_id, scope)
);
