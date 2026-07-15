package com.metabion.controller.web;

import com.metabion.domain.MeasurementUnit;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Component
final class TrendGlucoseConverter {

    private static final BigDecimal MG_DL_PER_MMOL_L = new BigDecimal("18");

    BigDecimal convert(BigDecimal value, MeasurementUnit source, MeasurementUnit target) {
        if (value == null || source == null || target == null) {
            return null;
        }
        if (source == target) {
            return value;
        }
        if (source == MeasurementUnit.MMOL_L && target == MeasurementUnit.MG_DL) {
            return value.multiply(MG_DL_PER_MMOL_L).setScale(2, RoundingMode.HALF_UP);
        }
        if (source == MeasurementUnit.MG_DL && target == MeasurementUnit.MMOL_L) {
            return value.divide(MG_DL_PER_MMOL_L, 2, RoundingMode.HALF_UP);
        }
        return null;
    }
}
