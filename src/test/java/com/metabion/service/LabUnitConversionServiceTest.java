package com.metabion.service;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LabUnitConversionServiceTest {

    private final LabUnitConversionService conversions = new LabUnitConversionService();

    @Test
    void convertsCrpToCanonicalUnit() {
        assertThat(conversions.toCanonical(LabCatalogServiceTest.crpDefinition(), "mg/dL", new BigDecimal("1.20")))
                .isEqualByComparingTo("12.00");
    }

    @Test
    void rejectsUnsupportedUnit() {
        assertThatThrownBy(() -> conversions.toCanonical(LabCatalogServiceTest.crpDefinition(), "mmol/L", BigDecimal.ONE))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void roundsCanonicalValueToDefinitionDisplayScale() {
        assertThat(conversions.toCanonical(LabCatalogServiceTest.crpDefinition(), "mg/dL", new BigDecimal("1.2345")))
                .isEqualByComparingTo("12.35");
    }

    @Test
    void rejectsNegativeValue() {
        assertThatThrownBy(() -> conversions.toCanonical(LabCatalogServiceTest.crpDefinition(), "mg/L", new BigDecimal("-0.01")))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
    }
}
