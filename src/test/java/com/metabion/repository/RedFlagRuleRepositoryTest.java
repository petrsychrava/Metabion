package com.metabion.repository;

import com.metabion.domain.RedFlagComparisonOperator;
import com.metabion.domain.RedFlagRuleCondition;
import com.metabion.domain.RedFlagRuleStatus;
import com.metabion.domain.RedFlagRuleVersion;
import com.metabion.domain.RedFlagSeverity;
import com.metabion.domain.RedFlagSourceType;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static java.util.Map.entry;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class RedFlagRuleRepositoryTest {

    private static final String APPROVAL_REFERENCE =
            "MET-12 initial clinical baseline approved 2026-07-29";

    private static final Map<String, TriggerSpec> EXPECTED_RULES = Map.ofEntries(
            entry("SYM_SEVERE_ABDOMINAL_PAIN", symptom(RedFlagSeverity.EMERGENCY)),
            entry("SYM_SIGNIFICANT_BLEEDING", symptom(RedFlagSeverity.EMERGENCY)),
            entry("SYM_ACTIVE_FLARE", symptom(RedFlagSeverity.URGENT_REVIEW)),
            entry("SYM_HIGH_STOOL_FREQUENCY", symptom(RedFlagSeverity.URGENT_REVIEW)),
            entry("SYM_COMBINED_SEVERE_ACTIVITY", symptom(RedFlagSeverity.URGENT_REVIEW)),
            entry("SYM_SUSPECTED_FLARE", symptom(RedFlagSeverity.ROUTINE_REVIEW)),
            entry("SYM_MODERATE_DETERIORATION", symptom(RedFlagSeverity.ROUTINE_REVIEW)),
            entry("LAB_SODIUM_CRITICAL", lab(RedFlagSeverity.EMERGENCY)),
            entry("LAB_POTASSIUM_CRITICAL", lab(RedFlagSeverity.EMERGENCY)),
            entry("LAB_CRP_CRITICAL", lab(RedFlagSeverity.EMERGENCY)),
            entry("LAB_CRP_HIGH", lab(RedFlagSeverity.URGENT_REVIEW)),
            entry("LAB_CRP_SYMPTOM_CONTEXT", lab(RedFlagSeverity.URGENT_REVIEW)),
            entry("LAB_HEMOGLOBIN_CRITICAL_LOW", lab(RedFlagSeverity.URGENT_REVIEW)),
            entry("LAB_MAGNESIUM_CRITICAL_LOW", lab(RedFlagSeverity.URGENT_REVIEW)),
            entry("LAB_UREA_CRITICAL_HIGH", lab(RedFlagSeverity.URGENT_REVIEW)),
            entry("LAB_CREATININE_CRITICAL_HIGH", lab(RedFlagSeverity.URGENT_REVIEW)),
            entry("LAB_TRANSAMINASE_CRITICAL_HIGH", lab(RedFlagSeverity.URGENT_REVIEW)),
            entry("LAB_ALBUMIN_CRITICAL_LOW", lab(RedFlagSeverity.URGENT_REVIEW)),
            entry("LAB_CALPROTECTIN_HIGH", lab(RedFlagSeverity.URGENT_REVIEW)),
            entry("LAB_CRP_ELEVATED", lab(RedFlagSeverity.ROUTINE_REVIEW)),
            entry("LAB_ALBUMIN_LOW", lab(RedFlagSeverity.ROUTINE_REVIEW)),
            entry("LAB_HEMOGLOBIN_LOW_MALE", lab(RedFlagSeverity.ROUTINE_REVIEW)),
            entry("LAB_HEMOGLOBIN_LOW_FEMALE", lab(RedFlagSeverity.ROUTINE_REVIEW)),
            entry("LAB_CALPROTECTIN_BORDERLINE", lab(RedFlagSeverity.ROUTINE_REVIEW)));

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired RedFlagRuleVersionRepository versions;
    @Autowired RedFlagRuleTransitionRepository transitions;
    @Autowired EntityManager entityManager;

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Test
    void migrationSeedsExactlyOneApprovedActiveVersionForEveryRule() {
        var active = activeVersions();

        assertThat(active).hasSize(24);
        assertThat(active).extracting(version -> version.getRule().getStableKey())
                .containsExactlyElementsOf(EXPECTED_RULES.keySet().stream().sorted().toList());
        assertThat(active).allSatisfy(version -> {
            assertThat(version.getVersionNumber()).isEqualTo(1);
            assertThat(version.getStatus()).isEqualTo(RedFlagRuleStatus.ACTIVE);
            assertThat(version.getAuthorReference()).isEqualTo("MET-12");
            assertThat(version.getEvidenceReference()).isNotBlank();
            assertThat(version.getRationale()).isNotBlank();
            assertThat(version.getChangeSummary()).isNotBlank();
            assertThat(version.getApprovalReference()).isEqualTo(APPROVAL_REFERENCE);
            assertThat(version.getApprovedAt()).isNotNull();
            assertThat(version.getActivatedAt()).isNotNull();
            assertThat(version.getRetiredAt()).isNull();
        });
    }

    @Test
    void activeCatalogueHasExactTriggersSeveritiesAndLifecycleTransitions() {
        var active = activeVersions();
        var actualRules = active.stream().collect(java.util.stream.Collectors.toMap(
                version -> version.getRule().getStableKey(),
                version -> new TriggerSpec(version.getTriggerSource(), version.getSeverity())));

        assertThat(actualRules).isEqualTo(EXPECTED_RULES);
        assertThat(transitions.count()).isEqualTo(48);
        active.forEach(version -> assertThat(transitions
                .findByRuleVersionIdOrderByTransitionedAtAscIdAsc(version.getId()))
                .extracting(transition -> new StatusChange(
                        transition.getPreviousStatus(), transition.getNewStatus()))
                .containsExactly(
                        new StatusChange(null, RedFlagRuleStatus.DRAFT),
                        new StatusChange(RedFlagRuleStatus.DRAFT, RedFlagRuleStatus.ACTIVE)));
    }

    @Test
    void compoundSymptomRulesRetainAndOrGroupingAndExactBoundaries() {
        var combined = activeVersion("SYM_COMBINED_SEVERE_ACTIVITY");
        assertGroup(combined, 0,
                decimal(RedFlagSourceType.SYMPTOM_CHECK_IN, "symptom.stool_frequency", "GTE", "6", 0),
                text(RedFlagSourceType.SYMPTOM_CHECK_IN, "symptom.blood_in_stool", "visible", 0));
        assertGroup(combined, 1,
                decimal(RedFlagSourceType.SYMPTOM_CHECK_IN, "symptom.stool_frequency", "GTE", "6", 0),
                text(RedFlagSourceType.SYMPTOM_CHECK_IN, "symptom.abdominal_pain", "moderate", 0));
        assertGroup(combined, 2,
                decimal(RedFlagSourceType.SYMPTOM_CHECK_IN, "symptom.stool_frequency", "GTE", "6", 0),
                text(RedFlagSourceType.SYMPTOM_CHECK_IN, "symptom.general_wellbeing", "very-unwell", 0));

        var moderate = activeVersion("SYM_MODERATE_DETERIORATION");
        assertThat(moderate.getConditionGroups()).hasSize(4);
        assertGroup(moderate, 0,
                decimal(RedFlagSourceType.SYMPTOM_CHECK_IN, "symptom.stool_frequency", "GTE", "4", 0),
                decimal(RedFlagSourceType.SYMPTOM_CHECK_IN, "symptom.stool_frequency", "LTE", "5", 0));
    }

    @Test
    void compoundLabRulesRetainExactLowerAndUpperBoundaries() {
        assertGroup(activeVersion("LAB_CRP_HIGH"), 0,
                decimal(RedFlagSourceType.LAB_RESULT_SET, "lab.CRP", "GTE", "100", 0),
                decimal(RedFlagSourceType.LAB_RESULT_SET, "lab.CRP", "LT", "300", 0));
        assertGroup(activeVersion("LAB_CRP_ELEVATED"), 0,
                decimal(RedFlagSourceType.LAB_RESULT_SET, "lab.CRP", "GT", "45", 0),
                decimal(RedFlagSourceType.LAB_RESULT_SET, "lab.CRP", "LT", "100", 0));
        assertGroup(activeVersion("LAB_ALBUMIN_LOW"), 0,
                decimal(RedFlagSourceType.LAB_RESULT_SET, "lab.ALBUMIN", "GT", "10", 0),
                decimal(RedFlagSourceType.LAB_RESULT_SET, "lab.ALBUMIN", "LT", "30", 0));
        assertGroup(activeVersion("LAB_HEMOGLOBIN_LOW_MALE"), 0,
                decimal(RedFlagSourceType.LAB_RESULT_SET, "lab.HEMOGLOBIN", "GT", "70", 0),
                decimal(RedFlagSourceType.LAB_RESULT_SET, "lab.HEMOGLOBIN", "LTE", "130", 0),
                text(RedFlagSourceType.PATIENT_PROFILE, "patient.sex", "MALE", 0));
        assertGroup(activeVersion("LAB_HEMOGLOBIN_LOW_FEMALE"), 0,
                decimal(RedFlagSourceType.LAB_RESULT_SET, "lab.HEMOGLOBIN", "GT", "70", 0),
                decimal(RedFlagSourceType.LAB_RESULT_SET, "lab.HEMOGLOBIN", "LTE", "120", 0),
                text(RedFlagSourceType.PATIENT_PROFILE, "patient.sex", "FEMALE", 0));
        assertGroup(activeVersion("LAB_CALPROTECTIN_BORDERLINE"), 0,
                decimal(RedFlagSourceType.LAB_RESULT_SET, "lab.FECAL_CALPROTECTIN", "GTE", "100", 0),
                decimal(RedFlagSourceType.LAB_RESULT_SET, "lab.FECAL_CALPROTECTIN", "LTE", "250", 0));
    }

    @Test
    void crpSymptomContextUsesSevenOrderedOrGroupsAndSevenDaySymptomLookback() {
        var context = activeVersion("LAB_CRP_SYMPTOM_CONTEXT");
        assertThat(context.getConditionGroups()).hasSize(7);

        var expectedPatterns = List.of(
                List.of(text(RedFlagSourceType.SYMPTOM_CHECK_IN, "symptom.flare_state", "ACTIVE_FLARE", 7)),
                List.of(decimal(RedFlagSourceType.SYMPTOM_CHECK_IN, "symptom.stool_frequency", "GT", "8", 7)),
                List.of(
                        decimal(RedFlagSourceType.SYMPTOM_CHECK_IN, "symptom.stool_frequency", "GTE", "6", 7),
                        text(RedFlagSourceType.SYMPTOM_CHECK_IN, "symptom.blood_in_stool", "visible", 7)),
                List.of(
                        decimal(RedFlagSourceType.SYMPTOM_CHECK_IN, "symptom.stool_frequency", "GTE", "6", 7),
                        text(RedFlagSourceType.SYMPTOM_CHECK_IN, "symptom.abdominal_pain", "moderate", 7)),
                List.of(
                        decimal(RedFlagSourceType.SYMPTOM_CHECK_IN, "symptom.stool_frequency", "GTE", "6", 7),
                        text(RedFlagSourceType.SYMPTOM_CHECK_IN, "symptom.general_wellbeing", "very-unwell", 7)),
                List.of(text(RedFlagSourceType.SYMPTOM_CHECK_IN, "symptom.abdominal_pain", "severe", 7)),
                List.of(text(RedFlagSourceType.SYMPTOM_CHECK_IN, "symptom.blood_in_stool", "significant", 7)));

        for (int groupIndex = 0; groupIndex < expectedPatterns.size(); groupIndex++) {
            var expected = Stream.concat(Stream.of(
                    decimal(RedFlagSourceType.LAB_RESULT_SET, "lab.CRP", "GT", "45", 0),
                    decimal(RedFlagSourceType.LAB_RESULT_SET, "lab.CRP", "LT", "100", 0)),
                    expectedPatterns.get(groupIndex).stream()).toArray(ConditionSpec[]::new);
            assertGroup(context, groupIndex, expected);
        }
    }

    @Test
    void databaseRejectsSecondActiveVersionForOneRule() {
        assertConstraintViolation("""
                INSERT INTO red_flag_rule_versions (
                    rule_id, version_number, status, trigger_source, severity,
                    evidence_reference, rationale, author_reference, change_summary,
                    approval_reference, approved_at, activated_at, created_at)
                SELECT rule_id, 2, 'ACTIVE', trigger_source, severity,
                       evidence_reference, rationale, 'MET-12', 'duplicate active test',
                       'test approval', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
                FROM red_flag_rule_versions
                WHERE id = (SELECT MIN(id) FROM red_flag_rule_versions)
                """);
    }

    @Test
    void databaseRejectsDuplicateGroupOrderWithinVersion() {
        assertConstraintViolation("""
                INSERT INTO red_flag_rule_condition_groups (rule_version_id, stable_key, sort_order)
                SELECT id, 'DUPLICATE_ORDER', 1
                FROM red_flag_rule_versions
                WHERE id = (SELECT MIN(id) FROM red_flag_rule_versions)
                """);
    }

    @Test
    void databaseRejectsDuplicateConditionOrderWithinGroup() {
        assertConstraintViolation("""
                INSERT INTO red_flag_rule_conditions (
                    condition_group_id, source_type, fact_key, comparison_operator,
                    decimal_operand, text_operand, lookback_days, sort_order)
                SELECT id, 'SYMPTOM_CHECK_IN', 'symptom.stool_frequency', 'GT',
                       99, NULL, 0, 1
                FROM red_flag_rule_condition_groups
                WHERE id = (SELECT MIN(id) FROM red_flag_rule_condition_groups)
                """);
    }

    @Test
    void databaseRejectsNegativeConditionLookback() {
        assertConstraintViolation(conditionInsert("1", "NULL", -1, 99));
    }

    @Test
    void databaseRejectsConditionWithBothOperandTypes() {
        assertConstraintViolation(conditionInsert("1", "'one'", 0, 99));
    }

    @Test
    void databaseRejectsConditionWithNeitherOperandType() {
        assertConstraintViolation(conditionInsert("NULL", "NULL", 0, 99));
    }

    private List<RedFlagRuleVersion> activeVersions() {
        return Stream.concat(
                        versions.findByStatusAndTriggerSource(
                                RedFlagRuleStatus.ACTIVE, RedFlagSourceType.SYMPTOM_CHECK_IN).stream(),
                        versions.findByStatusAndTriggerSource(
                                RedFlagRuleStatus.ACTIVE, RedFlagSourceType.LAB_RESULT_SET).stream())
                .sorted(java.util.Comparator.comparing(version -> version.getRule().getStableKey()))
                .toList();
    }

    private RedFlagRuleVersion activeVersion(String stableKey) {
        return activeVersions().stream()
                .filter(version -> version.getRule().getStableKey().equals(stableKey))
                .findFirst()
                .orElseThrow();
    }

    private void assertGroup(RedFlagRuleVersion version, int groupIndex, ConditionSpec... expected) {
        var group = version.getConditionGroups().get(groupIndex);
        assertThat(group.getSortOrder()).isEqualTo(groupIndex + 1);
        assertThat(group.getConditions()).extracting(this::conditionSpec)
                .containsExactly(expected);
    }

    private ConditionSpec conditionSpec(RedFlagRuleCondition condition) {
        return new ConditionSpec(
                condition.getSourceType(), condition.getFactKey(), condition.getOperator().name(),
                condition.getDecimalOperand() == null
                        ? null : condition.getDecimalOperand().stripTrailingZeros().toPlainString(),
                condition.getTextOperand(), condition.getLookbackDays());
    }

    private void assertConstraintViolation(String sql) {
        var thrown = catchThrowable(() -> entityManager.createNativeQuery(sql).executeUpdate());
        assertThat(thrown).isInstanceOf(PersistenceException.class);
        assertThat(rootCause(thrown)).isInstanceOfSatisfying(PSQLException.class,
                cause -> assertThat(cause.getSQLState()).startsWith("23"));
    }

    private Throwable rootCause(Throwable throwable) {
        var cause = throwable;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }

    private String conditionInsert(String decimalOperand, String textOperand, int lookbackDays, int sortOrder) {
        return """
                INSERT INTO red_flag_rule_conditions (
                    condition_group_id, source_type, fact_key, comparison_operator,
                    decimal_operand, text_operand, lookback_days, sort_order)
                SELECT id, 'SYMPTOM_CHECK_IN', 'symptom.stool_frequency', 'EQ',
                       %s, %s, %d, %d
                FROM red_flag_rule_condition_groups
                WHERE id = (SELECT MIN(id) FROM red_flag_rule_condition_groups)
                """.formatted(decimalOperand, textOperand, lookbackDays, sortOrder);
    }

    private static TriggerSpec symptom(RedFlagSeverity severity) {
        return new TriggerSpec(RedFlagSourceType.SYMPTOM_CHECK_IN, severity);
    }

    private static TriggerSpec lab(RedFlagSeverity severity) {
        return new TriggerSpec(RedFlagSourceType.LAB_RESULT_SET, severity);
    }

    private static ConditionSpec decimal(
            RedFlagSourceType sourceType, String factKey, String operator,
            String decimalOperand, int lookbackDays) {
        return new ConditionSpec(sourceType, factKey, operator, decimalOperand, null, lookbackDays);
    }

    private static ConditionSpec text(
            RedFlagSourceType sourceType, String factKey, String textOperand, int lookbackDays) {
        return new ConditionSpec(sourceType, factKey, RedFlagComparisonOperator.EQ.name(),
                null, textOperand, lookbackDays);
    }

    private record TriggerSpec(RedFlagSourceType sourceType, RedFlagSeverity severity) { }

    private record StatusChange(RedFlagRuleStatus previous, RedFlagRuleStatus next) { }

    private record ConditionSpec(
            RedFlagSourceType sourceType, String factKey, String operator,
            String decimalOperand, String textOperand, int lookbackDays) { }
}
