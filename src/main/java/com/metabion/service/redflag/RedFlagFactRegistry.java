package com.metabion.service.redflag;

import com.metabion.domain.RedFlagSourceType;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public class RedFlagFactRegistry {

    public enum ValueType {
        DECIMAL,
        TEXT
    }

    public record Definition(
            RedFlagSourceType sourceType, String factKey, ValueType valueType,
            String canonicalUnit, boolean lookbackAllowed) { }

    private final Map<FactIdentity, Definition> definitions;
    private final List<Definition> orderedDefinitions;

    public RedFlagFactRegistry() {
        var registered = new LinkedHashMap<FactIdentity, Definition>();
        register(registered, RedFlagSourceType.SYMPTOM_CHECK_IN,
                "symptom.flare_state", ValueType.TEXT, null, true);
        register(registered, RedFlagSourceType.SYMPTOM_CHECK_IN,
                "symptom.abdominal_pain", ValueType.TEXT, null, true);
        register(registered, RedFlagSourceType.SYMPTOM_CHECK_IN,
                "symptom.blood_in_stool", ValueType.TEXT, null, true);
        register(registered, RedFlagSourceType.SYMPTOM_CHECK_IN,
                "symptom.general_wellbeing", ValueType.TEXT, null, true);
        register(registered, RedFlagSourceType.SYMPTOM_CHECK_IN,
                "symptom.stool_frequency", ValueType.DECIMAL, "count/day", true);
        register(registered, RedFlagSourceType.LAB_RESULT_SET, "lab.CRP", ValueType.DECIMAL, "mg/L", false);
        register(registered, RedFlagSourceType.LAB_RESULT_SET, "lab.HEMOGLOBIN", ValueType.DECIMAL, "g/L", false);
        register(registered, RedFlagSourceType.LAB_RESULT_SET, "lab.ALBUMIN", ValueType.DECIMAL, "g/L", false);
        register(registered, RedFlagSourceType.LAB_RESULT_SET, "lab.SODIUM", ValueType.DECIMAL, "mmol/L", false);
        register(registered, RedFlagSourceType.LAB_RESULT_SET, "lab.POTASSIUM", ValueType.DECIMAL, "mmol/L", false);
        register(registered, RedFlagSourceType.LAB_RESULT_SET, "lab.MAGNESIUM", ValueType.DECIMAL, "mmol/L", false);
        register(registered, RedFlagSourceType.LAB_RESULT_SET, "lab.UREA", ValueType.DECIMAL, "mmol/L", false);
        register(registered, RedFlagSourceType.LAB_RESULT_SET, "lab.CREATININE", ValueType.DECIMAL, "umol/L", false);
        register(registered, RedFlagSourceType.LAB_RESULT_SET, "lab.ALT", ValueType.DECIMAL, "U/L", false);
        register(registered, RedFlagSourceType.LAB_RESULT_SET, "lab.AST", ValueType.DECIMAL, "U/L", false);
        register(registered, RedFlagSourceType.LAB_RESULT_SET,
                "lab.FECAL_CALPROTECTIN", ValueType.DECIMAL, "ug/g", false);
        register(registered, RedFlagSourceType.PATIENT_PROFILE,
                "patient.sex", ValueType.TEXT, null, false);
        orderedDefinitions = List.copyOf(new ArrayList<>(registered.values()));
        definitions = Map.copyOf(registered);
    }

    public Optional<Definition> find(RedFlagSourceType sourceType, String factKey) {
        return Optional.ofNullable(definitions.get(new FactIdentity(sourceType, factKey)));
    }

    public List<Definition> forSource(RedFlagSourceType sourceType) {
        return orderedDefinitions.stream()
                .filter(definition -> definition.sourceType() == sourceType)
                .toList();
    }

    private static void register(Map<FactIdentity, Definition> target, RedFlagSourceType sourceType,
            String factKey, ValueType valueType, String canonicalUnit, boolean lookbackAllowed) {
        var definition = new Definition(sourceType, factKey, valueType, canonicalUnit, lookbackAllowed);
        target.put(new FactIdentity(sourceType, factKey), definition);
    }

    private record FactIdentity(RedFlagSourceType sourceType, String factKey) { }
}
