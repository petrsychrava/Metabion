package com.metabion.controller.web;

import com.metabion.domain.MeasurementUnit;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class TrendGlucoseConverterTest {

    private final TrendGlucoseConverter converter = new TrendGlucoseConverter();

    @Test
    void convertsGlucoseInBothDirectionsAndPreservesSameUnitValues() {
        assertThat(converter.convert(new BigDecimal("5.80"), MeasurementUnit.MMOL_L, MeasurementUnit.MG_DL))
                .isEqualByComparingTo("104.40");
        assertThat(converter.convert(new BigDecimal("104.40"), MeasurementUnit.MG_DL, MeasurementUnit.MMOL_L))
                .isEqualByComparingTo("5.80");
        assertThat(converter.convert(new BigDecimal("5.80"), MeasurementUnit.MMOL_L, MeasurementUnit.MMOL_L))
                .isEqualByComparingTo("5.80");
    }

    @Test
    void rejectsMissingAndUnsupportedConversionInputsWithoutGuessing() {
        assertThat(converter.convert(null, MeasurementUnit.MMOL_L, MeasurementUnit.MG_DL)).isNull();
        assertThat(converter.convert(BigDecimal.ONE, null, MeasurementUnit.MMOL_L)).isNull();
        assertThat(converter.convert(BigDecimal.ONE, MeasurementUnit.MMOL_L, null)).isNull();
    }
}
