ALTER TABLE patient_profiles
    ADD COLUMN glucose_unit_preference VARCHAR(20) NOT NULL DEFAULT 'MMOL_L',
    ADD CONSTRAINT chk_patient_profiles_glucose_unit_preference
        CHECK (glucose_unit_preference IN ('MMOL_L', 'MG_DL'));

CREATE TABLE daily_diet_logs (
    id                  BIGSERIAL PRIMARY KEY,
    patient_profile_id  BIGINT NOT NULL REFERENCES patient_profiles(id) ON DELETE CASCADE,
    log_date            DATE NOT NULL,
    adherence_level     VARCHAR(40) NOT NULL,
    appetite_level      VARCHAR(40) NOT NULL,
    notes               VARCHAR(1000),
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),

    CONSTRAINT ux_daily_diet_logs_patient_date
        UNIQUE (patient_profile_id, log_date),
    CONSTRAINT chk_daily_diet_logs_adherence_level
        CHECK (adherence_level IN ('FULL', 'MOSTLY', 'PARTIAL', 'LOW', 'NOT_FOLLOWED')),
    CONSTRAINT chk_daily_diet_logs_appetite_level
        CHECK (appetite_level IN ('LOW', 'NORMAL', 'HIGH', 'VARIABLE')),
    CONSTRAINT chk_daily_diet_logs_notes
        CHECK (notes IS NULL OR length(notes) <= 1000)
);

CREATE TABLE daily_diet_log_meals (
    id                  BIGSERIAL PRIMARY KEY,
    daily_diet_log_id   BIGINT NOT NULL REFERENCES daily_diet_logs(id) ON DELETE CASCADE,
    meal_type           VARCHAR(40) NOT NULL,
    food_category       VARCHAR(60) NOT NULL,
    food_description    VARCHAR(1000) NOT NULL,
    notes               VARCHAR(1000),
    sort_order          INT NOT NULL DEFAULT 0,

    CONSTRAINT chk_daily_diet_log_meals_meal_type
        CHECK (meal_type IN ('BREAKFAST', 'LUNCH', 'DINNER', 'SNACK', 'DRINK', 'OTHER')),
    CONSTRAINT chk_daily_diet_log_meals_food_category
        CHECK (food_category IN (
            'FATS',
            'PROTEIN',
            'LOW_CARB_VEGETABLES',
            'DAIRY',
            'NUTS_SEEDS',
            'FERMENTED_FOODS',
            'BEVERAGES',
            'SUPPLEMENTS',
            'OTHER'
        )),
    CONSTRAINT chk_daily_diet_log_meals_sort_order
        CHECK (sort_order >= 0)
);

CREATE TABLE daily_diet_log_deviations (
    id                  BIGSERIAL PRIMARY KEY,
    daily_diet_log_id   BIGINT NOT NULL REFERENCES daily_diet_logs(id) ON DELETE CASCADE,
    deviation_category  VARCHAR(60) NOT NULL,
    severity            VARCHAR(40) NOT NULL,
    notes               VARCHAR(1000),
    sort_order          INT NOT NULL DEFAULT 0,

    CONSTRAINT chk_daily_diet_log_deviations_category
        CHECK (deviation_category IN (
            'EXCESS_CARBS',
            'NON_PROTOCOL_FOOD',
            'MISSED_MEAL',
            'DINING_OUT',
            'ALCOHOL',
            'GI_TOLERANCE',
            'OTHER'
        )),
    CONSTRAINT chk_daily_diet_log_deviations_severity
        CHECK (severity IN ('MINOR', 'MODERATE', 'MAJOR')),
    CONSTRAINT chk_daily_diet_log_deviations_sort_order
        CHECK (sort_order >= 0)
);

CREATE TABLE daily_diet_log_photo_references (
    id                  BIGSERIAL PRIMARY KEY,
    daily_diet_log_id   BIGINT NOT NULL REFERENCES daily_diet_logs(id) ON DELETE CASCADE,
    meal_id             BIGINT REFERENCES daily_diet_log_meals(id) ON DELETE SET NULL,
    original_filename   VARCHAR(255) NOT NULL,
    content_type        VARCHAR(120) NOT NULL,
    size_bytes          BIGINT,
    storage_key         VARCHAR(500) NOT NULL,
    caption             VARCHAR(500),
    sort_order          INT NOT NULL DEFAULT 0,

    CONSTRAINT chk_daily_diet_log_photo_references_size
        CHECK (size_bytes IS NULL OR size_bytes >= 0),
    CONSTRAINT chk_daily_diet_log_photo_references_sort_order
        CHECK (sort_order >= 0)
);

CREATE TABLE daily_measurement_entries (
    id                  BIGSERIAL PRIMARY KEY,
    patient_profile_id  BIGINT NOT NULL REFERENCES patient_profiles(id) ON DELETE CASCADE,
    daily_diet_log_id   BIGINT REFERENCES daily_diet_logs(id) ON DELETE SET NULL,
    measurement_type    VARCHAR(40) NOT NULL,
    value               NUMERIC(8,2) NOT NULL,
    unit                VARCHAR(20) NOT NULL,
    measured_at         TIMESTAMP WITH TIME ZONE NOT NULL,
    context             VARCHAR(40) NOT NULL,
    notes               VARCHAR(1000),
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_daily_measurement_entries_type
        CHECK (measurement_type IN ('KETONE', 'GLUCOSE')),
    CONSTRAINT chk_daily_measurement_entries_unit
        CHECK (unit IN ('MMOL_L', 'MG_DL')),
    CONSTRAINT chk_daily_measurement_entries_context
        CHECK (context IN ('FASTING', 'PRE_MEAL', 'POST_MEAL', 'BEDTIME', 'SYMPTOMS', 'OTHER')),
    CONSTRAINT chk_daily_measurement_entries_type_unit
        CHECK (
            (measurement_type = 'KETONE' AND unit = 'MMOL_L')
            OR (measurement_type = 'GLUCOSE' AND unit IN ('MMOL_L', 'MG_DL'))
        ),
    CONSTRAINT chk_daily_measurement_entries_value_range
        CHECK (
            (measurement_type = 'KETONE' AND unit = 'MMOL_L' AND value BETWEEN 0 AND 15)
            OR (measurement_type = 'GLUCOSE' AND unit = 'MMOL_L' AND value BETWEEN 1 AND 40)
            OR (measurement_type = 'GLUCOSE' AND unit = 'MG_DL' AND value BETWEEN 18 AND 720)
        )
);

CREATE INDEX ix_daily_diet_logs_patient_date
    ON daily_diet_logs(patient_profile_id, log_date DESC);

CREATE INDEX ix_daily_measurement_entries_patient_measured_at
    ON daily_measurement_entries(patient_profile_id, measured_at DESC);

CREATE INDEX ix_daily_measurement_entries_daily_diet_log
    ON daily_measurement_entries(daily_diet_log_id);
