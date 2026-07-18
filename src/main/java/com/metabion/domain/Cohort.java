package com.metabion.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;

@Entity
@Table(name = "cohorts")
public class Cohort {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by_user_id", nullable = false, updatable = false)
    private User createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @Column(name = "archived_at")
    private Instant archivedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "archived_by_user_id")
    private User archivedBy;

    public Cohort() {
    }

    public Cohort(String name, String description, User createdBy) {
        this.name = name;
        this.description = description;
        this.createdBy = Objects.requireNonNull(createdBy, "createdBy");
    }

    public void edit(String name, String description) {
        if (isArchived()) {
            throw new IllegalStateException("Archived cohort cannot be edited");
        }
        this.name = name;
        this.description = description;
    }

    public void archive(User actor, Instant at) {
        if (isArchived()) {
            throw new IllegalStateException("Cohort is already archived");
        }
        archivedBy = Objects.requireNonNull(actor, "actor");
        archivedAt = Objects.requireNonNull(at, "at");
    }

    public boolean isArchived() {
        return archivedAt != null;
    }

    @PreUpdate
    void touch() {
        this.updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public User getCreatedBy() {
        return createdBy;
    }

    public Instant getArchivedAt() {
        return archivedAt;
    }

    public User getArchivedBy() {
        return archivedBy;
    }
}
