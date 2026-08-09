package com.metabion.repository;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EducationLessonCompletionInsertAdapterTest {

    @Test
    void postgresqlInsertUsesOnConflictAndReturnsJdbcUpdateCount() {
        var jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        when(jdbcTemplate.update(anyString(), any(MapSqlParameterSource.class))).thenReturn(1);

        var result = new PostgresqlEducationLessonCompletionInsertAdapter(jdbcTemplate)
                .insertCompletionIfAbsent(11L, 60L, 100L);

        assertThat(result).isEqualTo(1);
        assertStatementAndParameters(jdbcTemplate, "ON CONFLICT");
    }

    @Test
    void oracleInsertUsesMergeAndReturnsJdbcUpdateCount() {
        var jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        when(jdbcTemplate.update(anyString(), any(MapSqlParameterSource.class))).thenReturn(0);

        var result = new OracleEducationLessonCompletionInsertAdapter(jdbcTemplate)
                .insertCompletionIfAbsent(11L, 60L, 100L);

        assertThat(result).isZero();
        assertStatementAndParameters(jdbcTemplate, "MERGE");
    }

    @Test
    void oracleDuplicateKeyDuringConcurrentMergeReturnsZero() {
        var jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        when(jdbcTemplate.update(anyString(), any(MapSqlParameterSource.class)))
                .thenThrow(new DuplicateKeyException("concurrent completion"));

        var result = new OracleEducationLessonCompletionInsertAdapter(jdbcTemplate)
                .insertCompletionIfAbsent(11L, 60L, 100L);

        assertThat(result).isZero();
    }

    @Test
    void oracleRethrowsUnrelatedDataAccessException() {
        var jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        var failure = new DataAccessResourceFailureException("database unavailable");
        when(jdbcTemplate.update(anyString(), any(MapSqlParameterSource.class))).thenThrow(failure);

        assertThatThrownBy(() -> new OracleEducationLessonCompletionInsertAdapter(jdbcTemplate)
                .insertCompletionIfAbsent(11L, 60L, 100L))
                .isSameAs(failure);
    }

    private void assertStatementAndParameters(NamedParameterJdbcTemplate jdbcTemplate, String sqlFragment) {
        var sql = ArgumentCaptor.forClass(String.class);
        var parameters = ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbcTemplate).update(sql.capture(), parameters.capture());

        assertThat(sql.getValue()).contains(sqlFragment).contains("education_lesson_completions");
        assertThat(parameters.getValue().getValue("patientProfileId")).isEqualTo(11L);
        assertThat(parameters.getValue().getValue("moduleVersionId")).isEqualTo(60L);
        assertThat(parameters.getValue().getValue("lessonVersionId")).isEqualTo(100L);
    }
}
