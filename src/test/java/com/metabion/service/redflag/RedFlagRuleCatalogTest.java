package com.metabion.service.redflag;

import com.metabion.domain.RedFlagComparisonOperator;
import com.metabion.domain.RedFlagRuleCondition;
import com.metabion.domain.RedFlagRuleConditionGroup;
import com.metabion.domain.RedFlagRuleStatus;
import com.metabion.domain.RedFlagRuleVersion;
import com.metabion.domain.RedFlagSeverity;
import com.metabion.domain.RedFlagSourceType;
import com.metabion.repository.RedFlagRuleVersionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RedFlagRuleCatalogTest {

    private static final List<String> SYMPTOM_KEYS = List.of(
            "SYM_SEVERE_ABDOMINAL_PAIN", "SYM_SIGNIFICANT_BLEEDING", "SYM_ACTIVE_FLARE",
            "SYM_HIGH_STOOL_FREQUENCY", "SYM_COMBINED_SEVERE_ACTIVITY",
            "SYM_SUSPECTED_FLARE", "SYM_MODERATE_DETERIORATION");
    private static final List<String> LAB_KEYS = List.of(
            "LAB_SODIUM_CRITICAL", "LAB_POTASSIUM_CRITICAL", "LAB_CRP_CRITICAL",
            "LAB_CRP_HIGH", "LAB_CRP_SYMPTOM_CONTEXT", "LAB_HEMOGLOBIN_CRITICAL_LOW",
            "LAB_MAGNESIUM_CRITICAL_LOW", "LAB_UREA_CRITICAL_HIGH",
            "LAB_CREATININE_CRITICAL_HIGH", "LAB_TRANSAMINASE_CRITICAL_HIGH",
            "LAB_ALBUMIN_CRITICAL_LOW", "LAB_CALPROTECTIN_HIGH", "LAB_CRP_ELEVATED",
            "LAB_ALBUMIN_LOW", "LAB_HEMOGLOBIN_LOW_MALE", "LAB_HEMOGLOBIN_LOW_FEMALE",
            "LAB_CALPROTECTIN_BORDERLINE");

    private RedFlagRuleVersionRepository versions;
    private RedFlagRuleCatalog catalog;

    @BeforeEach
    void setUp() {
        versions = mock(RedFlagRuleVersionRepository.class);
        catalog = new RedFlagRuleCatalog(versions, new RedFlagFactRegistry());
    }

    @Test
    void mapsActiveRulesGroupsAndConditionsInStableOrder() {
        var rows = validRows(RedFlagSourceType.SYMPTOM_CHECK_IN);
        var orderedVersion = rows.getFirst();
        var late = group(12L, "G2", 2, List.of(
                condition(22L, RedFlagSourceType.SYMPTOM_CHECK_IN, "symptom.blood_in_stool",
                        RedFlagComparisonOperator.EQ, null, "visible", 0, 2),
                condition(21L, RedFlagSourceType.SYMPTOM_CHECK_IN, "symptom.stool_frequency",
                        RedFlagComparisonOperator.GTE, new BigDecimal("6.00"), null, 0, 1)));
        var early = group(11L, "G1", 1, List.of(
                condition(20L, RedFlagSourceType.SYMPTOM_CHECK_IN, "symptom.abdominal_pain",
                        RedFlagComparisonOperator.EQ, null, "severe", 0, 1)));
        when(orderedVersion.getConditionGroups()).thenReturn(List.of(late, early));
        rows.sort(Comparator.comparing(
                (RedFlagRuleVersion version) -> version.getRule().getStableKey()).reversed());
        when(versions.findByStatusAndTriggerSource(
                RedFlagRuleStatus.ACTIVE, RedFlagSourceType.SYMPTOM_CHECK_IN)).thenReturn(rows);

        var definitions = catalog.activeFor(RedFlagSourceType.SYMPTOM_CHECK_IN);

        assertThat(definitions).extracting(RedFlagRuleDefinition::ruleKey)
                .containsExactlyElementsOf(SYMPTOM_KEYS.stream().sorted().toList());
        var mapped = definitions.stream()
                .filter(definition -> definition.ruleKey().equals(SYMPTOM_KEYS.getFirst()))
                .findFirst().orElseThrow();
        assertThat(mapped.groups()).extracting(RedFlagRuleDefinition.Group::stableKey)
                .containsExactly("G1", "G2");
        assertThat(mapped.groups().get(1).conditions())
                .extracting(RedFlagRuleDefinition.Condition::factKey)
                .containsExactly("symptom.stool_frequency", "symptom.blood_in_stool");
    }

    @Test
    void acceptsPositiveSymptomLookbackOnlyForLabTriggeredRules() {
        var rows = validRows(RedFlagSourceType.LAB_RESULT_SET);
        var version = rows.stream()
                .filter(candidate -> candidate.getRule().getStableKey().equals("LAB_CRP_SYMPTOM_CONTEXT"))
                .findFirst().orElseThrow();
        replaceGroups(version, List.of(group(1L, "G1", 1, List.of(
                condition(1L, RedFlagSourceType.LAB_RESULT_SET, "lab.CRP",
                        RedFlagComparisonOperator.GT, new BigDecimal("45"), null, 0, 1),
                condition(2L, RedFlagSourceType.SYMPTOM_CHECK_IN, "symptom.flare_state",
                        RedFlagComparisonOperator.EQ, null, "ACTIVE_FLARE", 7, 2)))));
        when(versions.findByStatusAndTriggerSource(
                RedFlagRuleStatus.ACTIVE, RedFlagSourceType.LAB_RESULT_SET)).thenReturn(rows);

        assertThat(catalog.activeFor(RedFlagSourceType.LAB_RESULT_SET))
                .filteredOn(definition -> definition.ruleKey().equals("LAB_CRP_SYMPTOM_CONTEXT"))
                .singleElement()
                .satisfies(definition -> assertThat(definition.groups().getFirst().conditions().get(1).lookbackDays())
                        .isEqualTo(7));
    }

    @ParameterizedTest(name = "rejects invalid catalogue: {0}")
    @MethodSource("invalidCatalogues")
    void rejectsEveryInvalidCatalogueShape(String description, InvalidCatalogue invalid) {
        var rows = validRows(invalid.triggerSource());
        invalid.mutate().accept(rows);
        when(versions.findByStatusAndTriggerSource(RedFlagRuleStatus.ACTIVE, invalid.triggerSource()))
                .thenReturn(rows);

        assertThatThrownBy(() -> catalog.activeFor(invalid.triggerSource()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Active red-flag catalogue is invalid")
                .hasNoCause();
    }

    private static Stream<org.junit.jupiter.params.provider.Arguments> invalidCatalogues() {
        return Stream.of(
                invalid("missing approval", RedFlagSourceType.SYMPTOM_CHECK_IN,
                        rows -> when(rows.getFirst().getApprovalReference()).thenReturn(null)),
                invalid("blank approval", RedFlagSourceType.SYMPTOM_CHECK_IN,
                        rows -> when(rows.getFirst().getApprovalReference()).thenReturn("  ")),
                invalid("missing approval timestamp", RedFlagSourceType.SYMPTOM_CHECK_IN,
                        rows -> when(rows.getFirst().getApprovedAt()).thenReturn(null)),
                invalid("missing activation timestamp", RedFlagSourceType.SYMPTOM_CHECK_IN,
                        rows -> when(rows.getFirst().getActivatedAt()).thenReturn(null)),
                invalid("missing groups", RedFlagSourceType.SYMPTOM_CHECK_IN,
                        rows -> when(rows.getFirst().getConditionGroups()).thenReturn(null)),
                invalid("missing groups", RedFlagSourceType.SYMPTOM_CHECK_IN,
                        rows -> when(rows.getFirst().getConditionGroups()).thenReturn(List.of())),
                invalid("empty conditions", RedFlagSourceType.SYMPTOM_CHECK_IN,
                        rows -> replaceGroups(rows.getFirst(),
                                List.of(group(1L, "G1", 1, List.of())))),
                invalid("unknown source and key", RedFlagSourceType.SYMPTOM_CHECK_IN,
                        rows -> replaceCondition(rows.getFirst(), condition(1L, RedFlagSourceType.PATIENT_PROFILE,
                                "symptom.flare_state", RedFlagComparisonOperator.EQ,
                                null, "ACTIVE_FLARE", 0, 1))),
                invalid("unknown fact key", RedFlagSourceType.SYMPTOM_CHECK_IN,
                        rows -> replaceCondition(rows.getFirst(), condition(1L, RedFlagSourceType.SYMPTOM_CHECK_IN,
                                "symptom.notes", RedFlagComparisonOperator.EQ, null, "secret", 0, 1))),
                invalid("wrong decimal operand", RedFlagSourceType.SYMPTOM_CHECK_IN,
                        rows -> replaceCondition(rows.getFirst(), condition(1L, RedFlagSourceType.SYMPTOM_CHECK_IN,
                                "symptom.stool_frequency", RedFlagComparisonOperator.GTE,
                                null, "6", 0, 1))),
                invalid("wrong text operand", RedFlagSourceType.SYMPTOM_CHECK_IN,
                        rows -> replaceCondition(rows.getFirst(), condition(1L, RedFlagSourceType.SYMPTOM_CHECK_IN,
                                "symptom.flare_state", RedFlagComparisonOperator.EQ,
                                BigDecimal.ONE, null, 0, 1))),
                invalid("both operand types", RedFlagSourceType.SYMPTOM_CHECK_IN,
                        rows -> replaceCondition(rows.getFirst(), condition(1L, RedFlagSourceType.SYMPTOM_CHECK_IN,
                                "symptom.flare_state", RedFlagComparisonOperator.EQ,
                                BigDecimal.ONE, "ACTIVE_FLARE", 0, 1))),
                invalid("neither operand type", RedFlagSourceType.SYMPTOM_CHECK_IN,
                        rows -> replaceCondition(rows.getFirst(), condition(1L, RedFlagSourceType.SYMPTOM_CHECK_IN,
                                "symptom.flare_state", RedFlagComparisonOperator.EQ,
                                null, null, 0, 1))),
                invalid("non-EQ text operator", RedFlagSourceType.SYMPTOM_CHECK_IN,
                        rows -> replaceCondition(rows.getFirst(), condition(1L, RedFlagSourceType.SYMPTOM_CHECK_IN,
                                "symptom.flare_state", RedFlagComparisonOperator.GT,
                                null, "ACTIVE_FLARE", 0, 1))),
                invalid("negative lookback", RedFlagSourceType.SYMPTOM_CHECK_IN,
                        rows -> replaceCondition(rows.getFirst(), condition(1L, RedFlagSourceType.SYMPTOM_CHECK_IN,
                                "symptom.flare_state", RedFlagComparisonOperator.EQ,
                                null, "ACTIVE_FLARE", -1, 1))),
                invalid("positive lookback on symptom trigger", RedFlagSourceType.SYMPTOM_CHECK_IN,
                        rows -> replaceCondition(rows.getFirst(), condition(1L, RedFlagSourceType.SYMPTOM_CHECK_IN,
                                "symptom.flare_state", RedFlagComparisonOperator.EQ,
                                null, "ACTIVE_FLARE", 7, 1))),
                invalid("positive lookback on lab fact", RedFlagSourceType.LAB_RESULT_SET,
                        rows -> replaceCondition(rows.getFirst(), condition(1L, RedFlagSourceType.LAB_RESULT_SET,
                                "lab.CRP", RedFlagComparisonOperator.GT,
                                BigDecimal.ONE, null, 7, 1))),
                invalid("positive lookback on profile fact", RedFlagSourceType.LAB_RESULT_SET,
                        rows -> replaceCondition(rows.getFirst(), condition(1L, RedFlagSourceType.PATIENT_PROFILE,
                                "patient.sex", RedFlagComparisonOperator.EQ,
                                null, "MALE", 7, 1))),
                invalid("duplicate group order", RedFlagSourceType.SYMPTOM_CHECK_IN,
                        rows -> replaceGroups(rows.getFirst(), List.of(
                                group(1L, "G1", 1, List.of(validSymptomCondition())),
                                group(2L, "G2", 1, List.of(validSymptomCondition()))))),
                invalid("duplicate condition order", RedFlagSourceType.SYMPTOM_CHECK_IN,
                        rows -> replaceGroups(rows.getFirst(), List.of(
                                group(1L, "G1", 1, List.of(validSymptomCondition(),
                                        condition(2L, RedFlagSourceType.SYMPTOM_CHECK_IN,
                                                "symptom.abdominal_pain", RedFlagComparisonOperator.EQ,
                                                null, "severe", 0, 1)))))),
                invalid("missing required seeded key", RedFlagSourceType.SYMPTOM_CHECK_IN,
                        rows -> rows.removeFirst()),
                invalid("duplicate required seeded key", RedFlagSourceType.SYMPTOM_CHECK_IN,
                        rows -> rows.add(rows.getFirst())));
    }

    private static org.junit.jupiter.params.provider.Arguments invalid(
            String description, RedFlagSourceType source, Consumer<List<RedFlagRuleVersion>> mutate) {
        return org.junit.jupiter.params.provider.Arguments.of(description, new InvalidCatalogue(source, mutate));
    }

    private static List<RedFlagRuleVersion> validRows(RedFlagSourceType triggerSource) {
        var keys = triggerSource == RedFlagSourceType.SYMPTOM_CHECK_IN ? SYMPTOM_KEYS : LAB_KEYS;
        var rows = new ArrayList<RedFlagRuleVersion>();
        for (int index = 0; index < keys.size(); index++) {
            var condition = triggerSource == RedFlagSourceType.SYMPTOM_CHECK_IN
                    ? validSymptomCondition()
                    : condition(1L, RedFlagSourceType.LAB_RESULT_SET, "lab.CRP",
                            RedFlagComparisonOperator.GTE, BigDecimal.ONE, null, 0, 1);
            rows.add(version((long) index + 1, keys.get(index), triggerSource,
                    List.of(group((long) index + 1, "G1", 1, List.of(condition)))));
        }
        return rows;
    }

    private static RedFlagRuleVersion version(Long id, String ruleKey, RedFlagSourceType triggerSource,
            List<RedFlagRuleConditionGroup> groups) {
        var version = mock(RedFlagRuleVersion.class);
        var rule = mock(com.metabion.domain.RedFlagRule.class);
        when(version.getId()).thenReturn(id);
        when(version.getRule()).thenReturn(rule);
        when(rule.getStableKey()).thenReturn(ruleKey);
        when(version.getVersionNumber()).thenReturn(1);
        when(version.getStatus()).thenReturn(RedFlagRuleStatus.ACTIVE);
        when(version.getTriggerSource()).thenReturn(triggerSource);
        when(version.getSeverity()).thenReturn(RedFlagSeverity.ROUTINE_REVIEW);
        when(version.getApprovalReference()).thenReturn("MET-12 approved");
        when(version.getApprovedAt()).thenReturn(Instant.parse("2026-07-29T10:00:00Z"));
        when(version.getActivatedAt()).thenReturn(Instant.parse("2026-07-29T10:05:00Z"));
        when(version.getConditionGroups()).thenReturn(groups);
        return version;
    }

    private static RedFlagRuleConditionGroup group(
            Long id, String key, int order, List<RedFlagRuleCondition> conditions) {
        var group = mock(RedFlagRuleConditionGroup.class);
        when(group.getId()).thenReturn(id);
        when(group.getStableKey()).thenReturn(key);
        when(group.getSortOrder()).thenReturn(order);
        when(group.getConditions()).thenReturn(conditions);
        return group;
    }

    private static RedFlagRuleCondition condition(Long id, RedFlagSourceType source, String factKey,
            RedFlagComparisonOperator operator, BigDecimal decimalOperand, String textOperand,
            int lookbackDays, int sortOrder) {
        var condition = mock(RedFlagRuleCondition.class);
        when(condition.getId()).thenReturn(id);
        when(condition.getSourceType()).thenReturn(source);
        when(condition.getFactKey()).thenReturn(factKey);
        when(condition.getOperator()).thenReturn(operator);
        when(condition.getDecimalOperand()).thenReturn(decimalOperand);
        when(condition.getTextOperand()).thenReturn(textOperand);
        when(condition.getLookbackDays()).thenReturn(lookbackDays);
        when(condition.getSortOrder()).thenReturn(sortOrder);
        return condition;
    }

    private static RedFlagRuleCondition validSymptomCondition() {
        return condition(1L, RedFlagSourceType.SYMPTOM_CHECK_IN, "symptom.flare_state",
                RedFlagComparisonOperator.EQ, null, "NO_FLARE", 0, 1);
    }

    private static void replaceCondition(RedFlagRuleVersion version, RedFlagRuleCondition condition) {
        replaceGroups(version, List.of(group(1L, "G1", 1, List.of(condition))));
    }

    private static void replaceGroups(
            RedFlagRuleVersion version, List<RedFlagRuleConditionGroup> groups) {
        when(version.getConditionGroups()).thenReturn(groups);
    }

    private record InvalidCatalogue(
            RedFlagSourceType triggerSource, Consumer<List<RedFlagRuleVersion>> mutate) { }
}
