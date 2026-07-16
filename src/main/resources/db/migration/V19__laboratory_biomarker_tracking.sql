CREATE TABLE lab_test_definitions (
    id BIGSERIAL PRIMARY KEY,
    code VARCHAR(64) NOT NULL UNIQUE,
    label_key VARCHAR(120) NOT NULL UNIQUE,
    category VARCHAR(40) NOT NULL,
    canonical_unit VARCHAR(40) NOT NULL,
    display_scale SMALLINT NOT NULL CHECK (display_scale BETWEEN 0 AND 6),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    sort_order INTEGER NOT NULL
);

CREATE TABLE lab_test_unit_definitions (
    id BIGSERIAL PRIMARY KEY,
    test_definition_id BIGINT NOT NULL REFERENCES lab_test_definitions(id) ON DELETE CASCADE,
    unit_code VARCHAR(40) NOT NULL,
    conversion_type VARCHAR(24) NOT NULL,
    multiplier NUMERIC(24, 12) NOT NULL CHECK (multiplier > 0),
    sort_order INTEGER NOT NULL,
    UNIQUE (test_definition_id, unit_code)
);

CREATE TABLE lab_result_sets (
    id BIGSERIAL PRIMARY KEY,
    patient_profile_id BIGINT NOT NULL REFERENCES patient_profiles(id),
    collection_date DATE NOT NULL,
    notes VARCHAR(2000),
    source VARCHAR(32) NOT NULL,
    confirmation_status VARCHAR(32) NOT NULL,
    created_by_user_id BIGINT NOT NULL REFERENCES users(id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    removed_at TIMESTAMP WITH TIME ZONE,
    removed_by_user_id BIGINT REFERENCES users(id),
    removal_reason VARCHAR(500),
    CONSTRAINT chk_lab_result_set_removal CHECK (
        (removed_at IS NULL AND removed_by_user_id IS NULL AND removal_reason IS NULL)
        OR (removed_at IS NOT NULL AND removed_by_user_id IS NOT NULL)
    )
);

CREATE TABLE lab_results (
    id BIGSERIAL PRIMARY KEY,
    result_set_id BIGINT NOT NULL REFERENCES lab_result_sets(id) ON DELETE CASCADE,
    test_definition_id BIGINT NOT NULL REFERENCES lab_test_definitions(id),
    reported_value NUMERIC(18, 6) NOT NULL CHECK (reported_value >= 0),
    reported_unit VARCHAR(40) NOT NULL,
    canonical_value NUMERIC(18, 6) NOT NULL CHECK (canonical_value >= 0),
    canonical_unit VARCHAR(40) NOT NULL,
    reference_lower NUMERIC(18, 6) CHECK (reference_lower >= 0),
    reference_upper NUMERIC(18, 6) CHECK (reference_upper >= 0),
    UNIQUE (result_set_id, test_definition_id),
    CONSTRAINT chk_lab_result_reference CHECK (
        reference_lower IS NULL OR reference_upper IS NULL OR reference_lower <= reference_upper
    )
);

CREATE TABLE lab_result_audit_events (
    id BIGSERIAL PRIMARY KEY,
    result_set_id BIGINT NOT NULL REFERENCES lab_result_sets(id),
    patient_profile_id BIGINT NOT NULL REFERENCES patient_profiles(id),
    action VARCHAR(20) NOT NULL,
    actor_user_id BIGINT NOT NULL REFERENCES users(id),
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
    before_snapshot TEXT,
    after_snapshot TEXT,
    CONSTRAINT chk_lab_audit_snapshots CHECK (
        (action = 'CREATE' AND before_snapshot IS NULL AND after_snapshot IS NOT NULL)
        OR (action IN ('UPDATE', 'REMOVE') AND before_snapshot IS NOT NULL AND after_snapshot IS NOT NULL)
    )
);

CREATE INDEX idx_lab_result_sets_patient_date
    ON lab_result_sets(patient_profile_id, collection_date DESC) WHERE removed_at IS NULL;
CREATE INDEX idx_lab_results_definition ON lab_results(test_definition_id, result_set_id);
CREATE INDEX idx_lab_audit_set_time ON lab_result_audit_events(result_set_id, occurred_at);

INSERT INTO lab_test_definitions
    (code, label_key, category, canonical_unit, display_scale, active, sort_order)
VALUES
 ('CRP','lab.test.crp','INFLAMMATION','mg/L',2,TRUE,10),
 ('FECAL_CALPROTECTIN','lab.test.fecalCalprotectin','INFLAMMATION','ug/g',0,TRUE,20),
 ('HEMOGLOBIN','lab.test.hemoglobin','HEMATOLOGY','g/L',1,TRUE,30),
 ('FERRITIN','lab.test.ferritin','HEMATOLOGY','ug/L',1,TRUE,40),
 ('VITAMIN_D_25_OH','lab.test.vitaminD25Oh','NUTRITION','ng/mL',1,TRUE,50),
 ('VITAMIN_B12','lab.test.vitaminB12','NUTRITION','pg/mL',0,TRUE,60),
 ('ALBUMIN','lab.test.albumin','NUTRITION','g/L',1,TRUE,70),
 ('SODIUM','lab.test.sodium','ELECTROLYTE','mmol/L',1,TRUE,80),
 ('POTASSIUM','lab.test.potassium','ELECTROLYTE','mmol/L',2,TRUE,90),
 ('CHLORIDE','lab.test.chloride','ELECTROLYTE','mmol/L',1,TRUE,100),
 ('MAGNESIUM','lab.test.magnesium','ELECTROLYTE','mmol/L',2,TRUE,110),
 ('CALCIUM','lab.test.calcium','ELECTROLYTE','mmol/L',2,TRUE,120),
 ('ALT','lab.test.alt','LIVER','U/L',0,TRUE,130),
 ('AST','lab.test.ast','LIVER','U/L',0,TRUE,140),
 ('ALP','lab.test.alp','LIVER','U/L',0,TRUE,150),
 ('GGT','lab.test.ggt','LIVER','U/L',0,TRUE,160),
 ('BILIRUBIN_TOTAL','lab.test.bilirubinTotal','LIVER','umol/L',1,TRUE,170),
 ('CREATININE','lab.test.creatinine','KIDNEY','umol/L',1,TRUE,180),
 ('EGFR','lab.test.egfr','KIDNEY','mL/min/1.73m2',0,TRUE,190),
 ('UREA','lab.test.urea','KIDNEY','mmol/L',2,TRUE,200);

INSERT INTO lab_test_unit_definitions
    (test_definition_id, unit_code, conversion_type, multiplier, sort_order)
SELECT id, canonical_unit, 'IDENTITY', 1, 0 FROM lab_test_definitions;

INSERT INTO lab_test_unit_definitions
    (test_definition_id, unit_code, conversion_type, multiplier, sort_order)
SELECT id,'mg/dL','MULTIPLY',10,1 FROM lab_test_definitions WHERE code='CRP'
UNION ALL SELECT id,'g/dL','MULTIPLY',10,1 FROM lab_test_definitions WHERE code='HEMOGLOBIN'
UNION ALL SELECT id,'ng/mL','IDENTITY',1,1 FROM lab_test_definitions WHERE code='FERRITIN'
UNION ALL SELECT id,'ug/L','IDENTITY',1,1 FROM lab_test_definitions WHERE code='VITAMIN_D_25_OH'
UNION ALL SELECT id,'ng/L','IDENTITY',1,1 FROM lab_test_definitions WHERE code='VITAMIN_B12'
UNION ALL SELECT id,'g/dL','MULTIPLY',10,1 FROM lab_test_definitions WHERE code='ALBUMIN';
