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

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

@Entity
@Table(name = "red_flag_evaluation_runs")
public class RedFlagEvaluationRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "patient_profile_id", nullable = false, updatable = false)
    private PatientProfile patientProfile;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, updatable = false, length = 32)
    private RedFlagSourceType sourceType;

    @Column(name = "source_id", nullable = false, updatable = false)
    private Long sourceId;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_operation", nullable = false, updatable = false, length = 16)
    private RedFlagSourceOperation sourceOperation;

    @Column(name = "evaluated_at", nullable = false, updatable = false)
    private Instant evaluatedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "overall_severity", updatable = false, length = 24)
    private RedFlagSeverity overallSeverity;

    @Column(name = "is_current", nullable = false)
    private boolean current;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "superseded_by_run_id")
    private RedFlagEvaluationRun supersededByRun;

    @OneToMany(mappedBy = "evaluationRun")
    @OrderBy("triggeredAt ASC, id ASC")
    private List<RedFlagTriggerEvent> events = new ArrayList<>();

    protected RedFlagEvaluationRun() {
    }

    public static RedFlagEvaluationRun pending(
            PatientProfile patient, RedFlagSourceType sourceType, Long sourceId,
            RedFlagSourceOperation operation, Instant evaluatedAt,
            RedFlagSeverity overallSeverity) {
        var run = new RedFlagEvaluationRun();
        run.patientProfile = Objects.requireNonNull(patient);
        run.sourceType = Objects.requireNonNull(sourceType);
        run.sourceId = Objects.requireNonNull(sourceId);
        run.sourceOperation = Objects.requireNonNull(operation);
        run.evaluatedAt = Objects.requireNonNull(evaluatedAt);
        run.overallSeverity = overallSeverity;
        run.current = false;
        return run;
    }

    public void supersedeWith(RedFlagEvaluationRun successor) {
        if (!current || successor == this) {
            throw new IllegalStateException("Invalid red-flag run supersession");
        }
        current = false;
        supersededByRun = successor;
    }

    public void markCurrent() {
        if (supersededByRun != null) {
            throw new IllegalStateException("Superseded run cannot become current");
        }
        current = true;
    }

    public Long getId() {
        return id;
    }

    public PatientProfile getPatientProfile() {
        return patientProfile;
    }

    public RedFlagSourceType getSourceType() {
        return sourceType;
    }

    public Long getSourceId() {
        return sourceId;
    }

    public RedFlagSourceOperation getSourceOperation() {
        return sourceOperation;
    }

    public Instant getEvaluatedAt() {
        return evaluatedAt;
    }

    public RedFlagSeverity getOverallSeverity() {
        return overallSeverity;
    }

    public boolean isCurrent() {
        return current;
    }

    public RedFlagEvaluationRun getSupersededByRun() {
        return supersededByRun;
    }

    public List<RedFlagTriggerEvent> getEvents() {
        return Collections.unmodifiableList(events);
    }
}
