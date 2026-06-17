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
@Table(name = "education_module_localizations")
public class EducationModuleLocalization {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "module_version_id", nullable = false)
    private EducationModuleVersion moduleVersion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private EducationLanguage language;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(nullable = false, length = 1000)
    private String summary;

    public EducationModuleLocalization() {
    }

    public EducationModuleLocalization(EducationModuleVersion moduleVersion, EducationLanguage language, String title, String summary) {
        this.moduleVersion = moduleVersion;
        this.language = language;
        this.title = title;
        this.summary = summary;
    }

    public Long getId() {
        return id;
    }

    public EducationModuleVersion getModuleVersion() {
        return moduleVersion;
    }

    void setModuleVersion(EducationModuleVersion moduleVersion) {
        this.moduleVersion = moduleVersion;
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
}
