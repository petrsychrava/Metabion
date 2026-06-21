package com.metabion.repository;

import com.metabion.domain.DailyDietLogPhotoReference;
import com.metabion.domain.DietLogPhotoStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

public interface DailyDietLogPhotoReferenceRepository extends JpaRepository<DailyDietLogPhotoReference, Long> {

    List<DailyDietLogPhotoReference> findByIdIn(Collection<Long> ids);

    List<DailyDietLogPhotoReference> findByStatusAndCreatedAtBefore(DietLogPhotoStatus status, Instant createdBefore);
}
