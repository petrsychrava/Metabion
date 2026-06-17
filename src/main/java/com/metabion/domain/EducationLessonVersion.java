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
        this.moduleVersion = moduleVersion;
        this.lesson = lesson;
        this.sortOrder = sortOrder;
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
        this.moduleVersion = moduleVersion;
    }

    public EducationLesson getLesson() {
        return lesson;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public List<EducationLessonLocalization> getLocalizations() {
        return localizations;
    }
}
