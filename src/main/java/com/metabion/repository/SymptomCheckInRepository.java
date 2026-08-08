package com.metabion.repository;

import com.metabion.domain.SymptomCheckIn;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface SymptomCheckInRepository extends JpaRepository<SymptomCheckIn, Long> {

    Optional<SymptomCheckIn> findByPatientProfileIdAndCheckInDate(Long patientProfileId, LocalDate checkInDate);

    Optional<SymptomCheckIn> findFirstByPatientProfileIdOrderByCheckInDateDesc(Long patientProfileId);

    List<SymptomCheckIn> findByPatientProfileIdAndCheckInDateBetweenOrderByCheckInDateDesc(
            Long patientProfileId, LocalDate from, LocalDate to);

    @EntityGraph(attributePaths = {"answers", "answers.question", "answers.option"})
    @Query("""
           select distinct checkIn from SymptomCheckIn checkIn
           where checkIn.patientProfile.id=:patientId
             and checkIn.checkInDate between :from and :to
           order by checkIn.checkInDate desc, checkIn.id desc
           """)
    List<SymptomCheckIn> findForRedFlagContext(Long patientId, LocalDate from, LocalDate to);
}
