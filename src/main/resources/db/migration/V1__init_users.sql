CREATE TABLE users (
    id                     BIGSERIAL PRIMARY KEY,
    email                  VARCHAR(255) NOT NULL UNIQUE,
    password_hash          VARCHAR(255) NOT NULL,
    enabled                BOOLEAN NOT NULL DEFAULT FALSE,
    failed_login_attempts  INT NOT NULL DEFAULT 0,
    locked_until           TIMESTAMP WITH TIME ZONE,
    mfa_enabled            BOOLEAN NOT NULL DEFAULT FALSE,
    mfa_secret_encrypted   BYTEA,
    created_at             TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at             TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE TABLE user_roles (
    user_id  BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role     VARCHAR(50) NOT NULL,
    PRIMARY KEY (user_id, role)
);
