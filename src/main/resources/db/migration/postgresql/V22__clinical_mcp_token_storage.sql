CREATE TABLE clinical_access_tokens (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id),
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    client_type VARCHAR(40) NOT NULL,
    display_label VARCHAR(120) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    resource VARCHAR(255) NOT NULL,
    last_used_at TIMESTAMPTZ,
    revoked_at TIMESTAMPTZ,
    revocation_reason VARCHAR(120),
    refresh_family_id VARCHAR(64),
    CONSTRAINT fk_clinical_access_tokens_refresh_family
        FOREIGN KEY (refresh_family_id) REFERENCES oauth_refresh_token_families(family_id)
);

CREATE INDEX idx_clinical_access_tokens_user_id
    ON clinical_access_tokens(user_id);

CREATE INDEX idx_clinical_access_tokens_active
    ON clinical_access_tokens(user_id, revoked_at, expires_at);

CREATE INDEX idx_clinical_access_tokens_refresh_family
    ON clinical_access_tokens(refresh_family_id);

CREATE TABLE clinical_access_token_scopes (
    token_id BIGINT NOT NULL REFERENCES clinical_access_tokens(id) ON DELETE CASCADE,
    scope VARCHAR(80) NOT NULL CHECK (scope LIKE 'clinician:%'),
    PRIMARY KEY (token_id, scope)
);

ALTER TABLE oauth_authorization_codes
    ADD COLUMN subject_type VARCHAR(16) NOT NULL DEFAULT 'PATIENT',
    ADD CONSTRAINT chk_oauth_authorization_codes_subject_type
        CHECK (subject_type IN ('PATIENT', 'CLINICIAN'));

ALTER TABLE oauth_refresh_tokens
    ADD COLUMN subject_type VARCHAR(16) NOT NULL DEFAULT 'PATIENT',
    ADD CONSTRAINT chk_oauth_refresh_tokens_subject_type
        CHECK (subject_type IN ('PATIENT', 'CLINICIAN'));
