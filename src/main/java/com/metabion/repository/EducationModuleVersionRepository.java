package com.metabion.repository;

import com.metabion.domain.EducationContentStatus;
import com.metabion.domain.EducationModuleVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface EducationModuleVersionRepository extends JpaRepository<EducationModuleVersion, Long> {
    Optional<EducationModuleVersion> findByModuleSlugAndVersion(String moduleSlug, int version);

    List<EducationModuleVersion> findByStatusOrderByCreatedAtDesc(EducationContentStatus status);

    List<EducationModuleVersion> findAllByOrderByCreatedAtDesc();

    @Query("select coalesce(max(v.version), 0) from EducationModuleVersion v where v.module.id = :moduleId")
    int maxVersion(@Param("moduleId") Long moduleId);
}
