ALTER TABLE daily_diet_log_photo_references
    ALTER COLUMN daily_diet_log_id DROP NOT NULL,
    ADD COLUMN patient_profile_id BIGINT,
    ADD COLUMN uploaded_by_user_id BIGINT,
    ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    ADD COLUMN sha256 VARCHAR(64),
    ADD COLUMN created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    ADD COLUMN attached_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN removed_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN removed_by_user_id BIGINT;

UPDATE daily_diet_log_photo_references photo
SET patient_profile_id = log.patient_profile_id,
    uploaded_by_user_id = profile.user_id,
    status = CASE WHEN photo.daily_diet_log_id IS NULL THEN 'PENDING' ELSE 'ATTACHED' END,
    sha256 = repeat('0', 64),
    attached_at = CASE WHEN photo.daily_diet_log_id IS NULL THEN NULL ELSE NOW() END
FROM daily_diet_logs log
JOIN patient_profiles profile ON profile.id = log.patient_profile_id
WHERE photo.daily_diet_log_id = log.id;

ALTER TABLE daily_diet_log_photo_references
    ALTER COLUMN patient_profile_id SET NOT NULL,
    ALTER COLUMN uploaded_by_user_id SET NOT NULL,
    ALTER COLUMN sha256 SET NOT NULL,
    ALTER COLUMN storage_key SET NOT NULL,
    ADD CONSTRAINT fk_daily_diet_log_photo_references_log_patient
        FOREIGN KEY (daily_diet_log_id, patient_profile_id) REFERENCES daily_diet_logs(id, patient_profile_id) ON DELETE CASCADE,
    ADD CONSTRAINT fk_daily_diet_log_photo_references_patient
        FOREIGN KEY (patient_profile_id) REFERENCES patient_profiles(id) ON DELETE CASCADE,
    ADD CONSTRAINT fk_daily_diet_log_photo_references_uploaded_by
        FOREIGN KEY (uploaded_by_user_id) REFERENCES users(id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_daily_diet_log_photo_references_removed_by
        FOREIGN KEY (removed_by_user_id) REFERENCES users(id) ON DELETE RESTRICT,
    ADD CONSTRAINT chk_daily_diet_log_photo_references_status
        CHECK (status IN ('PENDING', 'ATTACHED', 'REMOVED')),
    ADD CONSTRAINT chk_daily_diet_log_photo_references_sha256
        CHECK (length(sha256) = 64),
    ADD CONSTRAINT chk_daily_diet_log_photo_references_attached_state
        CHECK (
            (status = 'PENDING' AND daily_diet_log_id IS NULL AND attached_at IS NULL)
            OR (status = 'ATTACHED' AND daily_diet_log_id IS NOT NULL AND attached_at IS NOT NULL)
            OR (
                status = 'REMOVED'
                AND daily_diet_log_id IS NOT NULL
                AND removed_at IS NOT NULL
                AND removed_by_user_id IS NOT NULL
            )
        );

CREATE INDEX ix_daily_diet_log_photo_references_patient_status
    ON daily_diet_log_photo_references(patient_profile_id, status);

CREATE INDEX ix_daily_diet_log_photo_references_pending_created
    ON daily_diet_log_photo_references(status, created_at);
