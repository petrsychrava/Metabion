package com.metabion.repository;

import com.metabion.domain.LabResultSet;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface LabResultSetRepository extends JpaRepository<LabResultSet, Long> {
    @EntityGraph(attributePaths = {"patientProfile", "createdByUser", "results", "results.testDefinition"})
    @Query("select distinct s from LabResultSet s where s.id=:id and s.removedAt is null")
    Optional<LabResultSet> findActiveById(@Param("id") Long id);

    @EntityGraph(attributePaths = {"createdByUser", "results", "results.testDefinition"})
    @Query("""
            select distinct s from LabResultSet s
            where s.patientProfile.id=:patientId and s.removedAt is null
              and s.collectionDate between :from and :to
            order by s.collectionDate desc, s.id desc
            """)
    List<LabResultSet> findActiveByPatientAndCollectionDateBetween(
            @Param("patientId") Long patientId, @Param("from") LocalDate from, @Param("to") LocalDate to);
}
