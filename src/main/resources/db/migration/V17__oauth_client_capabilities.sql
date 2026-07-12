ALTER TABLE oauth_registered_clients
    ADD COLUMN application_type VARCHAR(16) NOT NULL DEFAULT 'native';

CREATE TABLE oauth_registered_client_grant_types (
    registered_client_id BIGINT NOT NULL REFERENCES oauth_registered_clients(id) ON DELETE CASCADE,
    grant_type_order INTEGER NOT NULL,
    grant_type VARCHAR(32) NOT NULL,
    PRIMARY KEY (registered_client_id, grant_type_order)
);

INSERT INTO oauth_registered_client_grant_types (registered_client_id, grant_type_order, grant_type)
SELECT id, 0, 'authorization_code'
FROM oauth_registered_clients;

ALTER TABLE patient_access_tokens
    ADD COLUMN refresh_family_id VARCHAR(64);

CREATE TABLE oauth_refresh_token_families (
    family_id VARCHAR(64) PRIMARY KEY,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    revoked_at TIMESTAMP WITH TIME ZONE,
    revocation_reason VARCHAR(120)
);

CREATE INDEX idx_patient_access_tokens_refresh_family
    ON patient_access_tokens(refresh_family_id);

CREATE TABLE oauth_refresh_tokens (
    id BIGSERIAL PRIMARY KEY,
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    family_id VARCHAR(64) NOT NULL REFERENCES oauth_refresh_token_families(family_id),
    user_id BIGINT NOT NULL REFERENCES users(id),
    client_id VARCHAR(500) NOT NULL,
    client_source VARCHAR(32) NOT NULL,
    client_type VARCHAR(40) NOT NULL,
    display_label VARCHAR(120) NOT NULL,
    resource VARCHAR(255) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    consumed_at TIMESTAMP WITH TIME ZONE,
    replacement_token_id BIGINT REFERENCES oauth_refresh_tokens(id),
    revoked_at TIMESTAMP WITH TIME ZONE,
    revocation_reason VARCHAR(120)
);

CREATE INDEX idx_oauth_refresh_tokens_family ON oauth_refresh_tokens(family_id);
CREATE INDEX idx_oauth_refresh_tokens_client ON oauth_refresh_tokens(client_id);

ALTER TABLE patient_access_tokens
    ADD CONSTRAINT fk_patient_access_tokens_refresh_family
    FOREIGN KEY (refresh_family_id) REFERENCES oauth_refresh_token_families(family_id);

CREATE TABLE oauth_refresh_token_scopes (
    refresh_token_id BIGINT NOT NULL REFERENCES oauth_refresh_tokens(id) ON DELETE CASCADE,
    scope VARCHAR(80) NOT NULL,
    PRIMARY KEY (refresh_token_id, scope)
);
