package com.metabion.config;

import com.metabion.repository.EducationLessonCompletionInsertPort;
import com.metabion.repository.OracleEducationLessonCompletionInsertAdapter;
import com.metabion.repository.PostgresqlEducationLessonCompletionInsertAdapter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DatabaseProperties.class)
public class DatabaseConfiguration {

    @Bean
    EducationLessonCompletionInsertPort educationLessonCompletionInsertPort(
            DatabaseProperties properties,
            NamedParameterJdbcTemplate jdbcTemplate) {
        return switch (properties.database()) {
            case POSTGRESQL -> new PostgresqlEducationLessonCompletionInsertAdapter(jdbcTemplate);
            case ORACLE -> new OracleEducationLessonCompletionInsertAdapter(jdbcTemplate);
        };
    }
}
