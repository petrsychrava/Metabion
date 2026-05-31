ALTER TABLE user_roles
    ADD CONSTRAINT chk_user_roles_role
    CHECK (role IN ('PATIENT', 'NUTRITION_SPECIALIST', 'PHYSICIAN', 'COORDINATOR', 'ADMIN'));

CREATE TABLE patient_profiles (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT NOT NULL UNIQUE REFERENCES users(id) ON DELETE CASCADE,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE TABLE staff_profiles (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT NOT NULL UNIQUE REFERENCES users(id) ON DELETE CASCADE,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE FUNCTION require_patient_profile_role()
RETURNS TRIGGER AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM user_roles
        WHERE user_id = NEW.user_id
          AND role = 'PATIENT'
    ) THEN
        RAISE EXCEPTION 'Patient profile requires PATIENT role'
            USING ERRCODE = '23514';
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_patient_profiles_require_patient_role
    BEFORE INSERT OR UPDATE OF user_id ON patient_profiles
    FOR EACH ROW
    EXECUTE FUNCTION require_patient_profile_role();

CREATE FUNCTION require_staff_profile_role()
RETURNS TRIGGER AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM user_roles
        WHERE user_id = NEW.user_id
          AND role IN ('NUTRITION_SPECIALIST', 'PHYSICIAN', 'COORDINATOR')
    ) THEN
        RAISE EXCEPTION 'Staff profile requires a clinical staff role'
            USING ERRCODE = '23514';
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_staff_profiles_require_clinical_staff_role
    BEFORE INSERT OR UPDATE OF user_id ON staff_profiles
    FOR EACH ROW
    EXECUTE FUNCTION require_staff_profile_role();

CREATE FUNCTION protect_profile_role_integrity()
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

    IF OLD.role = 'PATIENT'
        AND EXISTS (
            SELECT 1
            FROM patient_profiles
            WHERE user_id = OLD.user_id
        )
        AND NOT (
            EXISTS (
                SELECT 1
                FROM user_roles
                WHERE user_id = OLD.user_id
                  AND role = 'PATIENT'
                  AND NOT (user_id = OLD.user_id AND role = OLD.role)
            )
            OR (TG_OP = 'UPDATE' AND NEW.user_id = OLD.user_id AND NEW.role = 'PATIENT')
        ) THEN
        RAISE EXCEPTION 'Cannot remove PATIENT role while patient profile exists'
            USING ERRCODE = '23514';
    END IF;

    IF OLD.role IN ('NUTRITION_SPECIALIST', 'PHYSICIAN', 'COORDINATOR')
        AND EXISTS (
            SELECT 1
            FROM staff_profiles
            WHERE user_id = OLD.user_id
        )
        AND NOT (
            EXISTS (
                SELECT 1
                FROM user_roles
                WHERE user_id = OLD.user_id
                  AND role IN ('NUTRITION_SPECIALIST', 'PHYSICIAN', 'COORDINATOR')
                  AND NOT (user_id = OLD.user_id AND role = OLD.role)
            )
            OR (
                TG_OP = 'UPDATE'
                AND NEW.user_id = OLD.user_id
                AND NEW.role IN ('NUTRITION_SPECIALIST', 'PHYSICIAN', 'COORDINATOR')
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

CREATE TRIGGER trg_user_roles_protect_profile_role_integrity
    BEFORE DELETE OR UPDATE OF user_id, role ON user_roles
    FOR EACH ROW
    EXECUTE FUNCTION protect_profile_role_integrity();

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
