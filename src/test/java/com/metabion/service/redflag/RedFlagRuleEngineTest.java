package com.metabion.service.redflag;

import com.metabion.domain.RedFlagComparisonOperator;
import com.metabion.domain.RedFlagSeverity;
import com.metabion.domain.RedFlagSourceType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RedFlagRuleEngineTest {

    private static final LocalDate TRIGGER_DATE = LocalDate.of(2026, 7, 29);
    private final RedFlagRuleEngine engine = new RedFlagRuleEngine();

    @Test
    void matchesTextAndDecimalEquality() {
        var rule = rule(1L, "combined", RedFlagSeverity.ROUTINE_REVIEW,
                group(1L, "text-and-decimal", 1,
                        text(RedFlagSourceType.SYMPTOM_CHECK_IN, "pain", "severe", 0, 1),
                        decimal(RedFlagSourceType.SYMPTOM_CHECK_IN, "stools", RedFlagComparisonOperator.EQ, "6", 0, 2)));

        var result = engine.evaluate(List.of(rule), input(trigger(101L, "pain", "severe", "stools", "6")));

        assertThat(result.matches()).hasSize(1);
        assertThat(result.matches().getFirst().matchedFacts())
                .extracting(match -> match.fact().key()).containsExactly("pain", "stools");
    }

    @Test
    void matchesStrictAndInclusiveDecimalComparisons() {
        var rule = rule(1L, "bounds", RedFlagSeverity.ROUTINE_REVIEW,
                group(1L, "bounds", 1,
                        decimal(RedFlagSourceType.LAB_RESULT_SET, "gt", RedFlagComparisonOperator.GT, "9", 0, 1),
                        decimal(RedFlagSourceType.LAB_RESULT_SET, "gte", RedFlagComparisonOperator.GTE, "10", 0, 2),
                        decimal(RedFlagSourceType.LAB_RESULT_SET, "lt", RedFlagComparisonOperator.LT, "11", 0, 3),
                        decimal(RedFlagSourceType.LAB_RESULT_SET, "lte", RedFlagComparisonOperator.LTE, "10", 0, 4)));

        var result = engine.evaluate(List.of(rule), input(labTrigger(101L, "gt", "10", "gte", "10", "lt", "10", "lte", "10")));

        assertThat(result.matches()).hasSize(1);
    }

    @Test
    void doesNotMatchWhenARequiredFactIsMissing() {
        var rule = rule(1L, "missing", RedFlagSeverity.ROUTINE_REVIEW,
                group(1L, "required", 1,
                        text(RedFlagSourceType.SYMPTOM_CHECK_IN, "pain", "severe", 0, 1),
                        text(RedFlagSourceType.SYMPTOM_CHECK_IN, "blood", "visible", 0, 2)));

        var result = engine.evaluate(List.of(rule), input(trigger(101L, "pain", "severe")));

        assertThat(result.matches()).isEmpty();
        assertThat(result.overallSeverity()).isNull();
    }

    @Test
    void treatsGroupsAsOrAndConditionsWithinEachGroupAsAnd() {
        var rule = rule(1L, "or-and", RedFlagSeverity.ROUTINE_REVIEW,
                group(1L, "does-not-match", 1,
                        text(RedFlagSourceType.SYMPTOM_CHECK_IN, "pain", "severe", 0, 1),
                        text(RedFlagSourceType.SYMPTOM_CHECK_IN, "blood", "visible", 0, 2)),
                group(2L, "matches", 2,
                        text(RedFlagSourceType.SYMPTOM_CHECK_IN, "pain", "severe", 0, 1)));

        var result = engine.evaluate(List.of(rule), input(trigger(101L, "pain", "severe")));

        assertThat(result.matches()).singleElement()
                .satisfies(match -> assertThat(match.matchedGroupKey()).isEqualTo("matches"));
    }

    @Test
    void selectsFirstMatchingGroupBySortOrderThenId() {
        var rule = rule(1L, "first-group", RedFlagSeverity.ROUTINE_REVIEW,
                group(8L, "later-id", 1, text(RedFlagSourceType.SYMPTOM_CHECK_IN, "pain", "severe", 0, 1)),
                group(3L, "first-id", 1, text(RedFlagSourceType.SYMPTOM_CHECK_IN, "pain", "severe", 0, 1)),
                group(1L, "later-sort", 2, text(RedFlagSourceType.SYMPTOM_CHECK_IN, "pain", "severe", 0, 1)));

        var result = engine.evaluate(List.of(rule), input(trigger(101L, "pain", "severe")));

        assertThat(result.matches()).singleElement()
                .satisfies(match -> assertThat(match.matchedGroupId()).isEqualTo(3L));
    }

    @Test
    void selectsMostRecentQualifyingLookbackFactSet() {
        var rule = rule(1L, "recent", RedFlagSeverity.ROUTINE_REVIEW,
                group(1L, "recent", 1, text(RedFlagSourceType.SYMPTOM_CHECK_IN, "pain", "severe", 7, 1)));
        var older = factSet(RedFlagSourceType.SYMPTOM_CHECK_IN, 12L, TRIGGER_DATE.minusDays(4), fact("pain", "severe"));
        var newer = factSet(RedFlagSourceType.SYMPTOM_CHECK_IN, 99L, TRIGGER_DATE.minusDays(1), fact("pain", "severe"));

        var result = engine.evaluate(List.of(rule), input(trigger(101L, "pain", "none"), List.of(older, newer)));

        assertThat(result.matches()).singleElement().satisfies(match ->
                assertThat(match.matchedFacts()).singleElement()
                        .satisfies(fact -> assertThat(fact.sourceId()).isEqualTo(99L)));
    }

    @Test
    void usesHighestSeverityAcrossAllMatchingRules() {
        var routine = rule(1L, "routine", RedFlagSeverity.ROUTINE_REVIEW,
                group(1L, "routine", 1, text(RedFlagSourceType.SYMPTOM_CHECK_IN, "pain", "severe", 0, 1)));
        var emergency = rule(2L, "emergency", RedFlagSeverity.EMERGENCY,
                group(2L, "emergency", 1, text(RedFlagSourceType.SYMPTOM_CHECK_IN, "pain", "severe", 0, 1)));

        var result = engine.evaluate(List.of(routine, emergency), input(trigger(101L, "pain", "severe")));

        assertThat(result.matches()).hasSize(2);
        assertThat(result.overallSeverity()).isEqualTo(RedFlagSeverity.EMERGENCY);
    }

    @Test
    void includesTheSeventhLookbackDayButExcludesTheEighth() {
        var rule = rule(1L, "window", RedFlagSeverity.ROUTINE_REVIEW,
                group(1L, "window", 1, text(RedFlagSourceType.SYMPTOM_CHECK_IN, "pain", "severe", 7, 1)));
        var seventhDay = factSet(RedFlagSourceType.SYMPTOM_CHECK_IN, 7L, TRIGGER_DATE.minusDays(7), fact("pain", "severe"));
        var eighthDay = factSet(RedFlagSourceType.SYMPTOM_CHECK_IN, 8L, TRIGGER_DATE.minusDays(8), fact("pain", "severe"));

        var included = engine.evaluate(List.of(rule), input(trigger(101L, "pain", "none"), List.of(seventhDay)));
        var excluded = engine.evaluate(List.of(rule), input(trigger(101L, "pain", "none"), List.of(eighthDay)));

        assertThat(included.matches()).hasSize(1);
        assertThat(excluded.matches()).isEmpty();
    }

    @Test
    void requiresAllLookbackConditionsToMatchTheSameRecord() {
        var rule = rule(1L, "correlation", RedFlagSeverity.ROUTINE_REVIEW,
                group(1L, "correlation", 1,
                        decimal(RedFlagSourceType.SYMPTOM_CHECK_IN, "stools", RedFlagComparisonOperator.GTE, "6", 7, 1),
                        text(RedFlagSourceType.SYMPTOM_CHECK_IN, "blood", "visible", 7, 2)));
        var stoolsOnly = factSet(RedFlagSourceType.SYMPTOM_CHECK_IN, 1L, TRIGGER_DATE.minusDays(1), fact("stools", "6"));
        var bloodOnly = factSet(RedFlagSourceType.SYMPTOM_CHECK_IN, 2L, TRIGGER_DATE.minusDays(2), fact("blood", "visible"));

        var result = engine.evaluate(List.of(rule), input(trigger(101L, "pain", "none"), List.of(stoolsOnly, bloodOnly)));

        assertThat(result.matches()).isEmpty();
    }

    @Test
    void deduplicatesOneFactThatSatisfiesTwoBounds() {
        var rule = rule(1L, "dedupe", RedFlagSeverity.ROUTINE_REVIEW,
                group(1L, "dedupe", 1,
                        decimal(RedFlagSourceType.LAB_RESULT_SET, "crp", RedFlagComparisonOperator.GT, "45", 0, 1),
                        decimal(RedFlagSourceType.LAB_RESULT_SET, "crp", RedFlagComparisonOperator.LT, "100", 0, 2)));

        var result = engine.evaluate(List.of(rule), input(labTrigger(101L, "crp", "80")));

        assertThat(result.matches()).singleElement().satisfies(match ->
                assertThat(match.matchedFacts()).singleElement().satisfies(fact ->
                        assertThat(fact.fact().decimalValue()).isEqualByComparingTo("80")));
    }

    @Test
    void ignoresRulesForOtherTriggerSourcesAndReturnsNoMatch() {
        var rule = rule(1L, "lab-only", RedFlagSeverity.EMERGENCY,
                group(1L, "lab", 1, decimal(RedFlagSourceType.LAB_RESULT_SET, "crp", RedFlagComparisonOperator.GT, "45", 0, 1)));

        var result = engine.evaluate(List.of(rule), input(trigger(101L, "pain", "severe")));

        assertThat(result.matches()).isEmpty();
        assertThat(result.overallSeverity()).isNull();
    }

    private static RedFlagEvaluationInput input(RedFlagFactSet trigger) {
        return input(trigger, List.of());
    }

    private static RedFlagEvaluationInput input(RedFlagFactSet trigger, List<RedFlagFactSet> lookback) {
        return new RedFlagEvaluationInput(trigger,
                factSet(RedFlagSourceType.PATIENT_PROFILE, 500L, TRIGGER_DATE, fact("sex", "female")), lookback);
    }

    private static RedFlagFactSet trigger(Long id, String... facts) {
        return factSet(RedFlagSourceType.SYMPTOM_CHECK_IN, id, TRIGGER_DATE, facts(facts));
    }

    private static RedFlagFactSet labTrigger(Long id, String... facts) {
        return factSet(RedFlagSourceType.LAB_RESULT_SET, id, TRIGGER_DATE, facts(facts));
    }

    private static RedFlagFactSet factSet(RedFlagSourceType type, Long id, LocalDate date, RedFlagFact... facts) {
        return new RedFlagFactSet(type, id, date, List.of(facts));
    }

    private static RedFlagFact[] facts(String... values) {
        var result = new RedFlagFact[values.length / 2];
        for (int index = 0; index < values.length; index += 2) {
            result[index / 2] = fact(values[index], values[index + 1]);
        }
        return result;
    }

    private static RedFlagFact fact(String key, String value) {
        return value.matches("-?\\d+(\\.\\d+)?")
                ? new RedFlagFact(key, new BigDecimal(value), null, null)
                : new RedFlagFact(key, null, value, null);
    }

    private static RedFlagRuleDefinition rule(Long id, String key, RedFlagSeverity severity,
            RedFlagRuleDefinition.Group... groups) {
        return new RedFlagRuleDefinition(id, key, 1, groups[0].conditions().getFirst().sourceType(), severity, List.of(groups));
    }

    private static RedFlagRuleDefinition.Group group(Long id, String key, int sortOrder,
            RedFlagRuleDefinition.Condition... conditions) {
        return new RedFlagRuleDefinition.Group(id, key, sortOrder, List.of(conditions));
    }

    private static RedFlagRuleDefinition.Condition text(RedFlagSourceType sourceType, String key,
            String operand, int lookbackDays, int sortOrder) {
        return new RedFlagRuleDefinition.Condition(null, sourceType, key, RedFlagComparisonOperator.EQ,
                null, operand, lookbackDays, sortOrder);
    }

    private static RedFlagRuleDefinition.Condition decimal(RedFlagSourceType sourceType, String key,
            RedFlagComparisonOperator operator, String operand, int lookbackDays, int sortOrder) {
        return new RedFlagRuleDefinition.Condition(null, sourceType, key, operator,
                new BigDecimal(operand), null, lookbackDays, sortOrder);
    }
}
