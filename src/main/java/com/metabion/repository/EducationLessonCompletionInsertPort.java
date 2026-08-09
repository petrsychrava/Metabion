package com.metabion.repository;

public interface EducationLessonCompletionInsertPort {
    int insertCompletionIfAbsent(
            Long patientProfileId,
            Long moduleVersionId,
            Long lessonVersionId);
}
