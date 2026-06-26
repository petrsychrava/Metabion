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

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "symptom_questions")
public class SymptomQuestion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "questionnaire_version_id", nullable = false)
    private SymptomQuestionnaireVersion questionnaireVersion;

    @Column(name = "stable_key", nullable = false, length = 120)
    private String stableKey;

    @Column(nullable = false, length = 500)
    private String label;

    @Column(name = "help_text", length = 1000)
    private String helpText;

    @Enumerated(EnumType.STRING)
    @Column(name = "answer_type", nullable = false, length = 40)
    private SymptomAnswerType answerType;

    @Column(nullable = false)
    private boolean required = true;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "score_weight", nullable = false, precision = 8, scale = 2)
    private BigDecimal scoreWeight = BigDecimal.ONE;

    @Column(name = "min_numeric_value", precision = 8, scale = 2)
    private BigDecimal minNumericValue;

    @Column(name = "max_numeric_value", precision = 8, scale = 2)
    private BigDecimal maxNumericValue;

    @OneToMany(mappedBy = "question", cascade = CascadeType.ALL)
    @OrderBy("sortOrder ASC, id ASC")
    private List<SymptomQuestionOption> options = new ArrayList<>();

    protected SymptomQuestion() {
    }

    public SymptomQuestion(String stableKey, String label, SymptomAnswerType answerType, int sortOrder) {
        this.stableKey = stableKey;
        this.label = label;
        this.answerType = answerType;
        this.sortOrder = sortOrder;
    }

    public void addOption(SymptomQuestionOption option) {
        option.setQuestion(this);
        options.add(option);
    }

    public Long getId() {
        return id;
    }

    public SymptomQuestionnaireVersion getQuestionnaireVersion() {
        return questionnaireVersion;
    }

    public void setQuestionnaireVersion(SymptomQuestionnaireVersion questionnaireVersion) {
        this.questionnaireVersion = questionnaireVersion;
    }

    public String getStableKey() {
        return stableKey;
    }

    public void setStableKey(String stableKey) {
        this.stableKey = stableKey;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public String getHelpText() {
        return helpText;
    }

    public void setHelpText(String helpText) {
        this.helpText = helpText;
    }

    public SymptomAnswerType getAnswerType() {
        return answerType;
    }

    public void setAnswerType(SymptomAnswerType answerType) {
        this.answerType = answerType;
    }

    public boolean isRequired() {
        return required;
    }

    public void setRequired(boolean required) {
        this.required = required;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(int sortOrder) {
        this.sortOrder = sortOrder;
    }

    public BigDecimal getScoreWeight() {
        return scoreWeight;
    }

    public void setScoreWeight(BigDecimal scoreWeight) {
        this.scoreWeight = scoreWeight;
    }

    public BigDecimal getMinNumericValue() {
        return minNumericValue;
    }

    public void setMinNumericValue(BigDecimal minNumericValue) {
        this.minNumericValue = minNumericValue;
    }

    public BigDecimal getMaxNumericValue() {
        return maxNumericValue;
    }

    public void setMaxNumericValue(BigDecimal maxNumericValue) {
        this.maxNumericValue = maxNumericValue;
    }

    public List<SymptomQuestionOption> getOptions() {
        return options;
    }
}
