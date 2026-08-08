package com.metabion.config;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class OracleMigrationContentTest {

    private static final Pattern BIGSERIAL = token("BIGSERIAL");
    private static final Pattern BYTEA = token("BYTEA");
    private static final Pattern TIMESTAMPTZ = token("TIMESTAMPTZ");
    private static final Pattern NOW_FUNCTION = Pattern.compile("\\bNOW\\s*\\(", Pattern.CASE_INSENSITIVE);
    private static final Pattern ON_CONFLICT = Pattern.compile("\\bON\\s+CONFLICT\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern CONSTRAINT_TRIGGER = Pattern.compile(
            "\\bCREATE\\s+CONSTRAINT\\s+TRIGGER\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern PARTIAL_INDEX = Pattern.compile(
            "\\bCREATE\\s+(?:UNIQUE\\s+)?INDEX\\b[^;]*\\bWHERE\\b[^;]*;",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private final PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();

    @Test
    void migrationsDoNotContainBigserial() throws IOException {
        assertNoMigrationContains(BIGSERIAL, "BIGSERIAL");
    }

    @Test
    void migrationsDoNotContainBytea() throws IOException {
        assertNoMigrationContains(BYTEA, "BYTEA");
    }

    @Test
    void migrationsDoNotContainTimestamptz() throws IOException {
        assertNoMigrationContains(TIMESTAMPTZ, "TIMESTAMPTZ");
    }

    @Test
    void migrationsDoNotContainNowFunction() throws IOException {
        assertNoMigrationContains(NOW_FUNCTION, "NOW()");
    }

    @Test
    void migrationsDoNotContainOnConflict() throws IOException {
        assertNoMigrationContains(ON_CONFLICT, "ON CONFLICT");
    }

    @Test
    void migrationsDoNotContainConstraintTriggers() throws IOException {
        assertNoMigrationContains(CONSTRAINT_TRIGGER, "CREATE CONSTRAINT TRIGGER");
    }

    @Test
    void migrationsDoNotContainPartialIndexes() throws IOException {
        assertNoMigrationContains(PARTIAL_INDEX, "a partial-index WHERE clause");
    }

    private void assertNoMigrationContains(Pattern prohibitedSyntax, String description) throws IOException {
        Resource[] migrations = resolver.getResources("classpath*:db/migration/oracle/V*.sql");
        assertThat(migrations).as("Oracle migration resources").hasSize(21);

        Arrays.stream(migrations).forEach(migration -> {
            String sql;
            try {
                sql = migration.getContentAsString(StandardCharsets.UTF_8);
            } catch (IOException exception) {
                throw new AssertionError("Could not read Oracle migration " + migration.getFilename(), exception);
            }

            assertThat(prohibitedSyntax.matcher(sql).find())
                    .as("Oracle migration %s must not contain %s", migration.getFilename(), description)
                    .isFalse();
        });
    }

    private static Pattern token(String token) {
        return Pattern.compile("\\b" + token + "\\b", Pattern.CASE_INSENSITIVE);
    }
}
