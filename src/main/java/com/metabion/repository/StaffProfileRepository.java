package com.metabion.repository;

import com.metabion.domain.StaffProfile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface StaffProfileRepository extends JpaRepository<StaffProfile, Long> {

    Optional<StaffProfile> findByUserId(Long userId);
}
