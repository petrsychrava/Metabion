package com.metabion.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "education_lesson_versions")
public class EducationLessonVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "module_version_id", nullable = false)
    private EducationModuleVersion moduleVersion;

    @Column(name = "module_id", nullable = false)
    private Long moduleId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "lesson_id", nullable = false)
    private EducationLesson lesson;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @OneToMany(mappedBy = "lessonVersion", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("language ASC")
    private List<EducationLessonLocalization> localizations = new ArrayList<>();

    public EducationLessonVersion() {
    }

    public EducationLessonVersion(EducationModuleVersion moduleVersion, EducationLesson lesson, int sortOrder) {
        validateSameModule(moduleVersion, lesson);
        this.moduleVersion = moduleVersion;
        this.moduleId = moduleId(moduleVersion, lesson);
        this.lesson = lesson;
        this.sortOrder = sortOrder;
    }

    @PrePersist
    @PreUpdate
    void syncModuleId() {
        this.moduleId = moduleId(moduleVersion, lesson);
    }

    public void addLocalization(EducationLessonLocalization localization) {
        localization.setLessonVersion(this);
        localizations.add(localization);
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public EducationModuleVersion getModuleVersion() {
        return moduleVersion;
    }

    void setModuleVersion(EducationModuleVersion moduleVersion) {
        validateSameModule(moduleVersion, lesson);
        this.moduleVersion = moduleVersion;
        this.moduleId = moduleId(moduleVersion, lesson);
    }

    public EducationLesson getLesson() {
        return lesson;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public Long getModuleId() {
        return moduleId;
    }

    public List<EducationLessonLocalization> getLocalizations() {
        return localizations;
    }

    private void validateSameModule(EducationModuleVersion moduleVersion, EducationLesson lesson) {
        if (moduleVersion == null || lesson == null) {
            return;
        }
        var versionModule = moduleVersion.getModule();
        var lessonModule = lesson.getModule();
        if (versionModule != lessonModule && (versionModule.getId() == null || lessonModule.getId() == null
                || !versionModule.getId().equals(lessonModule.getId()))) {
            throw new IllegalArgumentException("Lesson version requires lesson from the same module");
        }
    }

    private Long moduleId(EducationModuleVersion moduleVersion, EducationLesson lesson) {
        if (moduleVersion != null && moduleVersion.getModule().getId() != null) {
            return moduleVersion.getModule().getId();
        }
        if (lesson != null) {
            return lesson.getModule().getId();
        }
        return null;
    }
}
