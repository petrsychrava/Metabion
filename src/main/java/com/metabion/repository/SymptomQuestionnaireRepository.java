package com.metabion.repository;

import com.metabion.domain.SymptomQuestionnaire;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SymptomQuestionnaireRepository extends JpaRepository<SymptomQuestionnaire, Long> {

    Optional<SymptomQuestionnaire> findByStableKey(String stableKey);
}
