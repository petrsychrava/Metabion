package com.metabion.repository;

import com.metabion.domain.LabResult;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface LabResultRepository extends JpaRepository<LabResult, Long> {
    @EntityGraph(attributePaths = {"resultSet", "resultSet.createdByUser", "testDefinition"})
    @Query("""
            select r from LabResult r
            where r.resultSet.patientProfile.id=:patientId and r.resultSet.removedAt is null
              and r.testDefinition.code=:testCode and r.resultSet.collectionDate between :from and :to
            order by r.resultSet.collectionDate asc, r.id asc
            """)
    List<LabResult> findTrend(@Param("patientId") Long patientId, @Param("testCode") String testCode,
                              @Param("from") LocalDate from, @Param("to") LocalDate to);
}
