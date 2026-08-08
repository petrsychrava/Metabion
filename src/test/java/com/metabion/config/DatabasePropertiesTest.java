package com.metabion.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class DatabasePropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfig.class);

    @Test
    void bindsPostgresqlDatabaseProperty() {
        contextRunner
                .withPropertyValues("metabion.database=postgresql")
                .run(context -> {
                    assertThat(context.getBean(DatabaseProperties.class).database())
                            .isEqualTo(DatabaseVendor.POSTGRESQL);
                });
    }

    @Test
    void bindsOracleDatabaseProperty() {
        contextRunner
                .withPropertyValues("metabion.database=oracle")
                .run(context -> {
                    assertThat(context.getBean(DatabaseProperties.class).database())
                            .isEqualTo(DatabaseVendor.ORACLE);
                });
    }

    @Test
    void rejectsInvalidDatabaseProperty() {
        contextRunner
                .withPropertyValues("metabion.database=mysql")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure().getCause())
                            .isInstanceOf(BindException.class)
                            .hasStackTraceContaining("mysql");
                });
    }

    @EnableConfigurationProperties(DatabaseProperties.class)
    static class TestConfig {
    }
}
