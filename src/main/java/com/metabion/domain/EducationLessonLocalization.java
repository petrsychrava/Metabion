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

@Entity
@Table(name = "education_lesson_localizations")
public class EducationLessonLocalization {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "lesson_version_id", nullable = false)
    private EducationLessonVersion lessonVersion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private EducationLanguage language;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(nullable = false, length = 1000)
    private String summary;

    @Column(name = "body_markdown", nullable = false, columnDefinition = "text")
    private String bodyMarkdown;

    public EducationLessonLocalization() {
    }

    public EducationLessonLocalization(
            EducationLessonVersion lessonVersion,
            EducationLanguage language,
            String title,
            String summary,
            String bodyMarkdown) {
        this.lessonVersion = lessonVersion;
        this.language = language;
        this.title = title;
        this.summary = summary;
        this.bodyMarkdown = bodyMarkdown;
    }

    public Long getId() {
        return id;
    }

    public EducationLessonVersion getLessonVersion() {
        return lessonVersion;
    }

    void setLessonVersion(EducationLessonVersion lessonVersion) {
        this.lessonVersion = lessonVersion;
    }

    public EducationLanguage getLanguage() {
        return language;
    }

    public String getTitle() {
        return title;
    }

    public String getSummary() {
        return summary;
    }

    public String getBodyMarkdown() {
        return bodyMarkdown;
    }
}
