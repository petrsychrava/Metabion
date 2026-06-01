package com.metabion.repository;

import com.metabion.domain.Cohort;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CohortRepository extends JpaRepository<Cohort, Long> {
}
