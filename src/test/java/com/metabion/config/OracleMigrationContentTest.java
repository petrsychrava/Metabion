package com.metabion.config;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.regex.MatchResult;
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
    private static final Pattern RESOURCE_REFERENCE = Pattern.compile(
            "(?i)(?<![A-Za-z0-9_\"])(\"?resource\"?)(?![A-Za-z0-9_\"])");
    private static final Pattern PATIENT_PROFILE_ROLE_ASSERTION = Pattern.compile(
            "\\bCREATE\\s+ASSERTION\\s+assert_patient_profile_has_role\\s+CHECK\\s*\\("
                    + ".*?FROM\\s+patient_profiles\\s+pp"
                    + ".*?r\\.patient_profile\\s*=\\s*TRUE"
                    + ".*?\\)\\s*DEFERRABLE\\s+INITIALLY\\s+DEFERRED\\s*;",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern STAFF_PROFILE_ROLE_ASSERTION = Pattern.compile(
            "\\bCREATE\\s+ASSERTION\\s+assert_staff_profile_has_role\\s+CHECK\\s*\\("
                    + ".*?FROM\\s+staff_profiles\\s+sp"
                    + ".*?r\\.clinical_staff\\s*=\\s*TRUE"
                    + ".*?\\)\\s*DEFERRABLE\\s+INITIALLY\\s+DEFERRED\\s*;",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern USER_ROLES_COMPOUND_TRIGGER = Pattern.compile(
            "\\bCREATE(?:\\s+OR\\s+REPLACE)?\\s+TRIGGER\\b"
                    + ".*?\\bON\\s+user_roles\\b.*?\\bCOMPOUND\\s+TRIGGER\\b",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern CREATE_ASSERTION = Pattern.compile(
            "\\bCREATE\\s+ASSERTION\\b.*?;",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern ANSI_JOIN = token("JOIN");

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

    @Test
    void resourceColumnsUseLowercaseQuotedIdentifier() throws IOException {
        assertResourceReferencesQuoted("V15__mcp_oauth_authorization.sql", 5);
        assertResourceReferencesQuoted("V17__oauth_client_capabilities.sql", 1);
        assertResourceReferencesQuoted("V22__clinical_mcp_token_storage.sql", 1);
    }

    @Test
    void rbacMigrationUsesDeferredAssertionsInsteadOfUserRolesCompoundTrigger() throws IOException {
        Resource migration = oracleMigration("V4__rbac_assignment_model.sql");
        String sql = migration.getContentAsString(StandardCharsets.UTF_8);

        assertThat(PATIENT_PROFILE_ROLE_ASSERTION.matcher(sql).results().count())
                .as("Oracle migration %s patient-profile role assertion", migration.getFilename())
                .isEqualTo(1);
        assertThat(STAFF_PROFILE_ROLE_ASSERTION.matcher(sql).results().count())
                .as("Oracle migration %s staff-profile role assertion", migration.getFilename())
                .isEqualTo(1);
        assertThat(Pattern.compile("\\bCREATE\\s+ASSERTION\\b", Pattern.CASE_INSENSITIVE)
                .matcher(sql).results().count())
                .as("Oracle migration %s assertion count", migration.getFilename())
                .isEqualTo(2);
        assertThat(USER_ROLES_COMPOUND_TRIGGER.matcher(sql).find())
                .as("Oracle migration %s must not use a user_roles compound trigger", migration.getFilename())
                .isFalse();
    }

    @Test
    void rbacAssertionsDoNotUseAnsiJoinSyntax() throws IOException {
        Resource migration = oracleMigration("V4__rbac_assignment_model.sql");
        String sql = migration.getContentAsString(StandardCharsets.UTF_8);
        var assertions = CREATE_ASSERTION.matcher(sql).results()
                .map(MatchResult::group)
                .toList();

        assertThat(assertions)
                .as("Oracle migration %s assertions", migration.getFilename())
                .hasSize(2)
                .allSatisfy(assertion -> assertThat(ANSI_JOIN.matcher(assertion).find())
                        .as("Oracle migration %s assertion must not use ANSI JOIN", migration.getFilename())
                        .isFalse());
    }

    @Test
    void patientProfileRoleIsCheckedByDeferredAssertionOnly() throws IOException {
        Resource migration = oracleMigration("V4__rbac_assignment_model.sql");
        String sql = migration.getContentAsString(StandardCharsets.UTF_8);

        assertThat(sql)
                .as("Oracle migration %s must not check patient roles with an immediate trigger", migration.getFilename())
                .doesNotContain("trg_patient_profiles_require_patient_role");
    }

    private void assertNoMigrationContains(Pattern prohibitedSyntax, String description) throws IOException {
        Resource[] migrations = resolver.getResources("classpath*:db/migration/oracle/V*.sql");
        assertThat(migrations).as("Oracle migration resources").hasSize(22);

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

    private void assertResourceReferencesQuoted(String filename, int expectedReferences) throws IOException {
        Resource migration = oracleMigration(filename);
        String sql = migration.getContentAsString(StandardCharsets.UTF_8);
        var references = RESOURCE_REFERENCE.matcher(sql).results()
                .map(result -> result.group(1))
                .toList();

        assertThat(references)
                .as("Oracle migration %s resource references", migration.getFilename())
                .hasSize(expectedReferences)
                .containsOnly("\"resource\"");
    }

    private Resource oracleMigration(String filename) {
        Resource migration = resolver.getResource("classpath:db/migration/oracle/" + filename);
        assertThat(migration.exists()).as("Oracle migration %s", filename).isTrue();
        return migration;
    }

    private static Pattern token(String token) {
        return Pattern.compile("\\b" + token + "\\b", Pattern.CASE_INSENSITIVE);
    }
}
