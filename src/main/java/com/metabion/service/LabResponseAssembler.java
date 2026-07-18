package com.metabion.service;

import com.metabion.domain.LabResult;
import com.metabion.domain.LabResultSet;
import com.metabion.domain.User;
import com.metabion.dto.LabResultResponse;
import com.metabion.dto.LabResultSetResponse;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;

@Service
public class LabResponseAssembler {
    private final MessageSource messages;
    public LabResponseAssembler(MessageSource messages) { this.messages = messages; }

    public LabResultSetResponse resultSet(LabResultSet set, User viewer) {
        return new LabResultSetResponse(set.getId(), set.getVersion(), set.getPatientProfile().getId(),
                set.getCollectionDate(), set.getNotes(), set.getSource(), set.getConfirmationStatus(),
                set.getCreatedByUser().getId().equals(viewer.getId()), set.getCreatedAt(), set.getUpdatedAt(),
                set.getResults().stream().map(this::result).toList());
    }
    private LabResultResponse result(LabResult result) {
        var definition = result.getTestDefinition();
        return new LabResultResponse(result.getId(), definition.getCode(), messages.getMessage(definition.getLabelKey(), null,
                definition.getCode(), LocaleContextHolder.getLocale()), result.getReportedValue(), result.getReportedUnit(),
                result.getCanonicalValue(), result.getCanonicalUnit(), result.getReferenceLower(), result.getReferenceUpper());
    }
}
