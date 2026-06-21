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
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "education_module_versions")
public class EducationModuleVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "module_id", nullable = false)
    private EducationModule module;

    @Column(nullable = false)
    private int version;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private EducationContentStatus status = EducationContentStatus.DRAFT;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "author_user_id", nullable = false)
    private User author;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reviewed_by_user_id")
    private User reviewedBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "published_by_user_id")
    private User publishedBy;

    @Column(name = "review_bypassed", nullable = false)
    private boolean reviewBypassed = false;

    @Column(name = "review_notes", length = 2000)
    private String reviewNotes;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "submitted_at")
    private Instant submittedAt;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "archived_at")
    private Instant archivedAt;

    @OneToMany(mappedBy = "moduleVersion", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("language ASC")
    private List<EducationModuleLocalization> localizations = new ArrayList<>();

    @OneToMany(mappedBy = "moduleVersion", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("sortOrder ASC")
    private List<EducationLessonVersion> lessons = new ArrayList<>();

    public EducationModuleVersion() {
    }

    public EducationModuleVersion(EducationModule module, int version, User author) {
        this.module = module;
        this.version = version;
        this.author = author;
    }

    public void submitForReview() {
        if (status != EducationContentStatus.DRAFT && status != EducationContentStatus.REJECTED) {
            throw new IllegalArgumentException("Only draft or rejected content can be submitted");
        }
        reviewedBy = null;
        reviewedAt = null;
        reviewNotes = null;
        status = EducationContentStatus.IN_REVIEW;
        submittedAt = Instant.now();
    }

    public void approve(User reviewer, String notes) {
        if (status != EducationContentStatus.IN_REVIEW) {
            throw new IllegalArgumentException("Only in-review content can be approved");
        }
        if (!author.hasRole(RoleName.ADMIN) && sameUser(author, reviewer)) {
            throw new IllegalArgumentException("Author cannot approve own content");
        }
        status = EducationContentStatus.APPROVED;
        reviewedBy = reviewer;
        reviewedAt = Instant.now();
        reviewNotes = notes;
    }

    public void reject(User reviewer, String notes) {
        if (status != EducationContentStatus.IN_REVIEW) {
            throw new IllegalArgumentException("Only in-review content can be rejected");
        }
        status = EducationContentStatus.REJECTED;
        reviewedBy = reviewer;
        reviewedAt = Instant.now();
        reviewNotes = notes;
    }

    public void publish(User publisher) {
        if (status != EducationContentStatus.APPROVED) {
            throw new IllegalArgumentException("Only approved content can be published");
        }
        status = EducationContentStatus.PUBLISHED;
        publishedBy = publisher;
        publishedAt = Instant.now();
    }

    public void publishDirectlyByAdmin(User admin) {
        if (!admin.hasRole(RoleName.ADMIN)) {
            throw new IllegalArgumentException("Admin role is required for direct publish");
        }
        if (!sameUser(author, admin)) {
            throw new IllegalArgumentException("Direct publish is only for admin-authored content");
        }
        if (status != EducationContentStatus.DRAFT) {
            throw new IllegalArgumentException("Only draft content can be directly published");
        }
        status = EducationContentStatus.PUBLISHED;
        reviewedBy = admin;
        reviewedAt = Instant.now();
        reviewBypassed = true;
        publishedBy = admin;
        publishedAt = Instant.now();
    }

    public void archive() {
        if (status != EducationContentStatus.PUBLISHED) {
            throw new IllegalArgumentException("Only published content can be archived");
        }
        status = EducationContentStatus.ARCHIVED;
        archivedAt = Instant.now();
    }

    public boolean isEditable() {
        return status == EducationContentStatus.DRAFT || status == EducationContentStatus.REJECTED;
    }

    public void addLocalization(EducationModuleLocalization localization) {
        localization.setModuleVersion(this);
        localizations.add(localization);
    }

    public void addLesson(EducationLessonVersion lesson) {
        lesson.setModuleVersion(this);
        lessons.add(lesson);
    }

    private boolean sameUser(User left, User right) {
        if (left == null || right == null) {
            return false;
        }
        if (left.getId() != null && right.getId() != null) {
            return left.getId().equals(right.getId());
        }
        return left == right;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public EducationModule getModule() {
        return module;
    }

    public int getVersion() {
        return version;
    }

    public EducationContentStatus getStatus() {
        return status;
    }

    public User getAuthor() {
        return author;
    }

    public User getReviewedBy() {
        return reviewedBy;
    }

    public User getPublishedBy() {
        return publishedBy;
    }

    public boolean isReviewBypassed() {
        return reviewBypassed;
    }

    public String getReviewNotes() {
        return reviewNotes;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getSubmittedAt() {
        return submittedAt;
    }

    public Instant getReviewedAt() {
        return reviewedAt;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public Instant getArchivedAt() {
        return archivedAt;
    }

    public List<EducationModuleLocalization> getLocalizations() {
        return localizations;
    }

    public List<EducationLessonVersion> getLessons() {
        return lessons;
    }
}
