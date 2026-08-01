package com.metabion.service.redflag;

import com.metabion.domain.RedFlagComparisonOperator;
import com.metabion.domain.RedFlagRuleCondition;
import com.metabion.domain.RedFlagRuleConditionGroup;
import com.metabion.domain.RedFlagRuleStatus;
import com.metabion.domain.RedFlagRuleVersion;
import com.metabion.domain.RedFlagSourceType;
import com.metabion.repository.RedFlagRuleVersionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class RedFlagRuleCatalog {

    private static final String INVALID_MESSAGE = "Active red-flag catalogue is invalid";
    private static final Map<RedFlagSourceType, Set<String>> REQUIRED_KEYS = Map.of(
            RedFlagSourceType.SYMPTOM_CHECK_IN, Set.of(
                    "SYM_SEVERE_ABDOMINAL_PAIN", "SYM_SIGNIFICANT_BLEEDING", "SYM_ACTIVE_FLARE",
                    "SYM_HIGH_STOOL_FREQUENCY", "SYM_COMBINED_SEVERE_ACTIVITY",
                    "SYM_SUSPECTED_FLARE", "SYM_MODERATE_DETERIORATION"),
            RedFlagSourceType.LAB_RESULT_SET, Set.of(
                    "LAB_SODIUM_CRITICAL", "LAB_POTASSIUM_CRITICAL", "LAB_CRP_CRITICAL",
                    "LAB_CRP_HIGH", "LAB_CRP_SYMPTOM_CONTEXT", "LAB_HEMOGLOBIN_CRITICAL_LOW",
                    "LAB_MAGNESIUM_CRITICAL_LOW", "LAB_UREA_CRITICAL_HIGH",
                    "LAB_CREATININE_CRITICAL_HIGH", "LAB_TRANSAMINASE_CRITICAL_HIGH",
                    "LAB_ALBUMIN_CRITICAL_LOW", "LAB_CALPROTECTIN_HIGH", "LAB_CRP_ELEVATED",
                    "LAB_ALBUMIN_LOW", "LAB_HEMOGLOBIN_LOW_MALE", "LAB_HEMOGLOBIN_LOW_FEMALE",
                    "LAB_CALPROTECTIN_BORDERLINE"));

    private static final Comparator<RedFlagRuleConditionGroup> GROUP_ORDER =
            Comparator.comparingInt(RedFlagRuleConditionGroup::getSortOrder)
                    .thenComparing(RedFlagRuleConditionGroup::getId, Comparator.nullsLast(Comparator.naturalOrder()));
    private static final Comparator<RedFlagRuleCondition> CONDITION_ORDER =
            Comparator.comparingInt(RedFlagRuleCondition::getSortOrder)
                    .thenComparing(RedFlagRuleCondition::getId, Comparator.nullsLast(Comparator.naturalOrder()));

    private final RedFlagRuleVersionRepository versions;
    private final RedFlagFactRegistry facts;

    public RedFlagRuleCatalog(RedFlagRuleVersionRepository versions, RedFlagFactRegistry facts) {
        this.versions = versions;
        this.facts = facts;
    }

    @Transactional(readOnly = true)
    public List<RedFlagRuleDefinition> activeFor(RedFlagSourceType source) {
        var requiredKeys = REQUIRED_KEYS.get(source);
        if (requiredKeys == null) {
            throw invalid();
        }
        var active = versions.findByStatusAndTriggerSource(RedFlagRuleStatus.ACTIVE, source);
        validateRequiredKeys(active, requiredKeys);
        return active.stream()
                .map(version -> map(version, source))
                .sorted(Comparator.comparing(RedFlagRuleDefinition::ruleKey)
                        .thenComparingInt(RedFlagRuleDefinition::versionNumber))
                .toList();
    }

    private RedFlagRuleDefinition map(RedFlagRuleVersion version, RedFlagSourceType requestedSource) {
        if (version == null || version.getRule() == null || version.getRule().getStableKey() == null
                || version.getStatus() != RedFlagRuleStatus.ACTIVE
                || version.getTriggerSource() != requestedSource
                || version.getSeverity() == null
                || isBlank(version.getApprovalReference())
                || version.getApprovedAt() == null || version.getActivatedAt() == null) {
            throw invalid();
        }
        var groups = version.getConditionGroups();
        if (groups == null || groups.isEmpty() || hasDuplicateOrder(groups.stream()
                .map(RedFlagRuleConditionGroup::getSortOrder).toList())) {
            throw invalid();
        }
        var mappedGroups = groups.stream().sorted(GROUP_ORDER)
                .map(group -> map(group, requestedSource)).toList();
        return new RedFlagRuleDefinition(version.getId(), version.getRule().getStableKey(),
                version.getVersionNumber(), version.getTriggerSource(), version.getSeverity(), mappedGroups);
    }

    private RedFlagRuleDefinition.Group map(
            RedFlagRuleConditionGroup group, RedFlagSourceType triggerSource) {
        if (group == null || group.getStableKey() == null) {
            throw invalid();
        }
        var conditions = group.getConditions();
        if (conditions == null || conditions.isEmpty() || hasDuplicateOrder(conditions.stream()
                .map(RedFlagRuleCondition::getSortOrder).toList())) {
            throw invalid();
        }
        var mappedConditions = conditions.stream().sorted(CONDITION_ORDER)
                .map(condition -> map(condition, triggerSource)).toList();
        return new RedFlagRuleDefinition.Group(
                group.getId(), group.getStableKey(), group.getSortOrder(), mappedConditions);
    }

    private RedFlagRuleDefinition.Condition map(
            RedFlagRuleCondition condition, RedFlagSourceType triggerSource) {
        if (condition == null || condition.getSourceType() == null || condition.getFactKey() == null
                || condition.getOperator() == null || condition.getLookbackDays() < 0) {
            throw invalid();
        }
        var definition = facts.find(condition.getSourceType(), condition.getFactKey()).orElseThrow(
                RedFlagRuleCatalog::invalid);
        var hasDecimal = condition.getDecimalOperand() != null;
        var hasText = condition.getTextOperand() != null;
        if (hasDecimal == hasText
                || definition.valueType() == RedFlagFactRegistry.ValueType.DECIMAL && !hasDecimal
                || definition.valueType() == RedFlagFactRegistry.ValueType.TEXT && !hasText
                || definition.valueType() == RedFlagFactRegistry.ValueType.TEXT
                        && condition.getOperator() != RedFlagComparisonOperator.EQ) {
            throw invalid();
        }
        if (condition.getLookbackDays() > 0
                && (triggerSource != RedFlagSourceType.LAB_RESULT_SET
                || condition.getSourceType() != RedFlagSourceType.SYMPTOM_CHECK_IN
                || !definition.lookbackAllowed())) {
            throw invalid();
        }
        return new RedFlagRuleDefinition.Condition(
                condition.getId(), condition.getSourceType(), condition.getFactKey(),
                condition.getOperator(), condition.getDecimalOperand(), condition.getTextOperand(),
                condition.getLookbackDays(), condition.getSortOrder());
    }

    private void validateRequiredKeys(List<RedFlagRuleVersion> active, Set<String> requiredKeys) {
        if (active == null) {
            throw invalid();
        }
        var keys = active.stream()
                .map(version -> version == null || version.getRule() == null
                        ? null : version.getRule().getStableKey())
                .toList();
        if (keys.stream().anyMatch(java.util.Objects::isNull)
                || keys.size() != requiredKeys.size()
                || new HashSet<>(keys).size() != keys.size()
                || !new HashSet<>(keys).equals(requiredKeys)) {
            throw invalid();
        }
    }

    private static boolean hasDuplicateOrder(List<Integer> orders) {
        return orders.size() != new HashSet<>(orders).size();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static IllegalStateException invalid() {
        return new IllegalStateException(INVALID_MESSAGE);
    }
}
