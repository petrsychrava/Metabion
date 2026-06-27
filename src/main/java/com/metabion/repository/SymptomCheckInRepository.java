package com.metabion.repository;

import com.metabion.domain.SymptomCheckIn;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface SymptomCheckInRepository extends JpaRepository<SymptomCheckIn, Long> {

    Optional<SymptomCheckIn> findByPatientProfileIdAndCheckInDate(Long patientProfileId, LocalDate checkInDate);

    List<SymptomCheckIn> findByPatientProfileIdAndCheckInDateBetweenOrderByCheckInDateDesc(
            Long patientProfileId, LocalDate from, LocalDate to);
}
