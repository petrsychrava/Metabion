package com.metabion.repository;

import com.metabion.domain.RedFlagSeverity;
import com.metabion.domain.RedFlagTriggerEvent;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface RedFlagTriggerEventRepository extends Repository<RedFlagTriggerEvent, Long> {

    RedFlagTriggerEvent saveAndFlush(RedFlagTriggerEvent event);

    @EntityGraph(attributePaths = {
            "evaluationRun", "evaluationRun.supersededByRun", "ruleVersion", "ruleVersion.rule"
    })
    @Query("""
           select event from RedFlagTriggerEvent event
           where event.evaluationRun.patientProfile.id = :patientId
             and event.evaluationRun.current = true
           order by event.triggeredAt desc, event.id desc
           """)
    List<RedFlagTriggerEvent> findCurrentForPatient(@Param("patientId") Long patientId);

    @EntityGraph(attributePaths = {
            "evaluationRun", "evaluationRun.supersededByRun", "ruleVersion", "ruleVersion.rule"
    })
    default List<RedFlagTriggerEvent> findHistoryPage(
            @Param("patientId") Long patientId,
            @Param("severity") RedFlagSeverity severity,
            @Param("fromInclusive") Instant fromInclusive,
            @Param("toExclusive") Instant toExclusive,
            @Param("cursorAt") Instant cursorAt,
            @Param("cursorId") Long cursorId,
            Pageable pageable) {
        return findHistoryPageInternal(
                patientId,
                severity == null,
                severity,
                fromInclusive == null,
                fromInclusive,
                toExclusive == null,
                toExclusive,
                cursorAt == null,
                cursorAt,
                cursorId,
                pageable);
    }

    @EntityGraph(attributePaths = {
            "evaluationRun", "evaluationRun.supersededByRun", "ruleVersion", "ruleVersion.rule"
    })
    @Query("""
           select event from RedFlagTriggerEvent event
           where event.evaluationRun.patientProfile.id = :patientId
             and (:ignoreSeverity = true or event.severity = :severity)
             and (:ignoreFrom = true or event.triggeredAt >= :fromInclusive)
             and (:ignoreTo = true or event.triggeredAt < :toExclusive)
             and (:ignoreCursor = true
                  or event.triggeredAt < :cursorAt
                  or (event.triggeredAt = :cursorAt and event.id < :cursorId))
           order by event.triggeredAt desc, event.id desc
           """)
    List<RedFlagTriggerEvent> findHistoryPageInternal(
            @Param("patientId") Long patientId,
            @Param("ignoreSeverity") boolean ignoreSeverity,
            @Param("severity") RedFlagSeverity severity,
            @Param("ignoreFrom") boolean ignoreFrom,
            @Param("fromInclusive") Instant fromInclusive,
            @Param("ignoreTo") boolean ignoreTo,
            @Param("toExclusive") Instant toExclusive,
            @Param("ignoreCursor") boolean ignoreCursor,
            @Param("cursorAt") Instant cursorAt,
            @Param("cursorId") Long cursorId,
            Pageable pageable);

    @Query("""
           select event.ruleVersion.rule.stableKey
           from RedFlagTriggerEvent event
           where event.evaluationRun.id = :runId
           order by event.ruleVersion.rule.stableKey
           """)
    List<String> findRuleKeysByEvaluationRunId(@Param("runId") Long runId);
}
