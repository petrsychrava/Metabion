package com.metabion.service.redflag;

import com.metabion.domain.RedFlagComparisonOperator;
import com.metabion.domain.RedFlagSeverity;
import com.metabion.domain.RedFlagSourceType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public class RedFlagRuleEngine {

    private static final Comparator<RedFlagRuleDefinition.Group> GROUP_ORDER =
            Comparator.comparingInt(RedFlagRuleDefinition.Group::sortOrder)
                    .thenComparing(RedFlagRuleDefinition.Group::id, Comparator.nullsLast(Comparator.naturalOrder()));
    private static final Comparator<RedFlagRuleDefinition.Condition> CONDITION_ORDER =
            Comparator.comparingInt(RedFlagRuleDefinition.Condition::sortOrder)
                    .thenComparing(RedFlagRuleDefinition.Condition::id, Comparator.nullsLast(Comparator.naturalOrder()));
    private static final Comparator<RedFlagFactSet> LOOKBACK_ORDER =
            Comparator.comparing(RedFlagFactSet::observedOn, Comparator.reverseOrder())
                    .thenComparing(RedFlagFactSet::sourceId, Comparator.nullsLast(Comparator.reverseOrder()));

    public RedFlagEvaluationResult evaluate(
            List<RedFlagRuleDefinition> rules,
            RedFlagEvaluationInput input) {
        var matches = new ArrayList<RedFlagRuleMatch>();
        for (var rule : rules) {
            if (rule.triggerSource() != input.trigger().sourceType()) {
                continue;
            }
            findFirstMatch(rule, input).ifPresent(matches::add);
        }

        var overallSeverity = matches.stream()
                .map(match -> match.rule().severity())
                .max(Comparator.comparingInt(RedFlagSeverity::priority))
                .orElse(null);
        return new RedFlagEvaluationResult(matches, overallSeverity);
    }

    private Optional<RedFlagRuleMatch> findFirstMatch(
            RedFlagRuleDefinition rule, RedFlagEvaluationInput input) {
        return rule.groups().stream()
                .sorted(GROUP_ORDER)
                .map(group -> matchGroup(rule, group, input))
                .flatMap(Optional::stream)
                .findFirst();
    }

    private Optional<RedFlagRuleMatch> matchGroup(
            RedFlagRuleDefinition rule,
            RedFlagRuleDefinition.Group group,
            RedFlagEvaluationInput input) {
        Map<Partition, List<RedFlagRuleDefinition.Condition>> partitions = new LinkedHashMap<>();
        group.conditions().stream().sorted(CONDITION_ORDER).forEach(condition ->
                partitions.computeIfAbsent(new Partition(condition.sourceType(), condition.lookbackDays()), ignored -> new ArrayList<>())
                        .add(condition));

        var matchedFacts = new LinkedHashSet<RedFlagRuleMatch.MatchedFact>();
        for (var partition : partitions.entrySet()) {
            var partitionMatch = matchPartition(partition.getKey(), partition.getValue(), input);
            if (partitionMatch.isEmpty()) {
                return Optional.empty();
            }
            matchedFacts.addAll(partitionMatch.get());
        }
        return Optional.of(new RedFlagRuleMatch(rule, group.id(), group.stableKey(), List.copyOf(matchedFacts)));
    }

    private Optional<List<RedFlagRuleMatch.MatchedFact>> matchPartition(
            Partition partition,
            List<RedFlagRuleDefinition.Condition> conditions,
            RedFlagEvaluationInput input) {
        return candidateFactSets(partition, input).stream()
                .map(factSet -> matchFactSet(factSet, conditions))
                .flatMap(Optional::stream)
                .findFirst();
    }

    private List<RedFlagFactSet> candidateFactSets(Partition partition, RedFlagEvaluationInput input) {
        if (partition.lookbackDays() == 0) {
            if (partition.sourceType() == input.trigger().sourceType()) {
                return List.of(input.trigger());
            }
            if (partition.sourceType() == RedFlagSourceType.PATIENT_PROFILE) {
                return List.of(input.patientProfile());
            }
            return List.of();
        }

        LocalDate earliest = input.trigger().observedOn().minusDays(partition.lookbackDays());
        LocalDate latest = input.trigger().observedOn();
        return input.lookback().stream()
                .filter(factSet -> factSet.sourceType() == partition.sourceType())
                .filter(factSet -> !factSet.observedOn().isBefore(earliest) && !factSet.observedOn().isAfter(latest))
                .sorted(LOOKBACK_ORDER)
                .toList();
    }

    private Optional<List<RedFlagRuleMatch.MatchedFact>> matchFactSet(
            RedFlagFactSet factSet,
            List<RedFlagRuleDefinition.Condition> conditions) {
        var matches = new ArrayList<RedFlagRuleMatch.MatchedFact>();
        for (var condition : conditions) {
            var fact = factSet.facts().stream()
                    .filter(candidate -> Objects.equals(candidate.key(), condition.factKey()))
                    .filter(candidate -> matches(condition, candidate))
                    .findFirst();
            if (fact.isEmpty()) {
                return Optional.empty();
            }
            matches.add(new RedFlagRuleMatch.MatchedFact(
                    factSet.sourceType(), factSet.sourceId(), factSet.observedOn(), fact.get()));
        }
        return Optional.of(matches);
    }

    private boolean matches(RedFlagRuleDefinition.Condition condition, RedFlagFact fact) {
        if (condition.decimalOperand() != null) {
            return fact.decimalValue() != null
                    && matchesDecimal(fact.decimalValue(), condition.operator(), condition.decimalOperand());
        }
        return condition.textOperand() != null
                && fact.textValue() != null
                && condition.operator() == RedFlagComparisonOperator.EQ
                && condition.textOperand().equals(fact.textValue());
    }

    private boolean matchesDecimal(
            BigDecimal value,
            RedFlagComparisonOperator operator,
            BigDecimal operand) {
        int comparison = value.compareTo(operand);
        return switch (operator) {
            case EQ -> comparison == 0;
            case GT -> comparison > 0;
            case GTE -> comparison >= 0;
            case LT -> comparison < 0;
            case LTE -> comparison <= 0;
        };
    }

    private record Partition(RedFlagSourceType sourceType, int lookbackDays) { }
}
