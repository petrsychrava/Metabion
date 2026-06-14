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
import jakarta.persistence.OrderBy;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(
        name = "daily_diet_logs",
        uniqueConstraints = @UniqueConstraint(
                name = "ux_daily_diet_logs_patient_date",
                columnNames = {"patient_profile_id", "log_date"}))
public class DailyDietLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "patient_profile_id", nullable = false)
    private PatientProfile patientProfile;

    @Column(name = "log_date", nullable = false)
    private LocalDate logDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "adherence_level", nullable = false, length = 40)
    private DietAdherenceLevel adherenceLevel = DietAdherenceLevel.FULL;

    @Enumerated(EnumType.STRING)
    @Column(name = "appetite_level", nullable = false, length = 40)
    private AppetiteLevel appetiteLevel = AppetiteLevel.NORMAL;

    @Column(length = 1000)
    private String notes;

    @Column(length = 2000)
    private String metadata;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @OneToMany(mappedBy = "dailyDietLog", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("sortOrder ASC, id ASC")
    private List<DailyDietLogMeal> meals = new ArrayList<>();

    @OneToMany(mappedBy = "dailyDietLog", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("sortOrder ASC, id ASC")
    private List<DailyDietLogDeviation> deviations = new ArrayList<>();

    @OneToMany(mappedBy = "dailyDietLog", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("sortOrder ASC, id ASC")
    private List<DailyDietLogPhotoReference> photoReferences = new ArrayList<>();

    protected DailyDietLog() {
    }

    public DailyDietLog(PatientProfile patientProfile, LocalDate logDate) {
        this.patientProfile = patientProfile;
        this.logDate = logDate;
    }

    @PreUpdate
    void touch() {
        this.updatedAt = Instant.now();
    }

    public void addMeal(DailyDietLogMeal meal) {
        meal.setDailyDietLog(this);
        this.meals.add(meal);
    }

    public void addDeviation(DailyDietLogDeviation deviation) {
        deviation.setDailyDietLog(this);
        this.deviations.add(deviation);
    }

    public void addPhotoReference(DailyDietLogPhotoReference photoReference) {
        photoReference.setDailyDietLog(this);
        this.photoReferences.add(photoReference);
    }

    public void replaceMeals(List<DailyDietLogMeal> meals) {
        this.meals.clear();
        meals.forEach(this::addMeal);
    }

    public void replaceDeviations(List<DailyDietLogDeviation> deviations) {
        this.deviations.clear();
        deviations.forEach(this::addDeviation);
    }

    public void replacePhotoReferences(List<DailyDietLogPhotoReference> photoReferences) {
        this.photoReferences.clear();
        photoReferences.forEach(this::addPhotoReference);
    }

    public void replaceChildren(
            List<DailyDietLogMeal> meals,
            List<DailyDietLogDeviation> deviations) {
        replaceMeals(meals);
        replaceDeviations(deviations);
    }

    public void replaceChildren(
            List<DailyDietLogMeal> meals,
            List<DailyDietLogDeviation> deviations,
            List<DailyDietLogPhotoReference> photoReferences) {
        replaceChildren(meals, deviations);
        replacePhotoReferences(photoReferences);
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

    public LocalDate getLogDate() {
        return logDate;
    }

    public void setLogDate(LocalDate logDate) {
        this.logDate = logDate;
    }

    public DietAdherenceLevel getAdherenceLevel() {
        return adherenceLevel;
    }

    public void setAdherenceLevel(DietAdherenceLevel adherenceLevel) {
        this.adherenceLevel = adherenceLevel;
    }

    public AppetiteLevel getAppetiteLevel() {
        return appetiteLevel;
    }

    public void setAppetiteLevel(AppetiteLevel appetiteLevel) {
        this.appetiteLevel = appetiteLevel;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public String getMetadata() {
        return metadata;
    }

    public void setMetadata(String metadata) {
        this.metadata = metadata;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public List<DailyDietLogMeal> getMeals() {
        return meals;
    }

    public List<DailyDietLogDeviation> getDeviations() {
        return deviations;
    }

    public List<DailyDietLogPhotoReference> getPhotoReferences() {
        return photoReferences;
    }
}
