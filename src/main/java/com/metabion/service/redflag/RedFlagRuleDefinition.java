package com.metabion.service.redflag;

import com.metabion.domain.RedFlagComparisonOperator;
import com.metabion.domain.RedFlagSeverity;
import com.metabion.domain.RedFlagSourceType;

import java.math.BigDecimal;
import java.util.List;

public record RedFlagRuleDefinition(
        Long versionId, String ruleKey, int versionNumber,
        RedFlagSourceType triggerSource, RedFlagSeverity severity,
        List<Group> groups) {

    public RedFlagRuleDefinition {
        groups = List.copyOf(groups);
    }

    public record Group(
            Long id, String stableKey, int sortOrder,
            List<Condition> conditions) {

        public Group {
            conditions = List.copyOf(conditions);
        }
    }

    public record Condition(
            Long id, RedFlagSourceType sourceType, String factKey,
            RedFlagComparisonOperator operator, BigDecimal decimalOperand,
            String textOperand, int lookbackDays, int sortOrder) { }
}
