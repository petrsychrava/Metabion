package com.metabion.repository;

import com.metabion.domain.EducationLessonCompletion;
import org.springframework.data.jpa.repository.JpaRepository;
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

    void deleteByPatientProfileIdAndLessonVersionId(Long patientProfileId, Long lessonVersionId);
}
