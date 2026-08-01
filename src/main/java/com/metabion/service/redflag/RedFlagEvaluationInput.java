package com.metabion.service.redflag;

import java.util.List;

public record RedFlagEvaluationInput(
        RedFlagFactSet trigger, RedFlagFactSet patientProfile,
        List<RedFlagFactSet> lookback) {

    public RedFlagEvaluationInput {
        lookback = List.copyOf(lookback);
    }
}
