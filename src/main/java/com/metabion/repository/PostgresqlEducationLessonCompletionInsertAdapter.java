package com.metabion.repository;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

public class PostgresqlEducationLessonCompletionInsertAdapter implements EducationLessonCompletionInsertPort {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public PostgresqlEducationLessonCompletionInsertAdapter(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public int insertCompletionIfAbsent(Long patientProfileId, Long moduleVersionId, Long lessonVersionId) {
        return jdbcTemplate.update("""
                INSERT INTO education_lesson_completions(patient_profile_id, module_version_id, lesson_version_id)
                VALUES (:patientProfileId, :moduleVersionId, :lessonVersionId)
                ON CONFLICT ON CONSTRAINT ux_education_lesson_completions_patient_lesson DO NOTHING
                """, parameters(patientProfileId, moduleVersionId, lessonVersionId));
    }

    private MapSqlParameterSource parameters(Long patientProfileId, Long moduleVersionId, Long lessonVersionId) {
        return new MapSqlParameterSource()
                .addValue("patientProfileId", patientProfileId)
                .addValue("moduleVersionId", moduleVersionId)
                .addValue("lessonVersionId", lessonVersionId);
    }
}
