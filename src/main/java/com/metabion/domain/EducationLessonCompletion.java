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
@Table(name = "education_lesson_completions")
public class EducationLessonCompletion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "patient_profile_id", nullable = false)
    private PatientProfile patientProfile;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "module_version_id", nullable = false)
    private EducationModuleVersion moduleVersion;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "lesson_version_id", nullable = false)
    private EducationLessonVersion lessonVersion;

    @Column(name = "completed_at", nullable = false)
    private Instant completedAt = Instant.now();

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public EducationLessonCompletion() {
    }

    public EducationLessonCompletion(
            PatientProfile patientProfile,
            EducationModuleVersion moduleVersion,
            EducationLessonVersion lessonVersion) {
        if (moduleVersion != null && lessonVersion != null
                && moduleVersion != lessonVersion.getModuleVersion()
                && (moduleVersion.getId() == null || lessonVersion.getModuleVersion().getId() == null
                || !moduleVersion.getId().equals(lessonVersion.getModuleVersion().getId()))) {
            throw new IllegalArgumentException("Completion requires lesson version from the same module version");
        }
        this.patientProfile = patientProfile;
        this.moduleVersion = moduleVersion;
        this.lessonVersion = lessonVersion;
    }

    public Long getId() {
        return id;
    }

    public PatientProfile getPatientProfile() {
        return patientProfile;
    }

    public EducationModuleVersion getModuleVersion() {
        return moduleVersion;
    }

    public EducationLessonVersion getLessonVersion() {
        return lessonVersion;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
