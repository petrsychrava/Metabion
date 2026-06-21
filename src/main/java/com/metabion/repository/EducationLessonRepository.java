package com.metabion.repository;

import com.metabion.domain.EducationLesson;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface EducationLessonRepository extends JpaRepository<EducationLesson, Long> {
    Optional<EducationLesson> findByModuleSlugAndSlug(String moduleSlug, String slug);
}
