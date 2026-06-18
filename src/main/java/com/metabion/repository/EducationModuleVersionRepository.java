package com.metabion.repository;

import com.metabion.domain.EducationContentStatus;
import com.metabion.domain.EducationLessonVersion;
import com.metabion.domain.EducationModuleVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface EducationModuleVersionRepository extends JpaRepository<EducationModuleVersion, Long> {
    Optional<EducationModuleVersion> findByModuleSlugAndVersion(String moduleSlug, int version);

    List<EducationModuleVersion> findByStatusOrderByCreatedAtDesc(EducationContentStatus status);

    List<EducationModuleVersion> findAllByOrderByCreatedAtDesc();

    @Query("select coalesce(max(v.version), 0) from EducationModuleVersion v where v.module.id = :moduleId")
    int maxVersion(@Param("moduleId") Long moduleId);

    @Query("""
            select distinct v
            from EducationModuleVersion v
            left join fetch v.localizations
            where v in :versions
            """)
    List<EducationModuleVersion> fetchLocalizations(@Param("versions") Collection<EducationModuleVersion> versions);

    @Query("""
            select distinct v
            from EducationModuleVersion v
            left join fetch v.lessons lessonVersion
            left join fetch lessonVersion.lesson
            where v in :versions
            """)
    List<EducationModuleVersion> fetchLessons(@Param("versions") Collection<EducationModuleVersion> versions);

    @Query("""
            select distinct lessonVersion
            from EducationLessonVersion lessonVersion
            left join fetch lessonVersion.localizations
            where lessonVersion.moduleVersion in :versions
            """)
    List<EducationLessonVersion> fetchLessonLocalizations(@Param("versions") Collection<EducationModuleVersion> versions);
}
