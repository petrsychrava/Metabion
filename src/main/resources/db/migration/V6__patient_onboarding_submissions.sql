CREATE TABLE onboarding_submissions (
    id                          BIGSERIAL PRIMARY KEY,
    patient_profile_id          BIGINT NOT NULL REFERENCES patient_profiles(id) ON DELETE CASCADE,
    onboarding_context          VARCHAR(100) NOT NULL,
    version                     INT NOT NULL,
    created_at                  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    submitted_at                TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),

    diagnosis_type              VARCHAR(60) NOT NULL,
    diagnosis_year              INT,
    disease_location            VARCHAR(120),
    disease_behavior            VARCHAR(120),
    activity_estimate           VARCHAR(60) NOT NULL,

    current_medications         VARCHAR(1000),
    steroid_use                 VARCHAR(60) NOT NULL,
    advanced_therapy_exposure   VARCHAR(60) NOT NULL,
    medication_notes            VARCHAR(1000),

    labs_collected_at           DATE,
    crp_mg_l                    NUMERIC(7,2),
    fecal_calprotectin_ug_g     NUMERIC(8,2),
    hemoglobin_g_dl             NUMERIC(4,1),
    albumin_g_dl                NUMERIC(4,1),
    lab_notes                   VARCHAR(1000),

    review_status               VARCHAR(40) NOT NULL DEFAULT 'PENDING_REVIEW',
    reviewed_by_user_id         BIGINT REFERENCES users(id) ON DELETE SET NULL,
    reviewed_at                 TIMESTAMP WITH TIME ZONE,
    review_notes                VARCHAR(1000),

    CONSTRAINT ux_onboarding_submissions_patient_context_version
        UNIQUE (patient_profile_id, onboarding_context, version),
    CONSTRAINT chk_onboarding_submissions_version_positive
        CHECK (version > 0),
    CONSTRAINT chk_onboarding_submissions_context_not_blank
        CHECK (length(trim(onboarding_context)) > 0),
    CONSTRAINT chk_onboarding_submissions_diagnosis_type
        CHECK (diagnosis_type IN ('CROHNS_DISEASE', 'ULCERATIVE_COLITIS', 'IBD_UNCLASSIFIED')),
    CONSTRAINT chk_onboarding_submissions_activity
        CHECK (activity_estimate IN ('REMISSION', 'MILD', 'MODERATE', 'SEVERE', 'UNKNOWN')),
    CONSTRAINT chk_onboarding_submissions_steroid_use
        CHECK (steroid_use IN ('NONE', 'CURRENT', 'RECENT_LAST_3_MONTHS')),
    CONSTRAINT chk_onboarding_submissions_advanced_therapy
        CHECK (advanced_therapy_exposure IN ('NEVER_USED', 'CURRENT', 'PAST', 'UNKNOWN')),
    CONSTRAINT chk_onboarding_submissions_review_status
        CHECK (review_status IN ('PENDING_REVIEW', 'REVIEWED', 'NEEDS_FOLLOW_UP')),
    CONSTRAINT chk_onboarding_submissions_labs_date_required
        CHECK (
            labs_collected_at IS NOT NULL
            OR (crp_mg_l IS NULL AND fecal_calprotectin_ug_g IS NULL AND hemoglobin_g_dl IS NULL AND albumin_g_dl IS NULL)
        )
);

CREATE INDEX ix_onboarding_submissions_patient_context
    ON onboarding_submissions(patient_profile_id, onboarding_context, version DESC);

CREATE INDEX ix_onboarding_submissions_review_status
    ON onboarding_submissions(review_status);

CREATE INDEX ix_onboarding_submissions_reviewed_by
    ON onboarding_submissions(reviewed_by_user_id);
