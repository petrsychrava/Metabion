package com.metabion.config;

import com.metabion.domain.ClinicalAccessTokenScope;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class DatabaseMigrationLayoutTest {

    private static final Pattern MIGRATION_NAME = Pattern.compile("^V([0-9]+)__([A-Za-z0-9_]+)\\.sql$");
    private static final Map<Integer, String> EXPECTED_DESCRIPTIONS = Map.ofEntries(
            Map.entry(1, "init_users"),
            Map.entry(2, "verification_and_reset_tokens"),
            Map.entry(3, "spring_session"),
            Map.entry(4, "rbac_assignment_model"),
            Map.entry(5, "staff_invitations"),
            Map.entry(6, "patient_onboarding_submissions"),
            Map.entry(7, "user_theme_preference"),
            Map.entry(8, "user_language_preference"),
            Map.entry(9, "daily_diet_logs"),
            Map.entry(10, "diet_log_photo_storage"),
            Map.entry(11, "education_content_library"),
            Map.entry(12, "link_diet_log_deviations_to_meals"),
            Map.entry(13, "symptom_tracking"),
            Map.entry(14, "patient_access_tokens"),
            Map.entry(15, "mcp_oauth_authorization"),
            Map.entry(16, "oauth_dynamic_client_registration"),
            Map.entry(17, "oauth_client_capabilities"),
            Map.entry(18, "remove_food_category_from_diet_log_meals"),
            Map.entry(19, "laboratory_biomarker_tracking"),
            Map.entry(20, "cohort_assignment_management"),
            Map.entry(21, "red_flag_detection_foundation"),
            Map.entry(22, "clinical_mcp_token_storage"));

    private final PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();

    @Test
    void postgresqlMigrationsHaveExpectedInventoryAndLayout() throws IOException {
        assertExpectedInventory("postgresql");

        assertThat(resolver.getResources("classpath*:db/migration/*.sql")).isEmpty();
    }

    @Test
    void oracleMigrationsHaveExpectedInventoryAndLayout() throws IOException {
        assertExpectedInventory("oracle");
    }

    @Test
    void clinicalScopeTableConstraintMatchesAuthorityShape() throws IOException {
        assertClinicalScopeConstraint("postgresql", "scope VARCHAR(80) NOT NULL CHECK (scope LIKE 'clinician:%')");
        assertClinicalScopeConstraint("oracle", "scope VARCHAR2(80) NOT NULL CHECK (scope LIKE 'clinician:%')");

        assertThat(Arrays.stream(ClinicalAccessTokenScope.values())
                .map(ClinicalAccessTokenScope::authority)
                .toList())
                .as("clinical scope authorities persisted in clinical_access_token_scopes")
                .allSatisfy(authority -> assertThat(authority).startsWith("clinician:"))
                .doesNotContain(Arrays.stream(ClinicalAccessTokenScope.values())
                        .map(Enum::name)
                        .toArray(String[]::new));
    }

    private void assertExpectedInventory(String vendor) throws IOException {
        Resource[] migrations = resolver.getResources("classpath*:db/migration/" + vendor + "/V*.sql");

        var parsed = Arrays.stream(migrations)
                .map(Resource::getFilename)
                .map(filename -> {
                    Matcher matcher = MIGRATION_NAME.matcher(filename);
                    assertThat(matcher.matches()).as("migration filename %s", filename).isTrue();
                    return Map.entry(Integer.parseInt(matcher.group(1)), matcher.group(2));
                })
                .sorted(Map.Entry.comparingByKey())
                .toList();

        assertThat(parsed).hasSize(22);
        assertThat(parsed.stream().map(Map.Entry::getKey).toList())
                .containsExactlyElementsOf(EXPECTED_DESCRIPTIONS.keySet().stream().sorted().toList());
        parsed.forEach(entry -> assertThat(entry.getValue())
                .as("description for %s migration V%s", vendor, entry.getKey())
                .isEqualTo(EXPECTED_DESCRIPTIONS.get(entry.getKey())));
    }

    private void assertClinicalScopeConstraint(String vendor, String expectedPredicate) throws IOException {
        Resource migration = resolver.getResource(
                "classpath:db/migration/" + vendor + "/V22__clinical_mcp_token_storage.sql");
        assertThat(migration.exists()).as("%s V22 migration", vendor).isTrue();

        assertThat(migration.getContentAsString(StandardCharsets.UTF_8))
                .as("%s V22 clinical scope predicate", vendor)
                .contains(expectedPredicate);
    }
}
