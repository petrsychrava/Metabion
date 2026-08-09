ALTER TABLE daily_diet_log_photo_references
    MODIFY (daily_diet_log_id NULL);

ALTER TABLE daily_diet_log_photo_references ADD (
    patient_profile_id NUMBER(19),
    uploaded_by_user_id NUMBER(19),
    status VARCHAR2(20) DEFAULT 'PENDING' NOT NULL,
    sha256 VARCHAR2(64),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
    attached_at TIMESTAMP WITH TIME ZONE,
    removed_at TIMESTAMP WITH TIME ZONE,
    removed_by_user_id NUMBER(19)
);

ALTER TABLE daily_diet_log_photo_references MODIFY (
    patient_profile_id NOT NULL,
    uploaded_by_user_id NOT NULL,
    sha256 NOT NULL,
    storage_key NOT NULL
);

ALTER TABLE daily_diet_log_photo_references
    ADD CONSTRAINT fk_daily_diet_log_photo_references_log_patient
    FOREIGN KEY (daily_diet_log_id, patient_profile_id)
    REFERENCES daily_diet_logs(id, patient_profile_id) ON DELETE CASCADE;

ALTER TABLE daily_diet_log_photo_references
    ADD CONSTRAINT fk_daily_diet_log_photo_references_patient
    FOREIGN KEY (patient_profile_id) REFERENCES patient_profiles(id) ON DELETE CASCADE;

ALTER TABLE daily_diet_log_photo_references
    ADD CONSTRAINT fk_daily_diet_log_photo_references_uploaded_by
    FOREIGN KEY (uploaded_by_user_id) REFERENCES users(id);

ALTER TABLE daily_diet_log_photo_references
    ADD CONSTRAINT fk_daily_diet_log_photo_references_removed_by
    FOREIGN KEY (removed_by_user_id) REFERENCES users(id);

ALTER TABLE daily_diet_log_photo_references
    ADD CONSTRAINT chk_daily_diet_log_photo_references_status
    CHECK (status IN ('PENDING', 'ATTACHED', 'REMOVED'));

ALTER TABLE daily_diet_log_photo_references
    ADD CONSTRAINT chk_daily_diet_log_photo_references_sha256
    CHECK (length(sha256) = 64);

ALTER TABLE daily_diet_log_photo_references
    ADD CONSTRAINT chk_daily_diet_log_photo_references_attached_state
    CHECK (
        (
            status = 'PENDING'
            AND daily_diet_log_id IS NULL
            AND attached_at IS NULL
            AND removed_at IS NULL
            AND removed_by_user_id IS NULL
        )
        OR (
            status = 'ATTACHED'
            AND daily_diet_log_id IS NOT NULL
            AND attached_at IS NOT NULL
            AND removed_at IS NULL
            AND removed_by_user_id IS NULL
        )
        OR (
            status = 'REMOVED'
            AND daily_diet_log_id IS NOT NULL
            AND attached_at IS NOT NULL
            AND removed_at IS NOT NULL
            AND removed_by_user_id IS NOT NULL
        )
    );

CREATE INDEX ix_daily_diet_log_photo_references_patient_status
    ON daily_diet_log_photo_references(patient_profile_id, status);

CREATE INDEX ix_daily_diet_log_photo_references_pending_created
    ON daily_diet_log_photo_references(status, created_at);
