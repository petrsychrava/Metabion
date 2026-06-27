package com.metabion.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.math.BigDecimal;

@Entity
@Table(name = "symptom_question_options")
public class SymptomQuestionOption {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "question_id", nullable = false)
    private SymptomQuestion question;

    @Column(name = "stable_key", nullable = false, length = 120)
    private String stableKey;

    @Column(nullable = false, length = 300)
    private String label;

    @Column(name = "numeric_score", nullable = false, precision = 8, scale = 2)
    private BigDecimal numericScore = BigDecimal.ZERO;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    protected SymptomQuestionOption() {
    }

    public SymptomQuestionOption(String stableKey, String label, BigDecimal numericScore, int sortOrder) {
        this.stableKey = stableKey;
        this.label = label;
        this.numericScore = numericScore;
        this.sortOrder = sortOrder;
    }

    public Long getId() {
        return id;
    }

    public SymptomQuestion getQuestion() {
        return question;
    }

    public void setQuestion(SymptomQuestion question) {
        this.question = question;
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

    public BigDecimal getNumericScore() {
        return numericScore;
    }

    public void setNumericScore(BigDecimal numericScore) {
        this.numericScore = numericScore;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(int sortOrder) {
        this.sortOrder = sortOrder;
    }
}
