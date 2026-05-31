CREATE TABLE roles (
    code            VARCHAR(50) PRIMARY KEY,
    patient_profile BOOLEAN NOT NULL DEFAULT FALSE,
    clinical_staff  BOOLEAN NOT NULL DEFAULT FALSE
);

INSERT INTO roles (code, patient_profile, clinical_staff)
VALUES
    ('PATIENT', TRUE, FALSE),
    ('NUTRITION_SPECIALIST', FALSE, TRUE),
    ('PHYSICIAN', FALSE, TRUE),
    ('COORDINATOR', FALSE, TRUE),
    ('ADMIN', FALSE, FALSE);

ALTER TABLE user_roles
    DROP CONSTRAINT IF EXISTS chk_user_roles_role;

ALTER TABLE user_roles
    ADD CONSTRAINT fk_user_roles_role
    FOREIGN KEY (role) REFERENCES roles(code);

CREATE INDEX idx_user_roles_role ON user_roles(role);

CREATE OR REPLACE FUNCTION require_patient_profile_role()
RETURNS TRIGGER AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM user_roles ur
        JOIN roles r ON r.code = ur.role
        WHERE ur.user_id = NEW.user_id
          AND r.patient_profile
    ) THEN
        RAISE EXCEPTION 'Patient profile requires PATIENT role'
            USING ERRCODE = '23514';
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION require_staff_profile_role()
RETURNS TRIGGER AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM user_roles ur
        JOIN roles r ON r.code = ur.role
        WHERE ur.user_id = NEW.user_id
          AND r.clinical_staff
    ) THEN
        RAISE EXCEPTION 'Staff profile requires a clinical staff role'
            USING ERRCODE = '23514';
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION protect_profile_role_integrity()
RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP = 'DELETE'
        AND NOT EXISTS (
            SELECT 1
            FROM users
            WHERE id = OLD.user_id
        ) THEN
        RETURN OLD;
    END IF;

    IF EXISTS (
            SELECT 1
            FROM roles
            WHERE code = OLD.role
              AND patient_profile
        )
        AND EXISTS (
            SELECT 1
            FROM patient_profiles
            WHERE user_id = OLD.user_id
        )
        AND NOT (
            EXISTS (
                SELECT 1
                FROM user_roles ur
                JOIN roles r ON r.code = ur.role
                WHERE ur.user_id = OLD.user_id
                  AND r.patient_profile
                  AND NOT (ur.user_id = OLD.user_id AND ur.role = OLD.role)
            )
            OR (
                TG_OP = 'UPDATE'
                AND NEW.user_id = OLD.user_id
                AND EXISTS (
                    SELECT 1
                    FROM roles
                    WHERE code = NEW.role
                      AND patient_profile
                )
            )
        ) THEN
        RAISE EXCEPTION 'Cannot remove PATIENT role while patient profile exists'
            USING ERRCODE = '23514';
    END IF;

    IF EXISTS (
            SELECT 1
            FROM roles
            WHERE code = OLD.role
              AND clinical_staff
        )
        AND EXISTS (
            SELECT 1
            FROM staff_profiles
            WHERE user_id = OLD.user_id
        )
        AND NOT (
            EXISTS (
                SELECT 1
                FROM user_roles ur
                JOIN roles r ON r.code = ur.role
                WHERE ur.user_id = OLD.user_id
                  AND r.clinical_staff
                  AND NOT (ur.user_id = OLD.user_id AND ur.role = OLD.role)
            )
            OR (
                TG_OP = 'UPDATE'
                AND NEW.user_id = OLD.user_id
                AND EXISTS (
                    SELECT 1
                    FROM roles
                    WHERE code = NEW.role
                      AND clinical_staff
                )
            )
        ) THEN
        RAISE EXCEPTION 'Cannot remove clinical staff role while staff profile exists'
            USING ERRCODE = '23514';
    END IF;

    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
