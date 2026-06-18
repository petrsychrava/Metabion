package com.metabion.repository;

import com.metabion.domain.EducationModule;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface EducationModuleRepository extends JpaRepository<EducationModule, Long> {
    @EntityGraph(attributePaths = {
            "currentPublishedVersion",
            "currentPublishedVersion.author",
            "currentPublishedVersion.reviewedBy",
            "currentPublishedVersion.publishedBy"
    })
    Optional<EducationModule> findBySlug(String slug);

    boolean existsBySlug(String slug);

    @EntityGraph(attributePaths = {
            "currentPublishedVersion",
            "currentPublishedVersion.author",
            "currentPublishedVersion.reviewedBy",
            "currentPublishedVersion.publishedBy"
    })
    List<EducationModule> findByCurrentPublishedVersionIsNotNullOrderBySortOrderAscIdAsc();
}
