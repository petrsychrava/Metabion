package com.metabion.repository;

import com.metabion.domain.DailyMeasurementEntry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface DailyMeasurementEntryRepository extends JpaRepository<DailyMeasurementEntry, Long> {

    List<DailyMeasurementEntry> findByPatientProfileIdAndMeasuredAtBetweenOrderByMeasuredAtDesc(
            Long patientProfileId,
            Instant from,
            Instant to);

    List<DailyMeasurementEntry> findByDailyDietLogIdOrderByMeasuredAtDesc(Long dailyDietLogId);
}
