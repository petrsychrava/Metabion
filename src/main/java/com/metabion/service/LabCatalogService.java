package com.metabion.service;

import com.metabion.domain.LabTestDefinition;
import com.metabion.dto.LabTestDefinitionResponse;
import com.metabion.repository.LabTestDefinitionRepository;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;

@Service
public class LabCatalogService {

    private final LabTestDefinitionRepository definitions;
    private final MessageSource messages;

    public LabCatalogService(LabTestDefinitionRepository definitions, MessageSource messages) {
        this.definitions = definitions;
        this.messages = messages;
    }

    public List<LabTestDefinitionResponse> listActive() {
        return definitions.findByActiveTrueOrderBySortOrderAscCodeAsc().stream()
                .map(this::toResponse)
                .toList();
    }

    public LabTestDefinition requireActive(String code) {
        if (code == null || code.isBlank()) {
            throw badRequest("testCode is required");
        }
        return definitions.findByCodeAndActiveTrue(code.trim().toUpperCase(Locale.ROOT))
                .orElseThrow(() -> badRequest("laboratory test is unsupported"));
    }

    private LabTestDefinitionResponse toResponse(LabTestDefinition definition) {
        var units = definition.getUnits().stream()
                .sorted(Comparator.comparingInt(unit -> unit.getSortOrder()))
                .map(unit -> unit.getUnitCode())
                .toList();
        return new LabTestDefinitionResponse(
                definition.getCode(),
                messages.getMessage(definition.getLabelKey(), null, definition.getCode(), LocaleContextHolder.getLocale()),
                definition.getCategory(),
                definition.getCanonicalUnit(),
                definition.getDisplayScale(),
                units);
    }

    private ResponseStatusException badRequest(String reason) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
    }
}
