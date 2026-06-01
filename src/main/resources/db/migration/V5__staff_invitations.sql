CREATE TABLE staff_invitations (
    id BIGSERIAL PRIMARY KEY,
    email VARCHAR(255) NOT NULL,
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    invited_by_user_id BIGINT REFERENCES users(id) ON DELETE SET NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    accepted_at TIMESTAMPTZ,
    revoked_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_staff_invitations_token_hash_length CHECK (char_length(token_hash) = 64),
    CONSTRAINT chk_staff_invitations_email_lower CHECK (email = lower(trim(email)))
);

CREATE TABLE staff_invitation_roles (
    staff_invitation_id BIGINT NOT NULL REFERENCES staff_invitations(id) ON DELETE CASCADE,
    role VARCHAR(50) NOT NULL,
    PRIMARY KEY (staff_invitation_id, role),
    CONSTRAINT chk_staff_invitation_roles_role CHECK (
        role IN ('NUTRITION_SPECIALIST', 'PHYSICIAN', 'COORDINATOR')
    )
);

CREATE UNIQUE INDEX ux_staff_invitations_pending_email
    ON staff_invitations(email)
    WHERE accepted_at IS NULL AND revoked_at IS NULL;

CREATE INDEX ix_staff_invitations_email ON staff_invitations(email);
CREATE INDEX ix_staff_invitation_roles_role ON staff_invitation_roles(role);
