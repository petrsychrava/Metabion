package com.metabion.repository;

import com.metabion.domain.RedFlagEvaluationRun;
import com.metabion.domain.RedFlagSourceType;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface RedFlagEvaluationRunRepository extends Repository<RedFlagEvaluationRun, Long> {

    RedFlagEvaluationRun saveAndFlush(RedFlagEvaluationRun run);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
           select run from RedFlagEvaluationRun run
           where run.sourceType=:sourceType and run.sourceId=:sourceId and run.current=true
           """)
    Optional<RedFlagEvaluationRun> findCurrentForUpdate(
            @Param("sourceType") RedFlagSourceType sourceType, @Param("sourceId") Long sourceId);

    @EntityGraph(attributePaths = {
            "events", "events.ruleVersion", "events.ruleVersion.rule", "events.matchedGroup"
    })
    List<RedFlagEvaluationRun> findByPatientProfileIdAndCurrentTrueOrderByEvaluatedAtDescIdDesc(Long patientId);

    @EntityGraph(attributePaths = {
            "events", "events.ruleVersion", "events.ruleVersion.rule", "events.matchedGroup"
    })
    List<RedFlagEvaluationRun> findByPatientProfileIdOrderByEvaluatedAtDescIdDesc(Long patientId);
}
