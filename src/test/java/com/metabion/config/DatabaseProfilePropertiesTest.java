package com.metabion.config;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertySource;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;

class DatabaseProfilePropertiesTest {

    @Nested
    @SpringBootTest(properties = {
            "spring.flyway.enabled=false",
            "spring.jpa.hibernate.ddl-auto=none",
            "spring.datasource.url=jdbc:h2:mem:database_profile_test;DB_CLOSE_DELAY=-1",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
            "spring.autoconfigure.exclude=org.springframework.boot.session.jdbc.autoconfigure.JdbcSessionAutoConfiguration"
    })
    @ActiveProfiles("postgresql")
    class PostgreSqlProfile {

        @Autowired
        ConfigurableEnvironment environment;

        @MockitoBean
        FindByIndexNameSessionRepository<Session> sessions;

        @Test
        void selectsPostgreSqlVendorDriverDialectAndMigrations() {
            PropertySource<?> profileProperties = profileProperties("application-postgresql.properties");

            assertThat(environment.getProperty("metabion.database")).isEqualTo("postgresql");
            assertThat(profileProperties.getProperty("spring.flyway.locations"))
                    .isEqualTo("classpath:db/migration/postgresql");
            assertThat(profileProperties.getProperty("spring.datasource.driver-class-name"))
                    .isEqualTo("org.postgresql.Driver");
            assertThat(profileProperties.getProperty("spring.jpa.properties.hibernate.dialect"))
                    .isEqualTo("org.hibernate.dialect.PostgreSQLDialect");
        }

        private PropertySource<?> profileProperties(String resourceName) {
            return environment.getPropertySources().stream()
                    .filter(propertySource -> propertySource.getName().contains(resourceName))
                    .findFirst()
                    .orElseThrow();
        }
    }

    @Nested
    @SpringBootTest(properties = {
            "spring.flyway.enabled=false",
            "spring.jpa.hibernate.ddl-auto=none",
            "spring.datasource.url=jdbc:h2:mem:database_profile_test;DB_CLOSE_DELAY=-1",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
            "spring.autoconfigure.exclude=org.springframework.boot.session.jdbc.autoconfigure.JdbcSessionAutoConfiguration"
    })
    @ActiveProfiles("oracle")
    class OracleProfile {

        @Autowired
        ConfigurableEnvironment environment;

        @MockitoBean
        FindByIndexNameSessionRepository<Session> sessions;

        @Test
        void selectsOracleVendorDriverAndMigrationsWithoutExplicitHibernateDialect() {
            PropertySource<?> profileProperties = environment.getPropertySources().stream()
                    .filter(propertySource -> propertySource.getName().contains("application-oracle.properties"))
                    .findFirst()
                    .orElseThrow();

            assertThat(environment.getProperty("metabion.database")).isEqualTo("oracle");
            assertThat(profileProperties.getProperty("spring.flyway.locations"))
                    .isEqualTo("classpath:db/migration/oracle");
            assertThat(profileProperties.getProperty("spring.datasource.driver-class-name"))
                    .isEqualTo("oracle.jdbc.OracleDriver");
            assertThat(profileProperties.containsProperty("spring.jpa.properties.hibernate.dialect")).isFalse();
        }
    }

    @Nested
    @SpringBootTest(properties = {
            "spring.flyway.enabled=false",
            "spring.jpa.hibernate.ddl-auto=none",
            "spring.datasource.url=jdbc:h2:mem:database_profile_prod_test;DB_CLOSE_DELAY=-1",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
            "spring.autoconfigure.exclude=org.springframework.boot.session.jdbc.autoconfigure.JdbcSessionAutoConfiguration"
    })
    @ActiveProfiles("prod")
    class ProductionProfile {

        @Autowired
        ConfigurableEnvironment environment;

        @MockitoBean
        FindByIndexNameSessionRepository<Session> sessions;

        @Test
        void activatesPostgreSqlProfileThroughProductionProfileGroup() {
            assertThat(environment.getActiveProfiles())
                    .contains("prod", "postgresql");
            PropertySource<?> profileProperties = profileProperties();

            assertThat(profileProperties.getProperty("spring.flyway.locations"))
                    .isEqualTo("classpath:db/migration/postgresql");
            assertThat(profileProperties.getProperty("spring.datasource.driver-class-name"))
                    .isEqualTo("org.postgresql.Driver");
            assertThat(profileProperties.getProperty("spring.jpa.properties.hibernate.dialect"))
                    .isEqualTo("org.hibernate.dialect.PostgreSQLDialect");
        }

        private PropertySource<?> profileProperties() {
            return environment.getPropertySources().stream()
                    .filter(propertySource -> propertySource.getName().contains("application-postgresql.properties"))
                    .findFirst()
                    .orElseThrow();
        }
    }

    @Nested
    @SpringBootTest(properties = {
            "spring.flyway.enabled=false",
            "spring.jpa.hibernate.ddl-auto=none",
            "spring.datasource.url=jdbc:h2:mem:database_profile_dev_test;DB_CLOSE_DELAY=-1",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
            "spring.autoconfigure.exclude=org.springframework.boot.session.jdbc.autoconfigure.JdbcSessionAutoConfiguration"
    })
    @ActiveProfiles("dev")
    class DevelopmentProfile {

        @Autowired
        ConfigurableEnvironment environment;

        @MockitoBean
        FindByIndexNameSessionRepository<Session> sessions;

        @Test
        void activatesPostgreSqlProfileThroughDevelopmentProfileGroup() {
            assertThat(environment.getActiveProfiles())
                    .contains("dev", "postgresql");
            PropertySource<?> profileProperties = profileProperties();

            assertThat(profileProperties.getProperty("spring.flyway.locations"))
                    .isEqualTo("classpath:db/migration/postgresql");
            assertThat(profileProperties.getProperty("spring.datasource.driver-class-name"))
                    .isEqualTo("org.postgresql.Driver");
            assertThat(profileProperties.getProperty("spring.jpa.properties.hibernate.dialect"))
                    .isEqualTo("org.hibernate.dialect.PostgreSQLDialect");
        }

        private PropertySource<?> profileProperties() {
            return environment.getPropertySources().stream()
                    .filter(propertySource -> propertySource.getName().contains("application-postgresql.properties"))
                    .findFirst()
                    .orElseThrow();
        }
    }
}
