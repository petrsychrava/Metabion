package com.metabion.repository;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

public class OracleEducationLessonCompletionInsertAdapter implements EducationLessonCompletionInsertPort {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public OracleEducationLessonCompletionInsertAdapter(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public int insertCompletionIfAbsent(Long patientProfileId, Long moduleVersionId, Long lessonVersionId) {
        return jdbcTemplate.update("""
                MERGE INTO education_lesson_completions target
                USING (
                    SELECT :patientProfileId patient_profile_id,
                           :moduleVersionId module_version_id,
                           :lessonVersionId lesson_version_id
                    FROM dual
                ) source
                ON (
                    target.patient_profile_id = source.patient_profile_id
                    AND target.lesson_version_id = source.lesson_version_id
                )
                WHEN NOT MATCHED THEN
                    INSERT (
                        patient_profile_id, module_version_id, lesson_version_id
                    )
                    VALUES (
                        source.patient_profile_id,
                        source.module_version_id,
                        source.lesson_version_id
                    )
                """, parameters(patientProfileId, moduleVersionId, lessonVersionId));
    }

    private MapSqlParameterSource parameters(Long patientProfileId, Long moduleVersionId, Long lessonVersionId) {
        return new MapSqlParameterSource()
                .addValue("patientProfileId", patientProfileId)
                .addValue("moduleVersionId", moduleVersionId)
                .addValue("lessonVersionId", lessonVersionId);
    }
}
