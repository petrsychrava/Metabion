ALTER TABLE patient_access_tokens
    ADD COLUMN resource VARCHAR(255);

UPDATE patient_access_tokens
SET resource = 'http://localhost:8080/api/mcp'
WHERE resource IS NULL;

ALTER TABLE patient_access_tokens
    ALTER COLUMN resource SET NOT NULL;
