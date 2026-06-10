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

@Entity
@Table(name = "daily_diet_log_photo_references")
public class DailyDietLogPhotoReference {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "daily_diet_log_id", nullable = false)
    private DailyDietLog dailyDietLog;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "meal_id")
    private DailyDietLogMeal meal;

    @Column(name = "original_filename", length = 255)
    private String originalFilename;

    @Column(name = "content_type", length = 120)
    private String contentType;

    @Column(name = "size_bytes")
    private Long sizeBytes;

    @Column(name = "storage_key", length = 500)
    private String storageKey;

    @Column(length = 500)
    private String caption;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

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

    public Long getId() {
        return id;
    }

    public DailyDietLog getDailyDietLog() {
        return dailyDietLog;
    }

    public void setDailyDietLog(DailyDietLog dailyDietLog) {
        this.dailyDietLog = dailyDietLog;
    }

    public DailyDietLogMeal getMeal() {
        return meal;
    }

    public void setMeal(DailyDietLogMeal meal) {
        this.meal = meal;
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
}
