package com.metabion.domain;

import jakarta.persistence.CascadeType;
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
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "symptom_check_ins")
public class SymptomCheckIn {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "patient_profile_id", nullable = false)
    private PatientProfile patientProfile;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "questionnaire_version_id", nullable = false)
    private SymptomQuestionnaireVersion questionnaireVersion;

    @Column(name = "check_in_date", nullable = false)
    private LocalDate checkInDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "flare_state", nullable = false, length = 40)
    private FlareState flareState;

    @Column(length = 1000)
    private String notes;

    @Column(name = "total_symptom_score", nullable = false, precision = 8, scale = 2)
    private BigDecimal totalSymptomScore = BigDecimal.ZERO;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @OneToMany(mappedBy = "checkIn", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<SymptomCheckInAnswer> answers = new ArrayList<>();

    protected SymptomCheckIn() {
    }

    public SymptomCheckIn(
            PatientProfile patientProfile,
            SymptomQuestionnaireVersion questionnaireVersion,
            LocalDate checkInDate,
            FlareState flareState) {
        this.patientProfile = patientProfile;
        this.questionnaireVersion = questionnaireVersion;
        this.checkInDate = checkInDate;
        this.flareState = flareState;
    }

    @PreUpdate
    void touch() {
        this.updatedAt = Instant.now();
    }

    public void clearAnswers() {
        new ArrayList<>(answers).forEach(answer -> answer.setCheckIn(null));
        answers.clear();
    }

    public void addAnswer(SymptomCheckInAnswer answer) {
        answers.add(answer);
        answer.setCheckIn(this);
    }

    public Long getId() {
        return id;
    }

    public PatientProfile getPatientProfile() {
        return patientProfile;
    }

    public void setPatientProfile(PatientProfile patientProfile) {
        this.patientProfile = patientProfile;
    }

    public SymptomQuestionnaireVersion getQuestionnaireVersion() {
        return questionnaireVersion;
    }

    public void setQuestionnaireVersion(SymptomQuestionnaireVersion questionnaireVersion) {
        this.questionnaireVersion = questionnaireVersion;
    }

    public LocalDate getCheckInDate() {
        return checkInDate;
    }

    public void setCheckInDate(LocalDate checkInDate) {
        this.checkInDate = checkInDate;
    }

    public FlareState getFlareState() {
        return flareState;
    }

    public void setFlareState(FlareState flareState) {
        this.flareState = flareState;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public BigDecimal getTotalSymptomScore() {
        return totalSymptomScore;
    }

    public void setTotalSymptomScore(BigDecimal totalSymptomScore) {
        this.totalSymptomScore = totalSymptomScore;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public List<SymptomCheckInAnswer> getAnswers() {
        return answers;
    }
}
