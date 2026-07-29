package com.metabion.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

import java.math.BigDecimal;

@Entity
@Immutable
@Table(name = "red_flag_rule_conditions")
public class RedFlagRuleCondition {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "condition_group_id", nullable = false, updatable = false)
    private RedFlagRuleConditionGroup conditionGroup;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, updatable = false, length = 32)
    private RedFlagSourceType sourceType;

    @Column(name = "fact_key", nullable = false, updatable = false, length = 160)
    private String factKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "comparison_operator", nullable = false, updatable = false, length = 8)
    private RedFlagComparisonOperator operator;

    @Column(name = "decimal_operand", precision = 18, scale = 6, updatable = false)
    private BigDecimal decimalOperand;

    @Column(name = "text_operand", length = 160, updatable = false)
    private String textOperand;

    @Column(name = "lookback_days", nullable = false, updatable = false)
    private int lookbackDays;

    @Column(name = "sort_order", nullable = false, updatable = false)
    private int sortOrder;

    protected RedFlagRuleCondition() {
    }

    public Long getId() {
        return id;
    }

    public RedFlagRuleConditionGroup getConditionGroup() {
        return conditionGroup;
    }

    public RedFlagSourceType getSourceType() {
        return sourceType;
    }

    public String getFactKey() {
        return factKey;
    }

    public RedFlagComparisonOperator getOperator() {
        return operator;
    }

    public BigDecimal getDecimalOperand() {
        return decimalOperand;
    }

    public String getTextOperand() {
        return textOperand;
    }

    public int getLookbackDays() {
        return lookbackDays;
    }

    public int getSortOrder() {
        return sortOrder;
    }
}
