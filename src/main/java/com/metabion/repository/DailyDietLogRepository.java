package com.metabion.repository;

import com.metabion.domain.DailyDietLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface DailyDietLogRepository extends JpaRepository<DailyDietLog, Long> {

    Optional<DailyDietLog> findByPatientProfileIdAndLogDate(Long patientProfileId, LocalDate logDate);

    List<DailyDietLog> findByPatientProfileIdAndLogDateBetweenOrderByLogDateDesc(
            Long patientProfileId,
            LocalDate from,
            LocalDate to);
}
