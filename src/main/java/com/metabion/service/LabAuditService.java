package com.metabion.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.metabion.domain.*;
import com.metabion.repository.LabResultAuditEventRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;

@Service
public class LabAuditService {
    private final LabResultAuditEventRepository events;
    private final ObjectMapper objectMapper;

    public LabAuditService(LabResultAuditEventRepository events, ObjectProvider<ObjectMapper> objectMapperProvider) {
        this.events = events;
        this.objectMapper = objectMapperProvider.getIfAvailable(ObjectMapper::new).copy().registerModule(javaTimeSnapshotModule());
    }

    public LabAuditSnapshot snapshot(LabResultSet set) {
        return new LabAuditSnapshot(set.getId(), set.getPatientProfile().getId(), set.getCollectionDate(),
                set.getNotes(), set.getSource(), set.getConfirmationStatus(), set.getCreatedByUser().getId(),
                set.getCreatedAt(), set.getUpdatedAt(), set.getVersion(), set.getRemovedAt(),
                set.getRemovedByUser() == null ? null : set.getRemovedByUser().getId(), set.getRemovalReason(),
                set.getResults().stream().map(result -> new LabAuditSnapshot.Result(
                        result.getTestDefinition().getCode(), result.getReportedValue(), result.getReportedUnit(),
                        result.getCanonicalValue(), result.getCanonicalUnit(), result.getReferenceLower(),
                        result.getReferenceUpper())).toList());
    }

    public void recordCreate(LabResultSet set, User actor, Instant now) {
        events.save(event(set, actor, LabAuditAction.CREATE, null, json(snapshot(set)), now));
    }

    public void recordUpdate(LabResultSet set, LabAuditSnapshot before, User actor, Instant now) {
        events.save(event(set, actor, LabAuditAction.UPDATE, json(before), json(snapshot(set)), now));
    }

    public void recordRemoval(LabResultSet set, LabAuditSnapshot before, User actor, Instant now) {
        events.save(event(set, actor, LabAuditAction.REMOVE, json(before), json(snapshot(set)), now));
    }

    private LabResultAuditEvent event(LabResultSet set, User actor, LabAuditAction action,
                                      String before, String after, Instant now) {
        return new LabResultAuditEvent(set, set.getPatientProfile(), action, actor, now, before, after);
    }

    private String json(LabAuditSnapshot snapshot) {
        try { return objectMapper.writeValueAsString(snapshot); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("Unable to serialize laboratory audit snapshot", exception); }
    }

    private static SimpleModule javaTimeSnapshotModule() {
        var module = new SimpleModule();
        module.addSerializer(Instant.class, new com.fasterxml.jackson.databind.JsonSerializer<Instant>() {
            @Override public void serialize(Instant value, com.fasterxml.jackson.core.JsonGenerator generator,
                                            com.fasterxml.jackson.databind.SerializerProvider provider) throws java.io.IOException {
                generator.writeString(value.toString());
            }
        });
        module.addSerializer(LocalDate.class, new com.fasterxml.jackson.databind.JsonSerializer<LocalDate>() {
            @Override public void serialize(LocalDate value, com.fasterxml.jackson.core.JsonGenerator generator,
                                            com.fasterxml.jackson.databind.SerializerProvider provider) throws java.io.IOException {
                generator.writeString(value.toString());
            }
        });
        return module;
    }
}
