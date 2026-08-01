package com.metabion.repository;

import com.metabion.domain.RedFlagRuleStatus;
import com.metabion.domain.RedFlagRuleVersion;
import com.metabion.domain.RedFlagSourceType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface RedFlagRuleVersionRepository extends Repository<RedFlagRuleVersion, Long> {

    @EntityGraph(attributePaths = {"rule", "conditionGroups"})
    @Query("""
           select distinct version from RedFlagRuleVersion version
           where version.status=:status and version.triggerSource=:source
           order by version.rule.stableKey, version.versionNumber
           """)
    List<RedFlagRuleVersion> findByStatusAndTriggerSource(
            @Param("status") RedFlagRuleStatus status, @Param("source") RedFlagSourceType source);
}
