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
@Table(name = "daily_diet_log_photo_references")
public class DailyDietLogPhotoReference {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "daily_diet_log_id")
    private DailyDietLog dailyDietLog;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "patient_profile_id", nullable = false)
    private PatientProfile patientProfile;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "uploaded_by_user_id", nullable = false)
    private User uploadedByUser;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "meal_id")
    private DailyDietLogMeal meal;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DietLogPhotoStatus status = DietLogPhotoStatus.PENDING;

    @Column(name = "original_filename", length = 255)
    private String originalFilename;

    @Column(name = "content_type", length = 120)
    private String contentType;

    @Column(name = "size_bytes")
    private Long sizeBytes;

    @Column(name = "storage_key", nullable = false, length = 500)
    private String storageKey;

    @Column(name = "sha256", nullable = false, length = 64)
    private String sha256;

    @Column(length = 500)
    private String caption;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "attached_at")
    private Instant attachedAt;

    @Column(name = "removed_at")
    private Instant removedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "removed_by_user_id")
    private User removedByUser;

    protected DailyDietLogPhotoReference() {
    }

    public DailyDietLogPhotoReference(
            String originalFilename,
            String contentType,
            Long sizeBytes,
            String storageKey,
            String caption,
            int sortOrder) {
        this.originalFilename = originalFilename;
        this.contentType = contentType;
        this.sizeBytes = sizeBytes;
        this.storageKey = storageKey;
        this.caption = caption;
        this.sortOrder = sortOrder;
    }

    public static DailyDietLogPhotoReference pending(
            PatientProfile patientProfile,
            User uploadedByUser,
            String originalFilename,
            String contentType,
            Long sizeBytes,
            String sha256,
            String storageKey) {
        var photo = new DailyDietLogPhotoReference(
                originalFilename,
                contentType,
                sizeBytes,
                storageKey,
                null,
                0);
        photo.patientProfile = patientProfile;
        photo.uploadedByUser = uploadedByUser;
        photo.status = DietLogPhotoStatus.PENDING;
        photo.sha256 = sha256;
        return photo;
    }

    public void attachTo(DailyDietLog log, String caption, int sortOrder) {
        setDailyDietLog(log);
        this.status = DietLogPhotoStatus.ATTACHED;
        this.caption = caption;
        this.sortOrder = sortOrder;
        if (this.attachedAt == null) {
            this.attachedAt = Instant.now();
        }
    }

    public Long getId() {
        return id;
    }

    public DailyDietLog getDailyDietLog() {
        return dailyDietLog;
    }

    public void setDailyDietLog(DailyDietLog dailyDietLog) {
        this.dailyDietLog = dailyDietLog;
        if (dailyDietLog != null && this.patientProfile == null) {
            this.patientProfile = dailyDietLog.getPatientProfile();
        }
        if (dailyDietLog != null && this.uploadedByUser == null && dailyDietLog.getPatientProfile() != null) {
            this.uploadedByUser = dailyDietLog.getPatientProfile().getUser();
        }
        if (dailyDietLog != null && this.status == DietLogPhotoStatus.PENDING) {
            this.status = DietLogPhotoStatus.ATTACHED;
        }
        if (dailyDietLog != null && this.attachedAt == null) {
            this.attachedAt = Instant.now();
        }
        if (dailyDietLog != null && this.sha256 == null) {
            this.sha256 = "0".repeat(64);
        }
    }

    public PatientProfile getPatientProfile() {
        return patientProfile;
    }

    public void setPatientProfile(PatientProfile patientProfile) {
        this.patientProfile = patientProfile;
    }

    public User getUploadedByUser() {
        return uploadedByUser;
    }

    public void setUploadedByUser(User uploadedByUser) {
        this.uploadedByUser = uploadedByUser;
    }

    public DailyDietLogMeal getMeal() {
        return meal;
    }

    public void setMeal(DailyDietLogMeal meal) {
        this.meal = meal;
    }

    public DietLogPhotoStatus getStatus() {
        return status;
    }

    public void setStatus(DietLogPhotoStatus status) {
        this.status = status;
    }

    public String getOriginalFilename() {
        return originalFilename;
    }

    public void setOriginalFilename(String originalFilename) {
        this.originalFilename = originalFilename;
    }

    public String getContentType() {
        return contentType;
    }

    public void setContentType(String contentType) {
        this.contentType = contentType;
    }

    public Long getSizeBytes() {
        return sizeBytes;
    }

    public void setSizeBytes(Long sizeBytes) {
        this.sizeBytes = sizeBytes;
    }

    public String getStorageKey() {
        return storageKey;
    }

    public void setStorageKey(String storageKey) {
        this.storageKey = storageKey;
    }

    public String getSha256() {
        return sha256;
    }

    public void setSha256(String sha256) {
        this.sha256 = sha256;
    }

    public String getCaption() {
        return caption;
    }

    public void setCaption(String caption) {
        this.caption = caption;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(int sortOrder) {
        this.sortOrder = sortOrder;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getAttachedAt() {
        return attachedAt;
    }

    public void setAttachedAt(Instant attachedAt) {
        this.attachedAt = attachedAt;
    }

    public Instant getRemovedAt() {
        return removedAt;
    }

    public void setRemovedAt(Instant removedAt) {
        this.removedAt = removedAt;
    }

    public User getRemovedByUser() {
        return removedByUser;
    }

    public void setRemovedByUser(User removedByUser) {
        this.removedByUser = removedByUser;
    }
}
