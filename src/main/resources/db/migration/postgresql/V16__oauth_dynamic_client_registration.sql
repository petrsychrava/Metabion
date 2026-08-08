CREATE TABLE oauth_registered_clients (
    id BIGSERIAL PRIMARY KEY,
    client_id VARCHAR(80) NOT NULL UNIQUE,
    client_name VARCHAR(120),
    token_endpoint_auth_method VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE oauth_registered_client_redirect_uris (
    registered_client_id BIGINT NOT NULL REFERENCES oauth_registered_clients(id) ON DELETE CASCADE,
    redirect_uri_order INTEGER NOT NULL,
    redirect_uri VARCHAR(500) NOT NULL,
    PRIMARY KEY (registered_client_id, redirect_uri_order)
);

CREATE TABLE oauth_registered_client_scopes (
    registered_client_id BIGINT NOT NULL REFERENCES oauth_registered_clients(id) ON DELETE CASCADE,
    scope VARCHAR(80) NOT NULL,
    PRIMARY KEY (registered_client_id, scope)
);
