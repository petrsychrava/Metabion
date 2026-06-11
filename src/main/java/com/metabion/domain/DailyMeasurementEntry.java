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

@Entity
@Table(name = "daily_measurement_entries")
public class DailyMeasurementEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "patient_profile_id", nullable = false)
    private PatientProfile patientProfile;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "daily_diet_log_id")
    private DailyDietLog dailyDietLog;

    @Enumerated(EnumType.STRING)
    @Column(name = "measurement_type", nullable = false, length = 40)
    private MeasurementType measurementType;

    @Column(nullable = false, precision = 8, scale = 2)
    private BigDecimal value;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MeasurementUnit unit;

    @Column(name = "measured_at", nullable = false)
    private Instant measuredAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private MeasurementContext context;

    @Column(length = 1000)
    private String notes;

    @Column(length = 2000)
    private String metadata;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected DailyMeasurementEntry() {
    }

    public DailyMeasurementEntry(
            PatientProfile patientProfile,
            DailyDietLog dailyDietLog,
            MeasurementType measurementType,
            BigDecimal value,
            MeasurementUnit unit,
            Instant measuredAt,
            MeasurementContext context,
            String notes) {
        this(patientProfile, dailyDietLog, measurementType, value, unit, measuredAt, context, notes, null);
    }

    public DailyMeasurementEntry(
            PatientProfile patientProfile,
            DailyDietLog dailyDietLog,
            MeasurementType measurementType,
            BigDecimal value,
            MeasurementUnit unit,
            Instant measuredAt,
            MeasurementContext context,
            String notes,
            String metadata) {
        this.patientProfile = patientProfile;
        this.dailyDietLog = dailyDietLog;
        this.measurementType = measurementType;
        this.value = value;
        this.unit = unit;
        this.measuredAt = measuredAt;
        this.context = context;
        this.notes = notes;
        this.metadata = metadata;
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

    public DailyDietLog getDailyDietLog() {
        return dailyDietLog;
    }

    public void setDailyDietLog(DailyDietLog dailyDietLog) {
        this.dailyDietLog = dailyDietLog;
    }

    public MeasurementType getMeasurementType() {
        return measurementType;
    }

    public void setMeasurementType(MeasurementType measurementType) {
        this.measurementType = measurementType;
    }

    public BigDecimal getValue() {
        return value;
    }

    public void setValue(BigDecimal value) {
        this.value = value;
    }

    public MeasurementUnit getUnit() {
        return unit;
    }

    public void setUnit(MeasurementUnit unit) {
        this.unit = unit;
    }

    public Instant getMeasuredAt() {
        return measuredAt;
    }

    public void setMeasuredAt(Instant measuredAt) {
        this.measuredAt = measuredAt;
    }

    public MeasurementContext getContext() {
        return context;
    }

    public void setContext(MeasurementContext context) {
        this.context = context;
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
}
