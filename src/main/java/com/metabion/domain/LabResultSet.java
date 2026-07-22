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
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Entity
@Table(name = "lab_result_sets")
public class LabResultSet {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "patient_profile_id", nullable = false)
    private PatientProfile patientProfile;
    @Column(name = "collection_date", nullable = false)
    private LocalDate collectionDate;
    @Column(length = 2000)
    private String notes;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 32)
    private LabResultSource source;
    @Enumerated(EnumType.STRING) @Column(name = "confirmation_status", nullable = false, length = 32)
    private LabResultConfirmationStatus confirmationStatus;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "created_by_user_id", nullable = false)
    private User createdByUser;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    @Version @Column(nullable = false)
    private long version;
    @Column(name = "removed_at")
    private Instant removedAt;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "removed_by_user_id")
    private User removedByUser;
    @Column(name = "removal_reason", length = 500)
    private String removalReason;
    @OneToMany(mappedBy = "resultSet", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<LabResult> results = new ArrayList<>();

    protected LabResultSet() { }

    public LabResultSet(PatientProfile patientProfile, LocalDate collectionDate, String notes,
                        LabResultSource source, LabResultConfirmationStatus confirmationStatus,
                        User createdByUser, Instant now) {
        this.patientProfile = Objects.requireNonNull(patientProfile);
        this.collectionDate = Objects.requireNonNull(collectionDate);
        this.notes = trimToNull(notes);
        this.source = Objects.requireNonNull(source);
        this.confirmationStatus = Objects.requireNonNull(confirmationStatus);
        this.createdByUser = Objects.requireNonNull(createdByUser);
        this.createdAt = Objects.requireNonNull(now);
        this.updatedAt = now;
    }

    public void replaceResults(List<LabResult> replacements, Instant now) {
        var replacementsByCode = new HashMap<String, LabResult>();
        replacements.forEach(result -> {
            if (result.getResultSet() != this) {
                throw new IllegalArgumentException("lab result must belong to this result set");
            }
            replacementsByCode.put(result.getTestDefinition().getCode(), result);
        });

        Map<String, LabResult> existingByCode = new HashMap<>();
        results.forEach(result -> existingByCode.put(result.getTestDefinition().getCode(), result));
        results.removeIf(result -> !replacementsByCode.containsKey(result.getTestDefinition().getCode()));
        replacements.forEach(replacement -> {
            var existing = existingByCode.get(replacement.getTestDefinition().getCode());
            if (existing == null) {
                results.add(replacement);
                return;
            }
            existing.updateMeasurements(replacement.getReportedValue(), replacement.getReportedUnit(),
                    replacement.getCanonicalValue(), replacement.getCanonicalUnit(),
                    replacement.getReferenceLower(), replacement.getReferenceUpper());
        });
        updatedAt = now;
    }

    public void updateDetails(LocalDate collectionDate, String notes, Instant now) {
        this.collectionDate = Objects.requireNonNull(collectionDate);
        this.notes = trimToNull(notes);
        this.updatedAt = Objects.requireNonNull(now);
    }

    public void markRemoved(User actor, String reason, Instant now) {
        if (removedAt != null) {
            throw new IllegalStateException("lab result set is already removed");
        }
        removedAt = now;
        removedByUser = Objects.requireNonNull(actor);
        removalReason = trimToNull(reason);
        updatedAt = now;
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        var trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    public Long getId() { return id; }
    public PatientProfile getPatientProfile() { return patientProfile; }
    public LocalDate getCollectionDate() { return collectionDate; }
    public String getNotes() { return notes; }
    public LabResultSource getSource() { return source; }
    public LabResultConfirmationStatus getConfirmationStatus() { return confirmationStatus; }
    public User getCreatedByUser() { return createdByUser; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public long getVersion() { return version; }
    public Instant getRemovedAt() { return removedAt; }
    public User getRemovedByUser() { return removedByUser; }
    public String getRemovalReason() { return removalReason; }
    public List<LabResult> getResults() { return results; }
}
