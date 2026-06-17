package com.metabion.repository;

import com.metabion.domain.EducationModule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface EducationModuleRepository extends JpaRepository<EducationModule, Long> {
    Optional<EducationModule> findBySlug(String slug);

    boolean existsBySlug(String slug);

    List<EducationModule> findByCurrentPublishedVersionIsNotNullOrderBySortOrderAscIdAsc();
}
