ALTER TABLE cohorts ADD (
    created_by_user_id NUMBER(19) NOT NULL REFERENCES users(id),
    archived_at TIMESTAMP WITH TIME ZONE,
    archived_by_user_id NUMBER(19) REFERENCES users(id),
    CONSTRAINT chk_cohorts_archive_actor
        CHECK ((archived_at IS NULL AND archived_by_user_id IS NULL)
            OR (archived_at IS NOT NULL AND archived_by_user_id IS NOT NULL))
);

CREATE INDEX idx_cohorts_created_by_user_id ON cohorts(created_by_user_id);
CREATE INDEX idx_cohorts_archived_by_user_id ON cohorts(archived_by_user_id);

ALTER TABLE patient_cohort_memberships
    ADD ended_by_user_id NUMBER(19) REFERENCES users(id);
ALTER TABLE patient_expert_assignments
    ADD ended_by_user_id NUMBER(19) REFERENCES users(id);
ALTER TABLE cohort_staff_assignments
    ADD ended_by_user_id NUMBER(19) REFERENCES users(id);

CREATE INDEX idx_pcm_ended_by_user_id ON patient_cohort_memberships(ended_by_user_id);
CREATE INDEX idx_pea_ended_by_user_id ON patient_expert_assignments(ended_by_user_id);
CREATE INDEX idx_csa_ended_by_user_id ON cohort_staff_assignments(ended_by_user_id);
