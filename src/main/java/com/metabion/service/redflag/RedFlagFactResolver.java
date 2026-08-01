package com.metabion.service.redflag;

import com.metabion.domain.LabResultSet;
import com.metabion.domain.RedFlagSourceType;
import com.metabion.domain.Sex;
import com.metabion.domain.SymptomCheckIn;
import com.metabion.repository.SymptomCheckInRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class RedFlagFactResolver {

    private static final int LAB_SYMPTOM_LOOKBACK_DAYS = 7;

    private final SymptomCheckInRepository checkIns;
    private final RedFlagFactRegistry registry;

    public RedFlagFactResolver(SymptomCheckInRepository checkIns, RedFlagFactRegistry registry) {
        this.checkIns = checkIns;
        this.registry = registry;
    }

    public RedFlagEvaluationInput forSymptom(SymptomCheckIn checkIn) {
        return new RedFlagEvaluationInput(symptomFacts(checkIn), profileFacts(
                checkIn.getPatientProfile(), checkIn.getCheckInDate()), List.of());
    }

    public RedFlagEvaluationInput forLab(LabResultSet resultSet) {
        var observedOn = resultSet.getCollectionDate();
        var lookback = checkIns.findForRedFlagContext(resultSet.getPatientProfile().getId(),
                        observedOn.minusDays(LAB_SYMPTOM_LOOKBACK_DAYS), observedOn).stream()
                .map(this::symptomFacts)
                .toList();
        return new RedFlagEvaluationInput(labFacts(resultSet),
                profileFacts(resultSet.getPatientProfile(), observedOn), lookback);
    }

    public RedFlagEvaluationInput forLabRemoval(LabResultSet resultSet) {
        var trigger = new RedFlagFactSet(RedFlagSourceType.LAB_RESULT_SET,
                resultSet.getId(), resultSet.getCollectionDate(), List.of());
        return new RedFlagEvaluationInput(trigger,
                profileFacts(resultSet.getPatientProfile(), resultSet.getCollectionDate()), List.of());
    }

    private RedFlagFactSet symptomFacts(SymptomCheckIn checkIn) {
        var facts = new ArrayList<RedFlagFact>();
        if (checkIn.getFlareState() != null) {
            facts.add(new RedFlagFact("symptom.flare_state", null, checkIn.getFlareState().name(), null));
        }
        for (var answer : checkIn.getAnswers()) {
            if (answer.getQuestion() == null) {
                continue;
            }
            var factKey = symptomFactKey(answer.getQuestion().getStableKey());
            var definition = registry.find(RedFlagSourceType.SYMPTOM_CHECK_IN, factKey).orElse(null);
            if (definition == null || factKey.equals("symptom.flare_state")) {
                continue;
            }
            if (definition.valueType() == RedFlagFactRegistry.ValueType.DECIMAL
                    && answer.getAnswerNumeric() != null) {
                facts.add(new RedFlagFact(factKey, answer.getAnswerNumeric(), null,
                        definition.canonicalUnit()));
            } else if (definition.valueType() == RedFlagFactRegistry.ValueType.TEXT
                    && answer.getOption() != null && answer.getOption().getStableKey() != null) {
                facts.add(new RedFlagFact(factKey, null, answer.getOption().getStableKey(), null));
            }
        }
        facts.sort(factOrder(RedFlagSourceType.SYMPTOM_CHECK_IN));
        return new RedFlagFactSet(RedFlagSourceType.SYMPTOM_CHECK_IN,
                checkIn.getId(), checkIn.getCheckInDate(), facts);
    }

    private RedFlagFactSet labFacts(LabResultSet resultSet) {
        var facts = resultSet.getResults().stream()
                .filter(result -> result.getTestDefinition() != null)
                .map(result -> {
                    var factKey = "lab." + result.getTestDefinition().getCode();
                    var definition = registry.find(RedFlagSourceType.LAB_RESULT_SET, factKey).orElse(null);
                    if (definition == null) {
                        return null;
                    }
                    return new RedFlagFact(factKey, result.getCanonicalValue(), null,
                            result.getCanonicalUnit());
                })
                .filter(java.util.Objects::nonNull)
                .sorted(factOrder(RedFlagSourceType.LAB_RESULT_SET))
                .toList();
        return new RedFlagFactSet(RedFlagSourceType.LAB_RESULT_SET,
                resultSet.getId(), resultSet.getCollectionDate(), facts);
    }

    private RedFlagFactSet profileFacts(
            com.metabion.domain.PatientProfile profile, java.time.LocalDate observedOn) {
        var facts = profile.getSex() == Sex.MALE || profile.getSex() == Sex.FEMALE
                ? List.of(new RedFlagFact("patient.sex", null, profile.getSex().name(), null))
                : List.<RedFlagFact>of();
        return new RedFlagFactSet(RedFlagSourceType.PATIENT_PROFILE,
                profile.getId(), observedOn, facts);
    }

    private String symptomFactKey(String questionKey) {
        return switch (questionKey) {
            case "stool-frequency" -> "symptom.stool_frequency";
            case "abdominal-pain" -> "symptom.abdominal_pain";
            case "blood-in-stool" -> "symptom.blood_in_stool";
            case "general-wellbeing" -> "symptom.general_wellbeing";
            default -> "";
        };
    }

    private java.util.Comparator<RedFlagFact> factOrder(RedFlagSourceType sourceType) {
        Map<String, Integer> positions = new LinkedHashMap<>();
        var definitions = registry.forSource(sourceType);
        for (int index = 0; index < definitions.size(); index++) {
            positions.put(definitions.get(index).factKey(), index);
        }
        return java.util.Comparator.comparingInt(fact -> positions.get(fact.key()));
    }
}
