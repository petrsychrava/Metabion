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

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "onboarding_submissions")
public class OnboardingSubmission {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "patient_profile_id", nullable = false)
    private PatientProfile patientProfile;

    @Column(name = "onboarding_context", nullable = false, length = 100)
    private String onboardingContext;

    @Column(nullable = false)
    private int version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "submitted_at", nullable = false, updatable = false)
    private Instant submittedAt = Instant.now();

    @Enumerated(EnumType.STRING)
    @Column(name = "diagnosis_type", nullable = false, length = 60)
    private IbdDiagnosisType diagnosisType;

    @Column(name = "diagnosis_year")
    private Integer diagnosisYear;

    @Column(name = "disease_location", length = 120)
    private String diseaseLocation;

    @Column(name = "disease_behavior", length = 120)
    private String diseaseBehavior;

    @Enumerated(EnumType.STRING)
    @Column(name = "activity_estimate", nullable = false, length = 60)
    private DiseaseActivityEstimate activityEstimate;

    @Column(name = "current_medications", length = 1000)
    private String currentMedications;

    @Enumerated(EnumType.STRING)
    @Column(name = "steroid_use", nullable = false, length = 60)
    private SteroidUse steroidUse;

    @Enumerated(EnumType.STRING)
    @Column(name = "advanced_therapy_exposure", nullable = false, length = 60)
    private AdvancedTherapyExposure advancedTherapyExposure;

    @Column(name = "medication_notes", length = 1000)
    private String medicationNotes;

    @Column(name = "labs_collected_at")
    private LocalDate labsCollectedAt;

    @Column(name = "crp_mg_l", precision = 7, scale = 2)
    private BigDecimal crpMgL;

    @Column(name = "fecal_calprotectin_ug_g", precision = 8, scale = 2)
    private BigDecimal fecalCalprotectinUgG;

    @Column(name = "hemoglobin_g_dl", precision = 4, scale = 1)
    private BigDecimal hemoglobinGDl;

    @Column(name = "albumin_g_dl", precision = 4, scale = 1)
    private BigDecimal albuminGDl;

    @Column(name = "lab_notes", length = 1000)
    private String labNotes;

    @Enumerated(EnumType.STRING)
    @Column(name = "review_status", nullable = false, length = 40)
    private OnboardingReviewStatus reviewStatus = OnboardingReviewStatus.PENDING_REVIEW;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reviewed_by_user_id")
    private User reviewedBy;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @Column(name = "review_notes", length = 1000)
    private String reviewNotes;

    public OnboardingSubmission() {
    }

    public OnboardingSubmission(PatientProfile patientProfile, String onboardingContext, int version) {
        this.patientProfile = patientProfile;
        this.onboardingContext = onboardingContext;
        this.version = version;
    }

    public void review(OnboardingReviewStatus status, User reviewer, String notes) {
        if (status == OnboardingReviewStatus.PENDING_REVIEW) {
            throw new IllegalArgumentException("Review action cannot set PENDING_REVIEW");
        }
        this.reviewStatus = status;
        this.reviewedBy = reviewer;
        this.reviewedAt = Instant.now();
        this.reviewNotes = notes;
    }

    public Long getId() {
        return id;
    }

    public PatientProfile getPatientProfile() {
        return patientProfile;
    }

    public String getOnboardingContext() {
        return onboardingContext;
    }

    public int getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getSubmittedAt() {
        return submittedAt;
    }

    public void setSubmittedAt(Instant submittedAt) {
        this.submittedAt = submittedAt;
    }

    public IbdDiagnosisType getDiagnosisType() {
        return diagnosisType;
    }

    public void setDiagnosisType(IbdDiagnosisType diagnosisType) {
        this.diagnosisType = diagnosisType;
    }

    public Integer getDiagnosisYear() {
        return diagnosisYear;
    }

    public void setDiagnosisYear(Integer diagnosisYear) {
        this.diagnosisYear = diagnosisYear;
    }

    public String getDiseaseLocation() {
        return diseaseLocation;
    }

    public void setDiseaseLocation(String diseaseLocation) {
        this.diseaseLocation = diseaseLocation;
    }

    public String getDiseaseBehavior() {
        return diseaseBehavior;
    }

    public void setDiseaseBehavior(String diseaseBehavior) {
        this.diseaseBehavior = diseaseBehavior;
    }

    public DiseaseActivityEstimate getActivityEstimate() {
        return activityEstimate;
    }

    public void setActivityEstimate(DiseaseActivityEstimate activityEstimate) {
        this.activityEstimate = activityEstimate;
    }

    public String getCurrentMedications() {
        return currentMedications;
    }

    public void setCurrentMedications(String currentMedications) {
        this.currentMedications = currentMedications;
    }

    public SteroidUse getSteroidUse() {
        return steroidUse;
    }

    public void setSteroidUse(SteroidUse steroidUse) {
        this.steroidUse = steroidUse;
    }

    public AdvancedTherapyExposure getAdvancedTherapyExposure() {
        return advancedTherapyExposure;
    }

    public void setAdvancedTherapyExposure(AdvancedTherapyExposure advancedTherapyExposure) {
        this.advancedTherapyExposure = advancedTherapyExposure;
    }

    public String getMedicationNotes() {
        return medicationNotes;
    }

    public void setMedicationNotes(String medicationNotes) {
        this.medicationNotes = medicationNotes;
    }

    public LocalDate getLabsCollectedAt() {
        return labsCollectedAt;
    }

    public void setLabsCollectedAt(LocalDate labsCollectedAt) {
        this.labsCollectedAt = labsCollectedAt;
    }

    public BigDecimal getCrpMgL() {
        return crpMgL;
    }

    public void setCrpMgL(BigDecimal crpMgL) {
        this.crpMgL = crpMgL;
    }

    public BigDecimal getFecalCalprotectinUgG() {
        return fecalCalprotectinUgG;
    }

    public void setFecalCalprotectinUgG(BigDecimal fecalCalprotectinUgG) {
        this.fecalCalprotectinUgG = fecalCalprotectinUgG;
    }

    public BigDecimal getHemoglobinGDl() {
        return hemoglobinGDl;
    }

    public void setHemoglobinGDl(BigDecimal hemoglobinGDl) {
        this.hemoglobinGDl = hemoglobinGDl;
    }

    public BigDecimal getAlbuminGDl() {
        return albuminGDl;
    }

    public void setAlbuminGDl(BigDecimal albuminGDl) {
        this.albuminGDl = albuminGDl;
    }

    public String getLabNotes() {
        return labNotes;
    }

    public void setLabNotes(String labNotes) {
        this.labNotes = labNotes;
    }

    public OnboardingReviewStatus getReviewStatus() {
        return reviewStatus;
    }

    public User getReviewedBy() {
        return reviewedBy;
    }

    public Instant getReviewedAt() {
        return reviewedAt;
    }

    public String getReviewNotes() {
        return reviewNotes;
    }
}
