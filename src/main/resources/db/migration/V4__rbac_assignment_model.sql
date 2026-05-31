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
    ended_at            TIMESTAMP WITH TIME ZONE
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
    ended_at            TIMESTAMP WITH TIME ZONE
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
    ended_at            TIMESTAMP WITH TIME ZONE
);

CREATE UNIQUE INDEX uq_csa_active_cohort_staff
    ON cohort_staff_assignments(cohort_id, staff_profile_id)
    WHERE ended_at IS NULL;
CREATE INDEX idx_csa_cohort_id ON cohort_staff_assignments(cohort_id);
CREATE INDEX idx_csa_staff_profile_id ON cohort_staff_assignments(staff_profile_id);
CREATE INDEX idx_csa_assigned_by_user_id ON cohort_staff_assignments(assigned_by_user_id);
