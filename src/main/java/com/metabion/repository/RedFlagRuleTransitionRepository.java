package com.metabion.repository;

import com.metabion.domain.RedFlagRuleTransition;
import org.springframework.data.repository.Repository;

import java.util.List;

public interface RedFlagRuleTransitionRepository extends Repository<RedFlagRuleTransition, Long> {

    long count();

    List<RedFlagRuleTransition> findByRuleVersionIdOrderByTransitionedAtAscIdAsc(Long ruleVersionId);
}
