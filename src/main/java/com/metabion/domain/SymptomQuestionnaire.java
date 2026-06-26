package com.metabion.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "symptom_questionnaires")
public class SymptomQuestionnaire {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "stable_key", nullable = false, unique = true, length = 120)
    private String stableKey;

    @Column(name = "display_name", nullable = false, length = 200)
    private String displayName;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @OneToMany(mappedBy = "questionnaire", cascade = CascadeType.ALL)
    private List<SymptomQuestionnaireVersion> versions = new ArrayList<>();

    protected SymptomQuestionnaire() {
    }

    public SymptomQuestionnaire(String stableKey, String displayName) {
        this.stableKey = stableKey;
        this.displayName = displayName;
    }

    @PreUpdate
    void touch() {
        this.updatedAt = Instant.now();
    }

    public void addVersion(SymptomQuestionnaireVersion version) {
        version.setQuestionnaire(this);
        versions.add(version);
    }

    public Long getId() {
        return id;
    }

    public String getStableKey() {
        return stableKey;
    }

    public void setStableKey(String stableKey) {
        this.stableKey = stableKey;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public List<SymptomQuestionnaireVersion> getVersions() {
        return versions;
    }
}
