package com.metabion.repository;

import com.metabion.domain.SymptomQuestionnaireVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface SymptomQuestionnaireVersionRepository extends JpaRepository<SymptomQuestionnaireVersion, Long> {

    @Query("""
            select distinct version
            from SymptomQuestionnaireVersion version
            join fetch version.questionnaire questionnaire
            left join fetch version.questions question
            where questionnaire.stableKey = :stableKey
              and questionnaire.active = true
              and version.status = com.metabion.domain.QuestionnaireVersionStatus.ACTIVE
            order by question.sortOrder asc, question.id asc
            """)
    Optional<SymptomQuestionnaireVersion> findActiveByQuestionnaireStableKey(@Param("stableKey") String stableKey);

    @Query("""
            select version
            from SymptomQuestionnaireVersion version
            join version.questionnaire questionnaire
            where questionnaire.stableKey = :stableKey
              and questionnaire.active = true
              and version.status = com.metabion.domain.QuestionnaireVersionStatus.ACTIVE
            """)
    List<SymptomQuestionnaireVersion> findActiveVersionsByQuestionnaireStableKey(@Param("stableKey") String stableKey);
}
