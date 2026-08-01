package com.metabion.repository;

import com.metabion.domain.RedFlagTriggerEvent;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface RedFlagTriggerEventRepository extends Repository<RedFlagTriggerEvent, Long> {

    RedFlagTriggerEvent saveAndFlush(RedFlagTriggerEvent event);

    @Query("""
           select event.ruleVersion.rule.stableKey
           from RedFlagTriggerEvent event
           where event.evaluationRun.id = :runId
           order by event.ruleVersion.rule.stableKey
           """)
    List<String> findRuleKeysByEvaluationRunId(@Param("runId") Long runId);
}
