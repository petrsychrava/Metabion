package com.metabion.repository;

import com.metabion.domain.EducationLessonCompletion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface EducationLessonCompletionRepository extends JpaRepository<EducationLessonCompletion, Long> {
    @Query("""
            select c.lessonVersion.id
            from EducationLessonCompletion c
            where c.patientProfile.id = :patientProfileId
              and c.lessonVersion.id in :lessonVersionIds
            """)
    List<Long> findCompletedLessonVersionIds(
            @Param("patientProfileId") Long patientProfileId,
            @Param("lessonVersionIds") Collection<Long> lessonVersionIds);

    @Modifying
    @Query(value = """
            INSERT INTO education_lesson_completions(patient_profile_id, module_version_id, lesson_version_id)
            VALUES (:patientProfileId, :moduleVersionId, :lessonVersionId)
            ON CONFLICT ON CONSTRAINT ux_education_lesson_completions_patient_lesson DO NOTHING
            """, nativeQuery = true)
    int insertCompletionIfAbsent(
            @Param("patientProfileId") Long patientProfileId,
            @Param("moduleVersionId") Long moduleVersionId,
            @Param("lessonVersionId") Long lessonVersionId);

    void deleteByPatientProfileIdAndLessonVersionId(Long patientProfileId, Long lessonVersionId);
}
