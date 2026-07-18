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

import java.time.Instant;

@Entity
@Table(name = "lab_result_audit_events")
public class LabResultAuditEvent {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "result_set_id", nullable = false)
    private LabResultSet resultSet;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "patient_profile_id", nullable = false)
    private PatientProfile patientProfile;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20)
    private LabAuditAction action;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "actor_user_id", nullable = false)
    private User actorUser;
    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;
    @Column(name = "before_snapshot", columnDefinition = "TEXT")
    private String beforeSnapshot;
    @Column(name = "after_snapshot", columnDefinition = "TEXT")
    private String afterSnapshot;

    protected LabResultAuditEvent() { }
    public LabResultAuditEvent(LabResultSet resultSet, PatientProfile patientProfile, LabAuditAction action,
                               User actorUser, Instant occurredAt, String beforeSnapshot, String afterSnapshot) {
        this.resultSet = resultSet;
        this.patientProfile = patientProfile;
        this.action = action;
        this.actorUser = actorUser;
        this.occurredAt = occurredAt;
        this.beforeSnapshot = beforeSnapshot;
        this.afterSnapshot = afterSnapshot;
    }
    public Long getId() { return id; }
    public LabResultSet getResultSet() { return resultSet; }
    public PatientProfile getPatientProfile() { return patientProfile; }
    public LabAuditAction getAction() { return action; }
    public User getActorUser() { return actorUser; }
    public Instant getOccurredAt() { return occurredAt; }
    public String getBeforeSnapshot() { return beforeSnapshot; }
    public String getAfterSnapshot() { return afterSnapshot; }
}
