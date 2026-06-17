package com.metabion.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "education_modules")
public class EducationModule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 120)
    private String slug;

    @Column(nullable = false, length = 80)
    private String topic;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "current_published_version_id")
    private EducationModuleVersion currentPublishedVersion;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public EducationModule() {
    }

    public EducationModule(String slug, String topic, int sortOrder) {
        this.slug = slug;
        this.topic = topic;
        this.sortOrder = sortOrder;
    }

    @PreUpdate
    void touch() {
        this.updatedAt = Instant.now();
    }

    public void publish(EducationModuleVersion version) {
        if (version == null || version.getStatus() != EducationContentStatus.PUBLISHED) {
            throw new IllegalArgumentException("Current published version must be published");
        }
        if (version.getModule() != this && (id == null || version.getModule().getId() == null
                || !id.equals(version.getModule().getId()))) {
            throw new IllegalArgumentException("Published version must belong to the same module");
        }
        this.currentPublishedVersion = version;
        touch();
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getSlug() {
        return slug;
    }

    public void setSlug(String slug) {
        this.slug = slug;
    }

    public String getTopic() {
        return topic;
    }

    public void setTopic(String topic) {
        this.topic = topic;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(int sortOrder) {
        this.sortOrder = sortOrder;
    }

    public EducationModuleVersion getCurrentPublishedVersion() {
        return currentPublishedVersion;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
