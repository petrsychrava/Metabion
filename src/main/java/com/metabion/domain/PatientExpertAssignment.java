package com.metabion.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "patient_expert_assignments")
public class PatientExpertAssignment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "patient_profile_id", nullable = false)
    private PatientProfile patientProfile;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "staff_profile_id", nullable = false)
    private StaffProfile staffProfile;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assigned_by_user_id")
    private User assignedBy;

    @Column(name = "assigned_at", nullable = false, updatable = false)
    private Instant assignedAt = Instant.now();

    @Column(name = "ended_at")
    private Instant endedAt;

    public PatientExpertAssignment() {
    }

    public PatientExpertAssignment(PatientProfile patientProfile, StaffProfile staffProfile, User assignedBy) {
        this.patientProfile = patientProfile;
        this.staffProfile = staffProfile;
        this.assignedBy = assignedBy;
    }

    public boolean isActive() {
        return endedAt == null;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public PatientProfile getPatientProfile() {
        return patientProfile;
    }

    public void setPatientProfile(PatientProfile patientProfile) {
        this.patientProfile = patientProfile;
    }

    public StaffProfile getStaffProfile() {
        return staffProfile;
    }

    public void setStaffProfile(StaffProfile staffProfile) {
        this.staffProfile = staffProfile;
    }

    public User getAssignedBy() {
        return assignedBy;
    }

    public void setAssignedBy(User assignedBy) {
        this.assignedBy = assignedBy;
    }

    public Instant getAssignedAt() {
        return assignedAt;
    }

    public void setAssignedAt(Instant assignedAt) {
        this.assignedAt = assignedAt;
    }

    public Instant getEndedAt() {
        return endedAt;
    }

    public void setEndedAt(Instant endedAt) {
        this.endedAt = endedAt;
    }
}
