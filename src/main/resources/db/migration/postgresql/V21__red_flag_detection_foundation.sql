CREATE TABLE red_flag_rules (
    id BIGSERIAL PRIMARY KEY,
    stable_key VARCHAR(120) NOT NULL UNIQUE,
    display_name VARCHAR(200) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE red_flag_rule_versions (
    id BIGSERIAL PRIMARY KEY,
    rule_id BIGINT NOT NULL REFERENCES red_flag_rules(id),
    version_number INTEGER NOT NULL CHECK (version_number > 0),
    status VARCHAR(20) NOT NULL CHECK (status IN ('DRAFT', 'ACTIVE', 'RETIRED')),
    trigger_source VARCHAR(32) NOT NULL
        CHECK (trigger_source IN ('SYMPTOM_CHECK_IN', 'LAB_RESULT_SET')),
    severity VARCHAR(24) NOT NULL
        CHECK (severity IN ('ROUTINE_REVIEW', 'URGENT_REVIEW', 'EMERGENCY')),
    evidence_reference VARCHAR(1000) NOT NULL,
    rationale VARCHAR(2000) NOT NULL,
    author_reference VARCHAR(200) NOT NULL,
    change_summary VARCHAR(1000) NOT NULL,
    approval_reference VARCHAR(500),
    approved_at TIMESTAMP WITH TIME ZONE,
    activated_at TIMESTAMP WITH TIME ZONE,
    retired_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    UNIQUE (rule_id, version_number),
    CONSTRAINT chk_red_flag_active_approval CHECK (
        status <> 'ACTIVE'
        OR (approval_reference IS NOT NULL AND approved_at IS NOT NULL AND activated_at IS NOT NULL)
    )
);

CREATE UNIQUE INDEX uq_red_flag_rule_one_active_version
    ON red_flag_rule_versions(rule_id) WHERE status = 'ACTIVE';

CREATE TABLE red_flag_rule_condition_groups (
    id BIGSERIAL PRIMARY KEY,
    rule_version_id BIGINT NOT NULL REFERENCES red_flag_rule_versions(id),
    stable_key VARCHAR(120) NOT NULL,
    sort_order INTEGER NOT NULL CHECK (sort_order > 0),
    UNIQUE (rule_version_id, stable_key),
    UNIQUE (rule_version_id, sort_order),
    UNIQUE (id, rule_version_id)
);

CREATE TABLE red_flag_rule_conditions (
    id BIGSERIAL PRIMARY KEY,
    condition_group_id BIGINT NOT NULL REFERENCES red_flag_rule_condition_groups(id),
    source_type VARCHAR(32) NOT NULL
        CHECK (source_type IN ('SYMPTOM_CHECK_IN', 'LAB_RESULT_SET', 'PATIENT_PROFILE')),
    fact_key VARCHAR(160) NOT NULL,
    comparison_operator VARCHAR(8) NOT NULL CHECK (comparison_operator IN ('EQ', 'GT', 'GTE', 'LT', 'LTE')),
    decimal_operand NUMERIC(18, 6),
    text_operand VARCHAR(160),
    lookback_days INTEGER NOT NULL DEFAULT 0 CHECK (lookback_days >= 0),
    sort_order INTEGER NOT NULL CHECK (sort_order > 0),
    UNIQUE (condition_group_id, sort_order),
    CONSTRAINT chk_red_flag_condition_one_operand CHECK (
        (decimal_operand IS NOT NULL AND text_operand IS NULL)
        OR (decimal_operand IS NULL AND text_operand IS NOT NULL)
    )
);

CREATE TABLE red_flag_rule_transitions (
    id BIGSERIAL PRIMARY KEY,
    rule_version_id BIGINT NOT NULL REFERENCES red_flag_rule_versions(id),
    previous_status VARCHAR(20) CHECK (previous_status IN ('DRAFT', 'ACTIVE', 'RETIRED')),
    new_status VARCHAR(20) NOT NULL CHECK (new_status IN ('DRAFT', 'ACTIVE', 'RETIRED')),
    actor_reference VARCHAR(200) NOT NULL,
    transitioned_at TIMESTAMP WITH TIME ZONE NOT NULL,
    change_note VARCHAR(1000) NOT NULL,
    CONSTRAINT chk_red_flag_rule_transition_forward_only CHECK (
        (previous_status IS NULL AND new_status = 'DRAFT')
        OR (previous_status = 'DRAFT' AND new_status = 'ACTIVE')
        OR (previous_status = 'ACTIVE' AND new_status = 'RETIRED')
    )
);

CREATE INDEX idx_red_flag_rule_transitions_version_time
    ON red_flag_rule_transitions(rule_version_id, transitioned_at, id);

CREATE TABLE red_flag_evaluation_runs (
    id BIGSERIAL PRIMARY KEY,
    patient_profile_id BIGINT NOT NULL REFERENCES patient_profiles(id),
    source_type VARCHAR(32) NOT NULL
        CHECK (source_type IN ('SYMPTOM_CHECK_IN', 'LAB_RESULT_SET')),
    source_id BIGINT NOT NULL,
    source_operation VARCHAR(16) NOT NULL CHECK (source_operation IN ('UPSERT', 'REMOVE')),
    evaluated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    overall_severity VARCHAR(24)
        CHECK (overall_severity IN ('ROUTINE_REVIEW', 'URGENT_REVIEW', 'EMERGENCY')),
    is_current BOOLEAN NOT NULL DEFAULT FALSE,
    superseded_by_run_id BIGINT REFERENCES red_flag_evaluation_runs(id),
    CONSTRAINT chk_red_flag_run_supersession CHECK (
        superseded_by_run_id IS NULL OR is_current = FALSE
    )
);

CREATE UNIQUE INDEX uq_red_flag_evaluation_current_source
    ON red_flag_evaluation_runs(source_type, source_id) WHERE is_current;
CREATE INDEX idx_red_flag_evaluation_patient_time
    ON red_flag_evaluation_runs(patient_profile_id, evaluated_at DESC, id DESC);

CREATE TABLE red_flag_trigger_events (
    id BIGSERIAL PRIMARY KEY,
    evaluation_run_id BIGINT NOT NULL REFERENCES red_flag_evaluation_runs(id),
    rule_version_id BIGINT NOT NULL REFERENCES red_flag_rule_versions(id),
    matched_group_id BIGINT NOT NULL,
    severity VARCHAR(24) NOT NULL
        CHECK (severity IN ('ROUTINE_REVIEW', 'URGENT_REVIEW', 'EMERGENCY')),
    triggered_at TIMESTAMP WITH TIME ZONE NOT NULL,
    matched_inputs TEXT NOT NULL,
    UNIQUE (evaluation_run_id, rule_version_id),
    FOREIGN KEY (matched_group_id, rule_version_id)
        REFERENCES red_flag_rule_condition_groups(id, rule_version_id)
);

CREATE INDEX idx_red_flag_trigger_events_run_time
    ON red_flag_trigger_events(evaluation_run_id, triggered_at, id);

INSERT INTO red_flag_rules (stable_key, display_name, created_at)
VALUES
    ('SYM_SEVERE_ABDOMINAL_PAIN', 'Severe abdominal pain', '2026-07-29T09:00:00Z'),
    ('SYM_SIGNIFICANT_BLEEDING', 'Significant bleeding', '2026-07-29T09:00:00Z'),
    ('SYM_ACTIVE_FLARE', 'Active flare', '2026-07-29T09:00:00Z'),
    ('SYM_HIGH_STOOL_FREQUENCY', 'High stool frequency', '2026-07-29T09:00:00Z'),
    ('SYM_COMBINED_SEVERE_ACTIVITY', 'Combined severe symptom activity', '2026-07-29T09:00:00Z'),
    ('SYM_SUSPECTED_FLARE', 'Suspected flare', '2026-07-29T09:00:00Z'),
    ('SYM_MODERATE_DETERIORATION', 'Moderate symptom deterioration', '2026-07-29T09:00:00Z'),
    ('LAB_SODIUM_CRITICAL', 'Critical sodium', '2026-07-29T09:00:00Z'),
    ('LAB_POTASSIUM_CRITICAL', 'Critical potassium', '2026-07-29T09:00:00Z'),
    ('LAB_CRP_CRITICAL', 'Critical CRP', '2026-07-29T09:00:00Z'),
    ('LAB_CRP_HIGH', 'High CRP', '2026-07-29T09:00:00Z'),
    ('LAB_CRP_SYMPTOM_CONTEXT', 'Elevated CRP with symptom context', '2026-07-29T09:00:00Z'),
    ('LAB_HEMOGLOBIN_CRITICAL_LOW', 'Critically low haemoglobin', '2026-07-29T09:00:00Z'),
    ('LAB_MAGNESIUM_CRITICAL_LOW', 'Critically low magnesium', '2026-07-29T09:00:00Z'),
    ('LAB_UREA_CRITICAL_HIGH', 'Critically high urea', '2026-07-29T09:00:00Z'),
    ('LAB_CREATININE_CRITICAL_HIGH', 'Critically high creatinine', '2026-07-29T09:00:00Z'),
    ('LAB_TRANSAMINASE_CRITICAL_HIGH', 'Critically high transaminase', '2026-07-29T09:00:00Z'),
    ('LAB_ALBUMIN_CRITICAL_LOW', 'Critically low albumin', '2026-07-29T09:00:00Z'),
    ('LAB_CALPROTECTIN_HIGH', 'High faecal calprotectin', '2026-07-29T09:00:00Z'),
    ('LAB_CRP_ELEVATED', 'Elevated CRP', '2026-07-29T09:00:00Z'),
    ('LAB_ALBUMIN_LOW', 'Low albumin', '2026-07-29T09:00:00Z'),
    ('LAB_HEMOGLOBIN_LOW_MALE', 'Low haemoglobin for male profile', '2026-07-29T09:00:00Z'),
    ('LAB_HEMOGLOBIN_LOW_FEMALE', 'Low haemoglobin for female profile', '2026-07-29T09:00:00Z'),
    ('LAB_CALPROTECTIN_BORDERLINE', 'Borderline faecal calprotectin', '2026-07-29T09:00:00Z');

INSERT INTO red_flag_rule_versions (
    rule_id, version_number, status, trigger_source, severity,
    evidence_reference, rationale, author_reference, change_summary,
    approval_reference, approved_at, activated_at, retired_at, created_at)
SELECT
    rule.id,
    1,
    'ACTIVE',
    CASE WHEN rule.stable_key LIKE 'SYM_%' THEN 'SYMPTOM_CHECK_IN' ELSE 'LAB_RESULT_SET' END,
    CASE
        WHEN rule.stable_key IN (
            'SYM_SEVERE_ABDOMINAL_PAIN', 'SYM_SIGNIFICANT_BLEEDING',
            'LAB_SODIUM_CRITICAL', 'LAB_POTASSIUM_CRITICAL', 'LAB_CRP_CRITICAL') THEN 'EMERGENCY'
        WHEN rule.stable_key IN (
            'SYM_ACTIVE_FLARE', 'SYM_HIGH_STOOL_FREQUENCY', 'SYM_COMBINED_SEVERE_ACTIVITY',
            'LAB_CRP_HIGH', 'LAB_CRP_SYMPTOM_CONTEXT', 'LAB_HEMOGLOBIN_CRITICAL_LOW',
            'LAB_MAGNESIUM_CRITICAL_LOW', 'LAB_UREA_CRITICAL_HIGH',
            'LAB_CREATININE_CRITICAL_HIGH', 'LAB_TRANSAMINASE_CRITICAL_HIGH',
            'LAB_ALBUMIN_CRITICAL_LOW', 'LAB_CALPROTECTIN_HIGH') THEN 'URGENT_REVIEW'
        ELSE 'ROUTINE_REVIEW'
    END,
    CASE
        WHEN rule.stable_key IN ('SYM_SEVERE_ABDOMINAL_PAIN', 'SYM_SIGNIFICANT_BLEEDING')
            THEN 'https://www.nhs.uk/conditions/inflammatory-bowel-disease/'
        WHEN rule.stable_key LIKE 'SYM_%'
            THEN 'https://www.nice.org.uk/guidance/ng130/chapter/recommendations'
        WHEN rule.stable_key LIKE 'LAB_CALPROTECTIN_%'
            THEN 'https://www.ouh.nhs.uk/biochemistry/tests/tests-catalogue/calprotectin-faecal/'
        WHEN rule.stable_key LIKE 'LAB_HEMOGLOBIN_%'
            THEN 'https://www.gloshospitals.nhs.uk/our-services/services-we-offer/pathology/haematology/haematology-reference-ranges/'
        WHEN rule.stable_key IN ('LAB_CRP_HIGH', 'LAB_CRP_ELEVATED', 'LAB_CRP_SYMPTOM_CONTEXT')
            THEN 'https://www.nice.org.uk/advice/mib81/chapter/the-technology'
        WHEN rule.stable_key = 'LAB_CRP_CRITICAL'
            THEN 'https://sheffieldlaboratorymedicine.nhs.uk/search-test.php?search=3079'
        ELSE 'https://mft.nhs.uk/the-trust/other-departments/laboratory-medicine/information-for-gps/laboratory-medicines-newsletter-for-gps/the-communication-of-critical-biochemistry-results/'
    END,
    'Initial approved MET-12 threshold for ' || rule.display_name || '.',
    'MET-12',
    'Add the initial clinically approved red-flag rule version.',
    'MET-12 initial clinical baseline approved 2026-07-29',
    '2026-07-29T10:00:00Z',
    '2026-07-29T10:05:00Z',
    NULL,
    '2026-07-29T09:00:00Z'
FROM red_flag_rules rule;

WITH group_seed(rule_key, stable_key, sort_order) AS (
    VALUES
        ('SYM_SEVERE_ABDOMINAL_PAIN', 'G1', 1),
        ('SYM_SIGNIFICANT_BLEEDING', 'G1', 1),
        ('SYM_ACTIVE_FLARE', 'G1', 1),
        ('SYM_HIGH_STOOL_FREQUENCY', 'G1', 1),
        ('SYM_COMBINED_SEVERE_ACTIVITY', 'G1', 1),
        ('SYM_COMBINED_SEVERE_ACTIVITY', 'G2', 2),
        ('SYM_COMBINED_SEVERE_ACTIVITY', 'G3', 3),
        ('SYM_SUSPECTED_FLARE', 'G1', 1),
        ('SYM_MODERATE_DETERIORATION', 'G1', 1),
        ('SYM_MODERATE_DETERIORATION', 'G2', 2),
        ('SYM_MODERATE_DETERIORATION', 'G3', 3),
        ('SYM_MODERATE_DETERIORATION', 'G4', 4),
        ('LAB_SODIUM_CRITICAL', 'G1', 1),
        ('LAB_SODIUM_CRITICAL', 'G2', 2),
        ('LAB_POTASSIUM_CRITICAL', 'G1', 1),
        ('LAB_POTASSIUM_CRITICAL', 'G2', 2),
        ('LAB_CRP_CRITICAL', 'G1', 1),
        ('LAB_CRP_HIGH', 'G1', 1),
        ('LAB_CRP_SYMPTOM_CONTEXT', 'G1', 1),
        ('LAB_CRP_SYMPTOM_CONTEXT', 'G2', 2),
        ('LAB_CRP_SYMPTOM_CONTEXT', 'G3', 3),
        ('LAB_CRP_SYMPTOM_CONTEXT', 'G4', 4),
        ('LAB_CRP_SYMPTOM_CONTEXT', 'G5', 5),
        ('LAB_CRP_SYMPTOM_CONTEXT', 'G6', 6),
        ('LAB_CRP_SYMPTOM_CONTEXT', 'G7', 7),
        ('LAB_HEMOGLOBIN_CRITICAL_LOW', 'G1', 1),
        ('LAB_MAGNESIUM_CRITICAL_LOW', 'G1', 1),
        ('LAB_UREA_CRITICAL_HIGH', 'G1', 1),
        ('LAB_CREATININE_CRITICAL_HIGH', 'G1', 1),
        ('LAB_TRANSAMINASE_CRITICAL_HIGH', 'G1', 1),
        ('LAB_TRANSAMINASE_CRITICAL_HIGH', 'G2', 2),
        ('LAB_ALBUMIN_CRITICAL_LOW', 'G1', 1),
        ('LAB_CALPROTECTIN_HIGH', 'G1', 1),
        ('LAB_CRP_ELEVATED', 'G1', 1),
        ('LAB_ALBUMIN_LOW', 'G1', 1),
        ('LAB_HEMOGLOBIN_LOW_MALE', 'G1', 1),
        ('LAB_HEMOGLOBIN_LOW_FEMALE', 'G1', 1),
        ('LAB_CALPROTECTIN_BORDERLINE', 'G1', 1)
)
INSERT INTO red_flag_rule_condition_groups (rule_version_id, stable_key, sort_order)
SELECT version.id, seed.stable_key, seed.sort_order
FROM group_seed seed
JOIN red_flag_rules rule ON rule.stable_key = seed.rule_key
JOIN red_flag_rule_versions version ON version.rule_id = rule.id AND version.version_number = 1;

WITH condition_seed(
    rule_key, group_key, source_type, fact_key, comparison_operator,
    decimal_operand, text_operand, lookback_days, sort_order) AS (
    VALUES
        ('SYM_SEVERE_ABDOMINAL_PAIN', 'G1', 'SYMPTOM_CHECK_IN', 'symptom.abdominal_pain', 'EQ', NULL, 'severe', 0, 1),
        ('SYM_SIGNIFICANT_BLEEDING', 'G1', 'SYMPTOM_CHECK_IN', 'symptom.blood_in_stool', 'EQ', NULL, 'significant', 0, 1),
        ('SYM_ACTIVE_FLARE', 'G1', 'SYMPTOM_CHECK_IN', 'symptom.flare_state', 'EQ', NULL, 'ACTIVE_FLARE', 0, 1),
        ('SYM_HIGH_STOOL_FREQUENCY', 'G1', 'SYMPTOM_CHECK_IN', 'symptom.stool_frequency', 'GT', '8', NULL, 0, 1),
        ('SYM_COMBINED_SEVERE_ACTIVITY', 'G1', 'SYMPTOM_CHECK_IN', 'symptom.stool_frequency', 'GTE', '6', NULL, 0, 1),
        ('SYM_COMBINED_SEVERE_ACTIVITY', 'G1', 'SYMPTOM_CHECK_IN', 'symptom.blood_in_stool', 'EQ', NULL, 'visible', 0, 2),
        ('SYM_COMBINED_SEVERE_ACTIVITY', 'G2', 'SYMPTOM_CHECK_IN', 'symptom.stool_frequency', 'GTE', '6', NULL, 0, 1),
        ('SYM_COMBINED_SEVERE_ACTIVITY', 'G2', 'SYMPTOM_CHECK_IN', 'symptom.abdominal_pain', 'EQ', NULL, 'moderate', 0, 2),
        ('SYM_COMBINED_SEVERE_ACTIVITY', 'G3', 'SYMPTOM_CHECK_IN', 'symptom.stool_frequency', 'GTE', '6', NULL, 0, 1),
        ('SYM_COMBINED_SEVERE_ACTIVITY', 'G3', 'SYMPTOM_CHECK_IN', 'symptom.general_wellbeing', 'EQ', NULL, 'very-unwell', 0, 2),
        ('SYM_SUSPECTED_FLARE', 'G1', 'SYMPTOM_CHECK_IN', 'symptom.flare_state', 'EQ', NULL, 'SUSPECTED_FLARE', 0, 1),
        ('SYM_MODERATE_DETERIORATION', 'G1', 'SYMPTOM_CHECK_IN', 'symptom.stool_frequency', 'GTE', '4', NULL, 0, 1),
        ('SYM_MODERATE_DETERIORATION', 'G1', 'SYMPTOM_CHECK_IN', 'symptom.stool_frequency', 'LTE', '5', NULL, 0, 2),
        ('SYM_MODERATE_DETERIORATION', 'G2', 'SYMPTOM_CHECK_IN', 'symptom.blood_in_stool', 'EQ', NULL, 'visible', 0, 1),
        ('SYM_MODERATE_DETERIORATION', 'G3', 'SYMPTOM_CHECK_IN', 'symptom.abdominal_pain', 'EQ', NULL, 'moderate', 0, 1),
        ('SYM_MODERATE_DETERIORATION', 'G4', 'SYMPTOM_CHECK_IN', 'symptom.general_wellbeing', 'EQ', NULL, 'very-unwell', 0, 1),
        ('LAB_SODIUM_CRITICAL', 'G1', 'LAB_RESULT_SET', 'lab.SODIUM', 'LTE', '120', NULL, 0, 1),
        ('LAB_SODIUM_CRITICAL', 'G2', 'LAB_RESULT_SET', 'lab.SODIUM', 'GTE', '160', NULL, 0, 1),
        ('LAB_POTASSIUM_CRITICAL', 'G1', 'LAB_RESULT_SET', 'lab.POTASSIUM', 'LTE', '2.5', NULL, 0, 1),
        ('LAB_POTASSIUM_CRITICAL', 'G2', 'LAB_RESULT_SET', 'lab.POTASSIUM', 'GTE', '6.5', NULL, 0, 1),
        ('LAB_CRP_CRITICAL', 'G1', 'LAB_RESULT_SET', 'lab.CRP', 'GTE', '300', NULL, 0, 1),
        ('LAB_CRP_HIGH', 'G1', 'LAB_RESULT_SET', 'lab.CRP', 'GTE', '100', NULL, 0, 1),
        ('LAB_CRP_HIGH', 'G1', 'LAB_RESULT_SET', 'lab.CRP', 'LT', '300', NULL, 0, 2),
        ('LAB_CRP_SYMPTOM_CONTEXT', 'G1', 'LAB_RESULT_SET', 'lab.CRP', 'GT', '45', NULL, 0, 1),
        ('LAB_CRP_SYMPTOM_CONTEXT', 'G1', 'LAB_RESULT_SET', 'lab.CRP', 'LT', '100', NULL, 0, 2),
        ('LAB_CRP_SYMPTOM_CONTEXT', 'G1', 'SYMPTOM_CHECK_IN', 'symptom.flare_state', 'EQ', NULL, 'ACTIVE_FLARE', 7, 3),
        ('LAB_CRP_SYMPTOM_CONTEXT', 'G2', 'LAB_RESULT_SET', 'lab.CRP', 'GT', '45', NULL, 0, 1),
        ('LAB_CRP_SYMPTOM_CONTEXT', 'G2', 'LAB_RESULT_SET', 'lab.CRP', 'LT', '100', NULL, 0, 2),
        ('LAB_CRP_SYMPTOM_CONTEXT', 'G2', 'SYMPTOM_CHECK_IN', 'symptom.stool_frequency', 'GT', '8', NULL, 7, 3),
        ('LAB_CRP_SYMPTOM_CONTEXT', 'G3', 'LAB_RESULT_SET', 'lab.CRP', 'GT', '45', NULL, 0, 1),
        ('LAB_CRP_SYMPTOM_CONTEXT', 'G3', 'LAB_RESULT_SET', 'lab.CRP', 'LT', '100', NULL, 0, 2),
        ('LAB_CRP_SYMPTOM_CONTEXT', 'G3', 'SYMPTOM_CHECK_IN', 'symptom.stool_frequency', 'GTE', '6', NULL, 7, 3),
        ('LAB_CRP_SYMPTOM_CONTEXT', 'G3', 'SYMPTOM_CHECK_IN', 'symptom.blood_in_stool', 'EQ', NULL, 'visible', 7, 4),
        ('LAB_CRP_SYMPTOM_CONTEXT', 'G4', 'LAB_RESULT_SET', 'lab.CRP', 'GT', '45', NULL, 0, 1),
        ('LAB_CRP_SYMPTOM_CONTEXT', 'G4', 'LAB_RESULT_SET', 'lab.CRP', 'LT', '100', NULL, 0, 2),
        ('LAB_CRP_SYMPTOM_CONTEXT', 'G4', 'SYMPTOM_CHECK_IN', 'symptom.stool_frequency', 'GTE', '6', NULL, 7, 3),
        ('LAB_CRP_SYMPTOM_CONTEXT', 'G4', 'SYMPTOM_CHECK_IN', 'symptom.abdominal_pain', 'EQ', NULL, 'moderate', 7, 4),
        ('LAB_CRP_SYMPTOM_CONTEXT', 'G5', 'LAB_RESULT_SET', 'lab.CRP', 'GT', '45', NULL, 0, 1),
        ('LAB_CRP_SYMPTOM_CONTEXT', 'G5', 'LAB_RESULT_SET', 'lab.CRP', 'LT', '100', NULL, 0, 2),
        ('LAB_CRP_SYMPTOM_CONTEXT', 'G5', 'SYMPTOM_CHECK_IN', 'symptom.stool_frequency', 'GTE', '6', NULL, 7, 3),
        ('LAB_CRP_SYMPTOM_CONTEXT', 'G5', 'SYMPTOM_CHECK_IN', 'symptom.general_wellbeing', 'EQ', NULL, 'very-unwell', 7, 4),
        ('LAB_CRP_SYMPTOM_CONTEXT', 'G6', 'LAB_RESULT_SET', 'lab.CRP', 'GT', '45', NULL, 0, 1),
        ('LAB_CRP_SYMPTOM_CONTEXT', 'G6', 'LAB_RESULT_SET', 'lab.CRP', 'LT', '100', NULL, 0, 2),
        ('LAB_CRP_SYMPTOM_CONTEXT', 'G6', 'SYMPTOM_CHECK_IN', 'symptom.abdominal_pain', 'EQ', NULL, 'severe', 7, 3),
        ('LAB_CRP_SYMPTOM_CONTEXT', 'G7', 'LAB_RESULT_SET', 'lab.CRP', 'GT', '45', NULL, 0, 1),
        ('LAB_CRP_SYMPTOM_CONTEXT', 'G7', 'LAB_RESULT_SET', 'lab.CRP', 'LT', '100', NULL, 0, 2),
        ('LAB_CRP_SYMPTOM_CONTEXT', 'G7', 'SYMPTOM_CHECK_IN', 'symptom.blood_in_stool', 'EQ', NULL, 'significant', 7, 3),
        ('LAB_HEMOGLOBIN_CRITICAL_LOW', 'G1', 'LAB_RESULT_SET', 'lab.HEMOGLOBIN', 'LTE', '70', NULL, 0, 1),
        ('LAB_MAGNESIUM_CRITICAL_LOW', 'G1', 'LAB_RESULT_SET', 'lab.MAGNESIUM', 'LTE', '0.40', NULL, 0, 1),
        ('LAB_UREA_CRITICAL_HIGH', 'G1', 'LAB_RESULT_SET', 'lab.UREA', 'GTE', '30', NULL, 0, 1),
        ('LAB_CREATININE_CRITICAL_HIGH', 'G1', 'LAB_RESULT_SET', 'lab.CREATININE', 'GTE', '354', NULL, 0, 1),
        ('LAB_TRANSAMINASE_CRITICAL_HIGH', 'G1', 'LAB_RESULT_SET', 'lab.ALT', 'GTE', '500', NULL, 0, 1),
        ('LAB_TRANSAMINASE_CRITICAL_HIGH', 'G2', 'LAB_RESULT_SET', 'lab.AST', 'GTE', '500', NULL, 0, 1),
        ('LAB_ALBUMIN_CRITICAL_LOW', 'G1', 'LAB_RESULT_SET', 'lab.ALBUMIN', 'LTE', '10', NULL, 0, 1),
        ('LAB_CALPROTECTIN_HIGH', 'G1', 'LAB_RESULT_SET', 'lab.FECAL_CALPROTECTIN', 'GT', '250', NULL, 0, 1),
        ('LAB_CRP_ELEVATED', 'G1', 'LAB_RESULT_SET', 'lab.CRP', 'GT', '45', NULL, 0, 1),
        ('LAB_CRP_ELEVATED', 'G1', 'LAB_RESULT_SET', 'lab.CRP', 'LT', '100', NULL, 0, 2),
        ('LAB_ALBUMIN_LOW', 'G1', 'LAB_RESULT_SET', 'lab.ALBUMIN', 'GT', '10', NULL, 0, 1),
        ('LAB_ALBUMIN_LOW', 'G1', 'LAB_RESULT_SET', 'lab.ALBUMIN', 'LT', '30', NULL, 0, 2),
        ('LAB_HEMOGLOBIN_LOW_MALE', 'G1', 'LAB_RESULT_SET', 'lab.HEMOGLOBIN', 'GT', '70', NULL, 0, 1),
        ('LAB_HEMOGLOBIN_LOW_MALE', 'G1', 'LAB_RESULT_SET', 'lab.HEMOGLOBIN', 'LTE', '130', NULL, 0, 2),
        ('LAB_HEMOGLOBIN_LOW_MALE', 'G1', 'PATIENT_PROFILE', 'patient.sex', 'EQ', NULL, 'MALE', 0, 3),
        ('LAB_HEMOGLOBIN_LOW_FEMALE', 'G1', 'LAB_RESULT_SET', 'lab.HEMOGLOBIN', 'GT', '70', NULL, 0, 1),
        ('LAB_HEMOGLOBIN_LOW_FEMALE', 'G1', 'LAB_RESULT_SET', 'lab.HEMOGLOBIN', 'LTE', '120', NULL, 0, 2),
        ('LAB_HEMOGLOBIN_LOW_FEMALE', 'G1', 'PATIENT_PROFILE', 'patient.sex', 'EQ', NULL, 'FEMALE', 0, 3),
        ('LAB_CALPROTECTIN_BORDERLINE', 'G1', 'LAB_RESULT_SET', 'lab.FECAL_CALPROTECTIN', 'GTE', '100', NULL, 0, 1),
        ('LAB_CALPROTECTIN_BORDERLINE', 'G1', 'LAB_RESULT_SET', 'lab.FECAL_CALPROTECTIN', 'LTE', '250', NULL, 0, 2)
)
INSERT INTO red_flag_rule_conditions (
    condition_group_id, source_type, fact_key, comparison_operator,
    decimal_operand, text_operand, lookback_days, sort_order)
SELECT
    condition_group.id,
    seed.source_type,
    seed.fact_key,
    seed.comparison_operator,
    CAST(seed.decimal_operand AS NUMERIC(18, 6)),
    seed.text_operand,
    seed.lookback_days,
    seed.sort_order
FROM condition_seed seed
JOIN red_flag_rules rule ON rule.stable_key = seed.rule_key
JOIN red_flag_rule_versions version ON version.rule_id = rule.id AND version.version_number = 1
JOIN red_flag_rule_condition_groups condition_group
    ON condition_group.rule_version_id = version.id AND condition_group.stable_key = seed.group_key;

INSERT INTO red_flag_rule_transitions (
    rule_version_id, previous_status, new_status, actor_reference, transitioned_at, change_note)
SELECT id, NULL, 'DRAFT', 'MET-12', created_at, 'Initial rule version drafted.'
FROM red_flag_rule_versions
UNION ALL
SELECT id, 'DRAFT', 'ACTIVE', 'MET-12', activated_at, 'Initial clinical baseline approved and activated.'
FROM red_flag_rule_versions;

CREATE FUNCTION prevent_red_flag_rule_transition_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'Red-flag rule transitions are append-only';
END;
$$;

CREATE TRIGGER red_flag_rule_transitions_append_only
BEFORE UPDATE OR DELETE ON red_flag_rule_transitions
FOR EACH ROW EXECUTE FUNCTION prevent_red_flag_rule_transition_mutation();
