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

import java.time.Instant;
import java.util.Objects;

@Entity
@Immutable
@Table(name = "red_flag_trigger_events")
public class RedFlagTriggerEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "evaluation_run_id", nullable = false, updatable = false)
    private RedFlagEvaluationRun evaluationRun;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "rule_version_id", nullable = false, updatable = false)
    private RedFlagRuleVersion ruleVersion;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "matched_group_id", nullable = false, updatable = false)
    private RedFlagRuleConditionGroup matchedGroup;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 24)
    private RedFlagSeverity severity;

    @Column(name = "triggered_at", nullable = false, updatable = false)
    private Instant triggeredAt;

    @Column(name = "matched_inputs", nullable = false, updatable = false, columnDefinition = "TEXT")
    private String matchedInputs;

    protected RedFlagTriggerEvent() {
    }

    public RedFlagTriggerEvent(
            RedFlagEvaluationRun evaluationRun, RedFlagRuleVersion ruleVersion,
            RedFlagRuleConditionGroup matchedGroup, RedFlagSeverity severity,
            Instant triggeredAt, String matchedInputs) {
        this.evaluationRun = Objects.requireNonNull(evaluationRun);
        this.ruleVersion = Objects.requireNonNull(ruleVersion);
        this.matchedGroup = Objects.requireNonNull(matchedGroup);
        this.severity = Objects.requireNonNull(severity);
        this.triggeredAt = Objects.requireNonNull(triggeredAt);
        this.matchedInputs = Objects.requireNonNull(matchedInputs);
    }

    public Long getId() {
        return id;
    }

    public RedFlagEvaluationRun getEvaluationRun() {
        return evaluationRun;
    }

    public RedFlagRuleVersion getRuleVersion() {
        return ruleVersion;
    }

    public RedFlagRuleConditionGroup getMatchedGroup() {
        return matchedGroup;
    }

    public RedFlagSeverity getSeverity() {
        return severity;
    }

    public Instant getTriggeredAt() {
        return triggeredAt;
    }

    public String getMatchedInputs() {
        return matchedInputs;
    }
}
