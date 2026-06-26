CREATE TABLE symptom_questionnaires (
    id              BIGSERIAL PRIMARY KEY,
    stable_key      VARCHAR(120) NOT NULL UNIQUE,
    display_name    VARCHAR(200) NOT NULL,
    active          BOOLEAN NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_symptom_questionnaires_stable_key CHECK (length(trim(stable_key)) > 0),
    CONSTRAINT chk_symptom_questionnaires_display_name CHECK (length(trim(display_name)) > 0)
);

CREATE TABLE symptom_questionnaire_versions (
    id                  BIGSERIAL PRIMARY KEY,
    questionnaire_id    BIGINT NOT NULL REFERENCES symptom_questionnaires(id) ON DELETE CASCADE,
    version_number      INT NOT NULL,
    status              VARCHAR(40) NOT NULL,
    scoring_method      VARCHAR(40) NOT NULL,
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    published_at        TIMESTAMP WITH TIME ZONE,
    CONSTRAINT ux_symptom_questionnaire_versions_number UNIQUE (questionnaire_id, version_number),
    CONSTRAINT chk_symptom_questionnaire_versions_number CHECK (version_number > 0),
    CONSTRAINT chk_symptom_questionnaire_versions_status CHECK (status IN ('DRAFT', 'ACTIVE', 'RETIRED')),
    CONSTRAINT chk_symptom_questionnaire_versions_scoring CHECK (scoring_method IN ('SUM'))
);

CREATE TABLE symptom_questions (
    id                          BIGSERIAL PRIMARY KEY,
    questionnaire_version_id    BIGINT NOT NULL REFERENCES symptom_questionnaire_versions(id) ON DELETE CASCADE,
    stable_key                  VARCHAR(120) NOT NULL,
    label                       VARCHAR(500) NOT NULL,
    help_text                   VARCHAR(1000),
    answer_type                 VARCHAR(40) NOT NULL,
    required                    BOOLEAN NOT NULL DEFAULT TRUE,
    sort_order                  INT NOT NULL DEFAULT 0,
    score_weight                NUMERIC(8,2) NOT NULL DEFAULT 1.00,
    min_numeric_value           NUMERIC(8,2),
    max_numeric_value           NUMERIC(8,2),
    CONSTRAINT ux_symptom_questions_version_key UNIQUE (questionnaire_version_id, stable_key),
    CONSTRAINT chk_symptom_questions_key CHECK (length(trim(stable_key)) > 0),
    CONSTRAINT chk_symptom_questions_label CHECK (length(trim(label)) > 0),
    CONSTRAINT chk_symptom_questions_type CHECK (answer_type IN ('SINGLE_CHOICE', 'NUMERIC', 'TEXT')),
    CONSTRAINT chk_symptom_questions_sort_order CHECK (sort_order >= 0),
    CONSTRAINT chk_symptom_questions_weight CHECK (score_weight >= 0),
    CONSTRAINT chk_symptom_questions_numeric_range CHECK (
        min_numeric_value IS NULL
        OR max_numeric_value IS NULL
        OR min_numeric_value <= max_numeric_value
    )
);

CREATE TABLE symptom_question_options (
    id              BIGSERIAL PRIMARY KEY,
    question_id     BIGINT NOT NULL REFERENCES symptom_questions(id) ON DELETE CASCADE,
    stable_key      VARCHAR(120) NOT NULL,
    label           VARCHAR(300) NOT NULL,
    numeric_score   NUMERIC(8,2) NOT NULL DEFAULT 0.00,
    sort_order      INT NOT NULL DEFAULT 0,
    CONSTRAINT ux_symptom_question_options_question_key UNIQUE (question_id, stable_key),
    CONSTRAINT ux_symptom_question_options_id_question UNIQUE (id, question_id),
    CONSTRAINT chk_symptom_question_options_key CHECK (length(trim(stable_key)) > 0),
    CONSTRAINT chk_symptom_question_options_label CHECK (length(trim(label)) > 0),
    CONSTRAINT chk_symptom_question_options_sort_order CHECK (sort_order >= 0)
);

CREATE TABLE symptom_check_ins (
    id                          BIGSERIAL PRIMARY KEY,
    patient_profile_id          BIGINT NOT NULL REFERENCES patient_profiles(id) ON DELETE CASCADE,
    questionnaire_version_id    BIGINT NOT NULL REFERENCES symptom_questionnaire_versions(id),
    check_in_date               DATE NOT NULL,
    flare_state                 VARCHAR(40) NOT NULL,
    notes                       VARCHAR(1000),
    total_symptom_score         NUMERIC(8,2) NOT NULL DEFAULT 0.00,
    created_at                  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at                  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    CONSTRAINT ux_symptom_check_ins_patient_date
        UNIQUE (patient_profile_id, check_in_date),
    CONSTRAINT chk_symptom_check_ins_flare CHECK (flare_state IN ('NO_FLARE', 'SUSPECTED_FLARE', 'ACTIVE_FLARE')),
    CONSTRAINT chk_symptom_check_ins_score CHECK (total_symptom_score >= 0),
    CONSTRAINT chk_symptom_check_ins_notes CHECK (notes IS NULL OR length(trim(notes)) > 0)
);

CREATE TABLE symptom_check_in_answers (
    id                  BIGSERIAL PRIMARY KEY,
    check_in_id         BIGINT NOT NULL REFERENCES symptom_check_ins(id) ON DELETE CASCADE,
    question_id         BIGINT NOT NULL REFERENCES symptom_questions(id),
    option_id           BIGINT REFERENCES symptom_question_options(id),
    answer_text         VARCHAR(1000),
    answer_numeric      NUMERIC(8,2),
    numeric_score       NUMERIC(8,2) NOT NULL DEFAULT 0.00,
    CONSTRAINT ux_symptom_check_in_answers_check_question UNIQUE (check_in_id, question_id),
    CONSTRAINT fk_symptom_check_in_answers_option_question
        FOREIGN KEY (option_id, question_id)
        REFERENCES symptom_question_options(id, question_id),
    CONSTRAINT chk_symptom_check_in_answers_text CHECK (answer_text IS NULL OR length(trim(answer_text)) > 0),
    CONSTRAINT chk_symptom_check_in_answers_score CHECK (numeric_score >= 0),
    CONSTRAINT chk_symptom_check_in_answers_one_value CHECK (
        option_id IS NOT NULL
        OR answer_text IS NOT NULL
        OR answer_numeric IS NOT NULL
    )
);

CREATE INDEX ix_symptom_questionnaire_versions_active
    ON symptom_questionnaire_versions(questionnaire_id, status);
CREATE UNIQUE INDEX ux_symptom_questionnaire_versions_one_active
    ON symptom_questionnaire_versions(questionnaire_id)
    WHERE status = 'ACTIVE';
CREATE INDEX ix_symptom_questions_version_order
    ON symptom_questions(questionnaire_version_id, sort_order, id);
CREATE INDEX ix_symptom_question_options_question_order
    ON symptom_question_options(question_id, sort_order, id);
CREATE INDEX ix_symptom_check_ins_patient_date
    ON symptom_check_ins(patient_profile_id, check_in_date DESC);

INSERT INTO symptom_questionnaires (stable_key, display_name, active)
VALUES ('ibd-symptom-check-in', 'IBD symptom check-in', TRUE);

INSERT INTO symptom_questionnaire_versions (questionnaire_id, version_number, status, scoring_method, published_at)
SELECT id, 1, 'ACTIVE', 'SUM', NOW()
FROM symptom_questionnaires
WHERE stable_key = 'ibd-symptom-check-in';

INSERT INTO symptom_questions (
    questionnaire_version_id, stable_key, label, help_text, answer_type, required, sort_order,
    score_weight, min_numeric_value, max_numeric_value
)
SELECT version.id, question.stable_key, question.label, question.help_text, question.answer_type,
       TRUE, question.sort_order, 1.00, question.min_numeric_value, question.max_numeric_value
FROM symptom_questionnaire_versions version
JOIN symptom_questionnaires questionnaire ON questionnaire.id = version.questionnaire_id
CROSS JOIN (
    VALUES
        ('stool-frequency', 'Stool frequency', 'Number of stools in the last 24 hours', 'NUMERIC', 10, 0.00, 30.00),
        ('abdominal-pain', 'Abdominal pain', 'Pain severity in the last 24 hours', 'SINGLE_CHOICE', 20, NULL, NULL),
        ('blood-in-stool', 'Blood in stool', 'Blood observed in stool', 'SINGLE_CHOICE', 30, NULL, NULL),
        ('urgency', 'Urgency', 'Urgency severity in the last 24 hours', 'SINGLE_CHOICE', 40, NULL, NULL),
        ('general-wellbeing', 'General wellbeing', 'Overall wellbeing today', 'SINGLE_CHOICE', 50, NULL, NULL)
) AS question(stable_key, label, help_text, answer_type, sort_order, min_numeric_value, max_numeric_value)
WHERE questionnaire.stable_key = 'ibd-symptom-check-in'
  AND version.version_number = 1;

INSERT INTO symptom_question_options (question_id, stable_key, label, numeric_score, sort_order)
SELECT q.id, option.stable_key, option.label, option.numeric_score, option.sort_order
FROM symptom_questions q
JOIN symptom_questionnaire_versions version ON version.id = q.questionnaire_version_id
JOIN symptom_questionnaires questionnaire ON questionnaire.id = version.questionnaire_id
CROSS JOIN (
    VALUES
        ('none', 'None', 0.00, 10),
        ('mild', 'Mild', 1.00, 20),
        ('moderate', 'Moderate', 2.00, 30),
        ('severe', 'Severe', 3.00, 40)
) AS option(stable_key, label, numeric_score, sort_order)
WHERE questionnaire.stable_key = 'ibd-symptom-check-in'
  AND q.stable_key IN ('abdominal-pain', 'urgency');

INSERT INTO symptom_question_options (question_id, stable_key, label, numeric_score, sort_order)
SELECT q.id, option.stable_key, option.label, option.numeric_score, option.sort_order
FROM symptom_questions q
JOIN symptom_questionnaire_versions version ON version.id = q.questionnaire_version_id
JOIN symptom_questionnaires questionnaire ON questionnaire.id = version.questionnaire_id
CROSS JOIN (
    VALUES
        ('none', 'None', 0.00, 10),
        ('trace', 'Trace', 1.00, 20),
        ('visible', 'Visible', 2.00, 30),
        ('significant', 'Significant', 3.00, 40)
) AS option(stable_key, label, numeric_score, sort_order)
WHERE questionnaire.stable_key = 'ibd-symptom-check-in'
  AND q.stable_key = 'blood-in-stool';

INSERT INTO symptom_question_options (question_id, stable_key, label, numeric_score, sort_order)
SELECT q.id, option.stable_key, option.label, option.numeric_score, option.sort_order
FROM symptom_questions q
JOIN symptom_questionnaire_versions version ON version.id = q.questionnaire_version_id
JOIN symptom_questionnaires questionnaire ON questionnaire.id = version.questionnaire_id
CROSS JOIN (
    VALUES
        ('well', 'Well', 0.00, 10),
        ('slightly-unwell', 'Slightly unwell', 1.00, 20),
        ('unwell', 'Unwell', 2.00, 30),
        ('very-unwell', 'Very unwell', 3.00, 40)
) AS option(stable_key, label, numeric_score, sort_order)
WHERE questionnaire.stable_key = 'ibd-symptom-check-in'
  AND q.stable_key = 'general-wellbeing';
