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
@Table(name = "symptom_questionnaire_versions")
public class SymptomQuestionnaireVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "questionnaire_id", nullable = false)
    private SymptomQuestionnaire questionnaire;

    @Column(name = "version_number", nullable = false)
    private int versionNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private QuestionnaireVersionStatus status = QuestionnaireVersionStatus.DRAFT;

    @Enumerated(EnumType.STRING)
    @Column(name = "scoring_method", nullable = false, length = 40)
    private SymptomScoringMethod scoringMethod = SymptomScoringMethod.SUM;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "published_at")
    private Instant publishedAt;

    @OneToMany(mappedBy = "questionnaireVersion", cascade = CascadeType.ALL)
    @OrderBy("sortOrder ASC, id ASC")
    private List<SymptomQuestion> questions = new ArrayList<>();

    protected SymptomQuestionnaireVersion() {
    }

    public SymptomQuestionnaireVersion(SymptomQuestionnaire questionnaire, int versionNumber) {
        this.questionnaire = questionnaire;
        this.versionNumber = versionNumber;
    }

    public void addQuestion(SymptomQuestion question) {
        question.setQuestionnaireVersion(this);
        questions.add(question);
    }

    public Long getId() {
        return id;
    }

    public SymptomQuestionnaire getQuestionnaire() {
        return questionnaire;
    }

    public void setQuestionnaire(SymptomQuestionnaire questionnaire) {
        this.questionnaire = questionnaire;
    }

    public int getVersionNumber() {
        return versionNumber;
    }

    public void setVersionNumber(int versionNumber) {
        this.versionNumber = versionNumber;
    }

    public QuestionnaireVersionStatus getStatus() {
        return status;
    }

    public void setStatus(QuestionnaireVersionStatus status) {
        this.status = status;
    }

    public SymptomScoringMethod getScoringMethod() {
        return scoringMethod;
    }

    public void setScoringMethod(SymptomScoringMethod scoringMethod) {
        this.scoringMethod = scoringMethod;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public void setPublishedAt(Instant publishedAt) {
        this.publishedAt = publishedAt;
    }

    public List<SymptomQuestion> getQuestions() {
        return questions;
    }
}
