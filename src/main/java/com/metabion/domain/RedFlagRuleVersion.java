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
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Entity
@Immutable
@Table(name = "red_flag_rule_versions")
public class RedFlagRuleVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "rule_id", nullable = false, updatable = false)
    private RedFlagRule rule;

    @Column(name = "version_number", nullable = false, updatable = false)
    private int versionNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 20)
    private RedFlagRuleStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_source", nullable = false, updatable = false, length = 32)
    private RedFlagSourceType triggerSource;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 24)
    private RedFlagSeverity severity;

    @Column(name = "evidence_reference", nullable = false, updatable = false, length = 1000)
    private String evidenceReference;

    @Column(nullable = false, updatable = false, length = 2000)
    private String rationale;

    @Column(name = "author_reference", nullable = false, updatable = false, length = 200)
    private String authorReference;

    @Column(name = "change_summary", nullable = false, updatable = false, length = 1000)
    private String changeSummary;

    @Column(name = "approval_reference", updatable = false, length = 500)
    private String approvalReference;

    @Column(name = "approved_at", updatable = false)
    private Instant approvedAt;

    @Column(name = "activated_at", updatable = false)
    private Instant activatedAt;

    @Column(name = "retired_at", updatable = false)
    private Instant retiredAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @OneToMany(mappedBy = "ruleVersion")
    @OrderBy("sortOrder ASC, id ASC")
    private List<RedFlagRuleConditionGroup> conditionGroups = new ArrayList<>();

    protected RedFlagRuleVersion() {
    }

    public Long getId() {
        return id;
    }

    public RedFlagRule getRule() {
        return rule;
    }

    public int getVersionNumber() {
        return versionNumber;
    }

    public RedFlagRuleStatus getStatus() {
        return status;
    }

    public RedFlagSourceType getTriggerSource() {
        return triggerSource;
    }

    public RedFlagSeverity getSeverity() {
        return severity;
    }

    public String getEvidenceReference() {
        return evidenceReference;
    }

    public String getRationale() {
        return rationale;
    }

    public String getAuthorReference() {
        return authorReference;
    }

    public String getChangeSummary() {
        return changeSummary;
    }

    public String getApprovalReference() {
        return approvalReference;
    }

    public Instant getApprovedAt() {
        return approvedAt;
    }

    public Instant getActivatedAt() {
        return activatedAt;
    }

    public Instant getRetiredAt() {
        return retiredAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public List<RedFlagRuleConditionGroup> getConditionGroups() {
        return Collections.unmodifiableList(conditionGroups);
    }
}
