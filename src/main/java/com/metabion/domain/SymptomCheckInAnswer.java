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
@Table(name = "symptom_check_in_answers")
public class SymptomCheckInAnswer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "check_in_id", nullable = false)
    private SymptomCheckIn checkIn;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "question_id", nullable = false)
    private SymptomQuestion question;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "option_id")
    private SymptomQuestionOption option;

    @Column(name = "answer_text", length = 1000)
    private String answerText;

    @Column(name = "answer_numeric", precision = 8, scale = 2)
    private BigDecimal answerNumeric;

    @Column(name = "numeric_score", nullable = false, precision = 8, scale = 2)
    private BigDecimal numericScore = BigDecimal.ZERO;

    protected SymptomCheckInAnswer() {
    }

    public static SymptomCheckInAnswer choice(
            SymptomCheckIn checkIn,
            SymptomQuestion question,
            SymptomQuestionOption option) {
        if (!optionBelongsToQuestion(option, question)) {
            throw new IllegalArgumentException("Answer option must belong to the answered question");
        }
        var answer = new SymptomCheckInAnswer();
        answer.setQuestion(question);
        answer.setOption(option);
        answer.setNumericScore(option.getNumericScore().multiply(question.getScoreWeight()));
        checkIn.addAnswer(answer);
        return answer;
    }

    private static boolean optionBelongsToQuestion(SymptomQuestionOption option, SymptomQuestion question) {
        if (option == null || question == null || option.getQuestion() == null) {
            return false;
        }
        if (option.getQuestion() == question) {
            return true;
        }
        if (option.getQuestion().getId() == null || question.getId() == null) {
            return false;
        }
        return option.getQuestion().getId().equals(question.getId());
    }

    public Long getId() {
        return id;
    }

    public SymptomCheckIn getCheckIn() {
        return checkIn;
    }

    public void setCheckIn(SymptomCheckIn checkIn) {
        this.checkIn = checkIn;
    }

    public SymptomQuestion getQuestion() {
        return question;
    }

    public void setQuestion(SymptomQuestion question) {
        this.question = question;
    }

    public SymptomQuestionOption getOption() {
        return option;
    }

    public void setOption(SymptomQuestionOption option) {
        this.option = option;
    }

    public String getAnswerText() {
        return answerText;
    }

    public void setAnswerText(String answerText) {
        this.answerText = answerText;
    }

    public BigDecimal getAnswerNumeric() {
        return answerNumeric;
    }

    public void setAnswerNumeric(BigDecimal answerNumeric) {
        this.answerNumeric = answerNumeric;
    }

    public BigDecimal getNumericScore() {
        return numericScore;
    }

    public void setNumericScore(BigDecimal numericScore) {
        this.numericScore = numericScore;
    }
}
