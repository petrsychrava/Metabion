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
