package com.metabion.repository;

import com.metabion.domain.LabTestDefinition;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface LabTestDefinitionRepository extends JpaRepository<LabTestDefinition, Long> {
    @EntityGraph(attributePaths = "units")
    List<LabTestDefinition> findByActiveTrueOrderBySortOrderAscCodeAsc();

    @EntityGraph(attributePaths = "units")
    Optional<LabTestDefinition> findByCodeAndActiveTrue(String code);
}
