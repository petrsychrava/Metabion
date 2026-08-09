package com.metabion.config;

import com.metabion.repository.EducationLessonCompletionInsertPort;
import com.metabion.repository.OracleEducationLessonCompletionInsertAdapter;
import com.metabion.repository.PostgresqlEducationLessonCompletionInsertAdapter;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class DatabaseConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(DatabaseConfiguration.class, JdbcTemplateTestConfiguration.class);

    @Test
    void selectsPostgresqlCompletionInsertAdapter() {
        contextRunner.withPropertyValues("metabion.database=postgresql").run(context ->
                assertThat(context.getBean(EducationLessonCompletionInsertPort.class))
                        .isInstanceOf(PostgresqlEducationLessonCompletionInsertAdapter.class));
    }

    @Test
    void selectsOracleCompletionInsertAdapter() {
        contextRunner.withPropertyValues("metabion.database=oracle").run(context ->
                assertThat(context.getBean(EducationLessonCompletionInsertPort.class))
                        .isInstanceOf(OracleEducationLessonCompletionInsertAdapter.class));
    }

    @Configuration(proxyBeanMethods = false)
    static class JdbcTemplateTestConfiguration {
        @Bean
        NamedParameterJdbcTemplate namedParameterJdbcTemplate() {
            return mock(NamedParameterJdbcTemplate.class);
        }
    }
}
