package com.metabion.repository;

import com.metabion.domain.EducationLessonCompletion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface EducationLessonCompletionRepository extends JpaRepository<EducationLessonCompletion, Long> {
    Optional<EducationLessonCompletion> findByPatientProfileIdAndLessonVersionId(Long patientProfileId, Long lessonVersionId);

    List<EducationLessonCompletion> findByPatientProfileIdAndLessonVersionIdIn(
            Long patientProfileId,
            Collection<Long> lessonVersionIds);

    void deleteByPatientProfileIdAndLessonVersionId(Long patientProfileId, Long lessonVersionId);
}
