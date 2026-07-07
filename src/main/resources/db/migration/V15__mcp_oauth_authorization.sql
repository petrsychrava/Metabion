ALTER TABLE patient_access_tokens
    ADD COLUMN resource VARCHAR(255);

UPDATE patient_access_tokens
SET resource = 'http://localhost:8080/api/mcp'
WHERE resource IS NULL;

ALTER TABLE patient_access_tokens
    ALTER COLUMN resource SET NOT NULL;

CREATE TABLE oauth_authorization_codes (
    id BIGSERIAL PRIMARY KEY,
    code_hash VARCHAR(64) NOT NULL UNIQUE,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    client_id VARCHAR(255) NOT NULL,
    client_display_label VARCHAR(120) NOT NULL,
    redirect_uri VARCHAR(500) NOT NULL,
    resource VARCHAR(255) NOT NULL,
    code_challenge VARCHAR(128) NOT NULL,
    code_challenge_method VARCHAR(16) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ
);

CREATE INDEX idx_oauth_authorization_codes_user_id
    ON oauth_authorization_codes(user_id);

CREATE INDEX idx_oauth_authorization_codes_expires_at
    ON oauth_authorization_codes(expires_at);

CREATE TABLE oauth_authorization_code_scopes (
    authorization_code_id BIGINT NOT NULL REFERENCES oauth_authorization_codes(id) ON DELETE CASCADE,
    scope VARCHAR(80) NOT NULL,
    PRIMARY KEY (authorization_code_id, scope)
);
