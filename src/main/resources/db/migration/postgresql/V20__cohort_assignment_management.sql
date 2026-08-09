ALTER TABLE cohorts
    ADD COLUMN created_by_user_id BIGINT NOT NULL REFERENCES users(id),
    ADD COLUMN archived_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN archived_by_user_id BIGINT REFERENCES users(id),
    ADD CONSTRAINT chk_cohorts_archive_actor
        CHECK ((archived_at IS NULL) = (archived_by_user_id IS NULL));

CREATE INDEX idx_cohorts_created_by_user_id ON cohorts(created_by_user_id);
CREATE INDEX idx_cohorts_archived_by_user_id ON cohorts(archived_by_user_id);

ALTER TABLE patient_cohort_memberships
    ADD COLUMN ended_by_user_id BIGINT REFERENCES users(id);
ALTER TABLE patient_expert_assignments
    ADD COLUMN ended_by_user_id BIGINT REFERENCES users(id);
ALTER TABLE cohort_staff_assignments
    ADD COLUMN ended_by_user_id BIGINT REFERENCES users(id);

CREATE INDEX idx_pcm_ended_by_user_id ON patient_cohort_memberships(ended_by_user_id);
CREATE INDEX idx_pea_ended_by_user_id ON patient_expert_assignments(ended_by_user_id);
CREATE INDEX idx_csa_ended_by_user_id ON cohort_staff_assignments(ended_by_user_id);
