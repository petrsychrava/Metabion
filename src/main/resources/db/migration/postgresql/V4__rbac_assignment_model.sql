-- 1. Roles Lookup Table Setup (The new foundation)
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
    ADD CONSTRAINT fk_user_roles_role
    FOREIGN KEY (role) REFERENCES roles(code);

CREATE INDEX idx_user_roles_role ON user_roles(role);


-- 2. Core Profile & Cohort Tables
CREATE TABLE patient_profiles (
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT NOT NULL UNIQUE REFERENCES users(id) ON DELETE CASCADE,
    date_of_birth   DATE,
    sex             VARCHAR(40),
    country_region  VARCHAR(100),
    timezone        VARCHAR(100),
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_patient_profiles_sex
        CHECK (sex IS NULL OR sex IN ('FEMALE', 'MALE', 'INTERSEX', 'PREFER_NOT_TO_SAY'))
);

CREATE TABLE staff_profiles (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT NOT NULL UNIQUE REFERENCES users(id) ON DELETE CASCADE,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE TABLE cohorts (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(255) NOT NULL,
    description TEXT,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE TABLE patient_cohort_memberships (
    id                  BIGSERIAL PRIMARY KEY,
    patient_profile_id  BIGINT NOT NULL REFERENCES patient_profiles(id) ON DELETE CASCADE,
    cohort_id           BIGINT NOT NULL REFERENCES cohorts(id) ON DELETE CASCADE,
    assigned_by_user_id BIGINT REFERENCES users(id) ON DELETE SET NULL,
    assigned_at         TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    ended_at            TIMESTAMP WITH TIME ZONE,
    CONSTRAINT chk_patient_cohort_memberships_interval
        CHECK (ended_at IS NULL OR ended_at >= assigned_at)
);

CREATE UNIQUE INDEX uq_pcm_active_patient_cohort
    ON patient_cohort_memberships(patient_profile_id, cohort_id)
    WHERE ended_at IS NULL;
CREATE INDEX idx_pcm_patient_profile_id ON patient_cohort_memberships(patient_profile_id);
CREATE INDEX idx_pcm_cohort_id ON patient_cohort_memberships(cohort_id);
CREATE INDEX idx_pcm_assigned_by_user_id ON patient_cohort_memberships(assigned_by_user_id);

CREATE TABLE patient_expert_assignments (
    id                  BIGSERIAL PRIMARY KEY,
    patient_profile_id  BIGINT NOT NULL REFERENCES patient_profiles(id) ON DELETE CASCADE,
    staff_profile_id    BIGINT NOT NULL REFERENCES staff_profiles(id) ON DELETE CASCADE,
    assigned_by_user_id BIGINT REFERENCES users(id) ON DELETE SET NULL,
    assigned_at         TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    ended_at            TIMESTAMP WITH TIME ZONE,
    CONSTRAINT chk_patient_expert_assignments_interval
        CHECK (ended_at IS NULL OR ended_at >= assigned_at)
);

CREATE UNIQUE INDEX uq_pea_active_patient_staff
    ON patient_expert_assignments(patient_profile_id, staff_profile_id)
    WHERE ended_at IS NULL;
CREATE INDEX idx_pea_patient_profile_id ON patient_expert_assignments(patient_profile_id);
CREATE INDEX idx_pea_staff_profile_id ON patient_expert_assignments(staff_profile_id);
CREATE INDEX idx_pea_assigned_by_user_id ON patient_expert_assignments(assigned_by_user_id);

CREATE TABLE cohort_staff_assignments (
    id                  BIGSERIAL PRIMARY KEY,
    cohort_id           BIGINT NOT NULL REFERENCES cohorts(id) ON DELETE CASCADE,
    staff_profile_id    BIGINT NOT NULL REFERENCES staff_profiles(id) ON DELETE CASCADE,
    assigned_by_user_id BIGINT REFERENCES users(id) ON DELETE SET NULL,
    assigned_at         TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    ended_at            TIMESTAMP WITH TIME ZONE,
    CONSTRAINT chk_cohort_staff_assignments_interval
        CHECK (ended_at IS NULL OR ended_at >= assigned_at)
);

CREATE UNIQUE INDEX uq_csa_active_cohort_staff
    ON cohort_staff_assignments(cohort_id, staff_profile_id)
    WHERE ended_at IS NULL;
CREATE INDEX idx_csa_cohort_id ON cohort_staff_assignments(cohort_id);
CREATE INDEX idx_csa_staff_profile_id ON cohort_staff_assignments(staff_profile_id);
CREATE INDEX idx_csa_assigned_by_user_id ON cohort_staff_assignments(assigned_by_user_id);


-- 3. Business Logic (Functions & Triggers using the dynamic roles table)

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
        RAISE EXCEPTION 'Staff profile requires a clinical_staff role'
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

CREATE TRIGGER trg_patient_profiles_require_patient_role
    BEFORE INSERT OR UPDATE OF user_id ON patient_profiles
    FOR EACH ROW
    EXECUTE FUNCTION require_patient_profile_role();

CREATE TRIGGER trg_staff_profiles_require_clinical_staff_role
    BEFORE INSERT OR UPDATE OF user_id ON staff_profiles
    FOR EACH ROW
    EXECUTE FUNCTION require_staff_profile_role();

CREATE CONSTRAINT TRIGGER trg_user_roles_protect_profile_role_integrity
    AFTER DELETE OR UPDATE ON user_roles
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    EXECUTE FUNCTION protect_profile_role_integrity();
