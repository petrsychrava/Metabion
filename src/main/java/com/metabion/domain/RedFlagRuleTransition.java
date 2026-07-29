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

@Entity
@Immutable
@Table(name = "red_flag_rule_transitions")
public class RedFlagRuleTransition {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "rule_version_id", nullable = false, updatable = false)
    private RedFlagRuleVersion ruleVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "previous_status", updatable = false, length = 20)
    private RedFlagRuleStatus previousStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "new_status", nullable = false, updatable = false, length = 20)
    private RedFlagRuleStatus newStatus;

    @Column(name = "actor_reference", nullable = false, updatable = false, length = 200)
    private String actorReference;

    @Column(name = "transitioned_at", nullable = false, updatable = false)
    private Instant transitionedAt;

    @Column(name = "change_note", nullable = false, updatable = false, length = 1000)
    private String changeNote;

    protected RedFlagRuleTransition() {
    }

    public Long getId() {
        return id;
    }

    public RedFlagRuleVersion getRuleVersion() {
        return ruleVersion;
    }

    public RedFlagRuleStatus getPreviousStatus() {
        return previousStatus;
    }

    public RedFlagRuleStatus getNewStatus() {
        return newStatus;
    }

    public String getActorReference() {
        return actorReference;
    }

    public Instant getTransitionedAt() {
        return transitionedAt;
    }

    public String getChangeNote() {
        return changeNote;
    }
}
