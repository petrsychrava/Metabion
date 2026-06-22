package com.metabion.repository;

import com.metabion.domain.DailyMeasurementEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface DailyMeasurementEntryRepository extends JpaRepository<DailyMeasurementEntry, Long> {

    List<DailyMeasurementEntry> findByPatientProfileIdAndMeasuredAtBetweenOrderByMeasuredAtDesc(
            Long patientProfileId,
            Instant from,
            Instant to);

    List<DailyMeasurementEntry> findByDailyDietLogIdOrderByMeasuredAtDesc(Long dailyDietLogId);

    List<DailyMeasurementEntry> findByDailyDietLogIdInOrderByMeasuredAtDesc(List<Long> dailyDietLogIds);

    List<DailyMeasurementEntry> findByPatientProfileIdAndDailyDietLogIsNullAndMeasuredAtGreaterThanEqualAndMeasuredAtLessThanOrderByMeasuredAtDesc(
            Long patientProfileId,
            Instant fromInclusive,
            Instant toExclusive);

    @Query("""
            select e.dailyDietLog.id as dailyDietLogId, count(e) as measurementCount
            from DailyMeasurementEntry e
            where e.dailyDietLog.id in :dailyDietLogIds
            group by e.dailyDietLog.id
            """)
    List<DailyDietLogMeasurementCount> countByDailyDietLogIds(@Param("dailyDietLogIds") List<Long> dailyDietLogIds);

    void deleteByDailyDietLogId(Long dailyDietLogId);

    interface DailyDietLogMeasurementCount {
        Long getDailyDietLogId();

        long getMeasurementCount();
    }
}
