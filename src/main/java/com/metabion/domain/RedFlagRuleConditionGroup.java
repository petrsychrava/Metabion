package com.metabion.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Entity
@Immutable
@Table(name = "red_flag_rule_condition_groups")
public class RedFlagRuleConditionGroup {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "rule_version_id", nullable = false, updatable = false)
    private RedFlagRuleVersion ruleVersion;

    @Column(name = "stable_key", nullable = false, updatable = false, length = 120)
    private String stableKey;

    @Column(name = "sort_order", nullable = false, updatable = false)
    private int sortOrder;

    @OneToMany(mappedBy = "conditionGroup")
    @OrderBy("sortOrder ASC, id ASC")
    private List<RedFlagRuleCondition> conditions = new ArrayList<>();

    protected RedFlagRuleConditionGroup() {
    }

    public Long getId() {
        return id;
    }

    public RedFlagRuleVersion getRuleVersion() {
        return ruleVersion;
    }

    public String getStableKey() {
        return stableKey;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public List<RedFlagRuleCondition> getConditions() {
        return Collections.unmodifiableList(conditions);
    }
}
