package com.metabion.service;

import com.metabion.domain.SymptomCheckIn;
import com.metabion.domain.SymptomCheckInAnswer;
import com.metabion.domain.SymptomQuestion;
import com.metabion.domain.SymptomQuestionOption;
import com.metabion.domain.SymptomQuestionnaireVersion;
import com.metabion.dto.SymptomCheckInResponse;
import com.metabion.dto.SymptomQuestionnaireResponse;
import org.springframework.stereotype.Component;

import java.util.Comparator;

@Component
public class SymptomQuestionnaireAssembler {

    public SymptomQuestionnaireResponse questionnaire(SymptomQuestionnaireVersion version) {
        var questionnaire = version.getQuestionnaire();
        return new SymptomQuestionnaireResponse(
                questionnaire.getId(),
                questionnaire.getStableKey(),
                questionnaire.getDisplayName(),
                version.getId(),
                version.getVersionNumber(),
                version.getQuestions().stream()
                        .sorted(Comparator.comparing(SymptomQuestion::getSortOrder).thenComparing(SymptomQuestion::getId))
                        .map(this::question)
                        .toList());
    }

    public SymptomCheckInResponse checkIn(SymptomCheckIn checkIn) {
        var patient = checkIn.getPatientProfile();
        var version = checkIn.getQuestionnaireVersion();
        return new SymptomCheckInResponse(
                checkIn.getId(),
                patient == null ? null : patient.getId(),
                version == null ? null : version.getId(),
                checkIn.getCheckInDate(),
                checkIn.getFlareState(),
                checkIn.getTotalSymptomScore(),
                checkIn.getNotes(),
                checkIn.getAnswers().stream()
                        .sorted(answerComparator())
                        .map(this::answer)
                        .toList(),
                checkIn.getCreatedAt(),
                checkIn.getUpdatedAt());
    }

    private SymptomQuestionnaireResponse.QuestionResponse question(SymptomQuestion question) {
        return new SymptomQuestionnaireResponse.QuestionResponse(
                question.getId(),
                question.getStableKey(),
                question.getLabel(),
                question.getHelpText(),
                question.getAnswerType(),
                question.isRequired(),
                question.getMinNumericValue(),
                question.getMaxNumericValue(),
                question.getOptions().stream()
                        .sorted(Comparator.comparing(SymptomQuestionOption::getSortOrder)
                                .thenComparing(SymptomQuestionOption::getId))
                        .map(this::option)
                        .toList());
    }

    private SymptomQuestionnaireResponse.OptionResponse option(SymptomQuestionOption option) {
        return new SymptomQuestionnaireResponse.OptionResponse(
                option.getId(),
                option.getStableKey(),
                option.getLabel(),
                option.getNumericScore());
    }

    private SymptomCheckInResponse.AnswerResponse answer(SymptomCheckInAnswer answer) {
        var question = answer.getQuestion();
        var option = answer.getOption();
        return new SymptomCheckInResponse.AnswerResponse(
                question.getId(),
                question.getStableKey(),
                question.getLabel(),
                question.getAnswerType(),
                option == null ? null : option.getId(),
                option == null ? null : option.getStableKey(),
                option == null ? null : option.getLabel(),
                answer.getAnswerText(),
                answer.getAnswerNumeric(),
                answer.getNumericScore());
    }

    private Comparator<SymptomCheckInAnswer> answerComparator() {
        return Comparator
                .comparing((SymptomCheckInAnswer answer) -> answer.getQuestion().getSortOrder())
                .thenComparing(answer -> answer.getQuestion().getId());
    }
}
