package com.metabion.service;

import com.metabion.domain.LabTestCategory;
import com.metabion.domain.LabTestDefinition;
import com.metabion.domain.LabUnitConversionType;
import com.metabion.repository.LabTestDefinitionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.context.i18n.LocaleContextHolder;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LabCatalogServiceTest {

    private final LabTestDefinitionRepository definitions = mock(LabTestDefinitionRepository.class);
    private final StaticMessageSource messages = new StaticMessageSource();
    private final LabCatalogService catalog = new LabCatalogService(definitions, messages);

    @AfterEach
    void resetLocale() {
        LocaleContextHolder.resetLocaleContext();
    }

    @Test
    void catalogReturnsLocalizedLabelAndOrderedUnits() {
        LocaleContextHolder.setLocale(Locale.ENGLISH);
        messages.addMessage("lab.test.crp", Locale.ENGLISH, "C-reactive protein");
        var definition = crpDefinition();
        when(definitions.findByActiveTrueOrderBySortOrderAscCodeAsc()).thenReturn(List.of(definition));

        assertThat(catalog.listActive()).singleElement().satisfies(response -> {
            assertThat(response.label()).isEqualTo("C-reactive protein");
            assertThat(response.allowedUnits()).containsExactly("mg/L", "mg/dL");
        });
    }

    @Test
    void requireActiveNormalizesCodeBeforeLookingUpDefinition() {
        var definition = crpDefinition();
        when(definitions.findByCodeAndActiveTrue("CRP")).thenReturn(java.util.Optional.of(definition));

        assertThat(catalog.requireActive(" crp ")).isSameAs(definition);
    }

    @Test
    void requireActiveRejectsMissingCode() {
        assertThatThrownBy(() -> catalog.requireActive(" "))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("testCode is required");
    }

    static LabTestDefinition crpDefinition() {
        var definition = mock(LabTestDefinition.class);
        when(definition.getCode()).thenReturn("CRP");
        when(definition.getLabelKey()).thenReturn("lab.test.crp");
        when(definition.getCategory()).thenReturn(LabTestCategory.INFLAMMATION);
        when(definition.getCanonicalUnit()).thenReturn("mg/L");
        when(definition.getDisplayScale()).thenReturn((short) 2);
        var milligramsPerDeciliter = unit(definition, "mg/dL", LabUnitConversionType.MULTIPLY,
                new BigDecimal("10"), 20);
        var milligramsPerLiter = unit(definition, "mg/L", LabUnitConversionType.IDENTITY, BigDecimal.ONE, 10);
        when(definition.getUnits()).thenReturn(List.of(milligramsPerDeciliter, milligramsPerLiter));
        return definition;
    }

    private static com.metabion.domain.LabTestUnitDefinition unit(LabTestDefinition definition, String unitCode,
                                                                    LabUnitConversionType type, BigDecimal multiplier, int sortOrder) {
        var unit = mock(com.metabion.domain.LabTestUnitDefinition.class);
        when(unit.getTestDefinition()).thenReturn(definition);
        when(unit.getUnitCode()).thenReturn(unitCode);
        when(unit.getConversionType()).thenReturn(type);
        when(unit.getMultiplier()).thenReturn(multiplier);
        when(unit.getSortOrder()).thenReturn(sortOrder);
        return unit;
    }
}
