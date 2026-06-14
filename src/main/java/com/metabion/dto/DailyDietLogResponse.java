package com.metabion.dto;

import com.metabion.domain.AppetiteLevel;
import com.metabion.domain.DailyDietLog;
import com.metabion.domain.DailyDietLogDeviation;
import com.metabion.domain.DailyDietLogMeal;
import com.metabion.domain.DailyDietLogPhotoReference;
import com.metabion.domain.DailyMeasurementEntry;
import com.metabion.domain.DietAdherenceLevel;
import com.metabion.domain.DietDeviationCategory;
import com.metabion.domain.DietDeviationSeverity;
import com.metabion.domain.FoodCategory;
import com.metabion.domain.MealType;
import com.metabion.domain.PatientProfile;
import com.metabion.domain.User;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record DailyDietLogResponse(
        Long id,
        Long patientProfileId,
        String patientEmail,
        LocalDate logDate,
        DietAdherenceLevel adherenceLevel,
        AppetiteLevel appetiteLevel,
        String notes,
        String metadata,
        Instant createdAt,
        Instant updatedAt,
        List<MealResponse> meals,
        List<DeviationResponse> deviations,
        List<PhotoReferenceResponse> photoReferences,
        List<DailyMeasurementEntryResponse> measurements
) {

    public DailyDietLogResponse(Long id,
                                Long patientProfileId,
                                String patientEmail,
                                LocalDate logDate,
                                DietAdherenceLevel adherenceLevel,
                                AppetiteLevel appetiteLevel,
                                String notes,
                                Instant createdAt,
                                Instant updatedAt,
                                List<MealResponse> meals,
                                List<DeviationResponse> deviations,
                                List<PhotoReferenceResponse> photoReferences,
                                List<DailyMeasurementEntryResponse> measurements) {
        this(id, patientProfileId, patientEmail, logDate, adherenceLevel, appetiteLevel, notes, null,
                createdAt, updatedAt, meals, deviations, photoReferences, measurements);
    }

    public static DailyDietLogResponse from(DailyDietLog log, List<DailyMeasurementEntry> measurements) {
        var patientProfile = log.getPatientProfile();
        return new DailyDietLogResponse(
                log.getId(),
                patientProfileId(patientProfile),
                patientEmail(patientProfile),
                log.getLogDate(),
                log.getAdherenceLevel(),
                log.getAppetiteLevel(),
                log.getNotes(),
                log.getMetadata(),
                log.getCreatedAt(),
                log.getUpdatedAt(),
                log.getMeals().stream().map(MealResponse::from).toList(),
                log.getDeviations().stream().map(DeviationResponse::from).toList(),
                log.getPhotoReferences().stream().map(PhotoReferenceResponse::from).toList(),
                measurements == null
                        ? List.of()
                        : measurements.stream().map(DailyMeasurementEntryResponse::from).toList());
    }

    public record MealResponse(
            Long id,
            MealType mealType,
            FoodCategory foodCategory,
            String foodDescription,
            String notes,
            int sortOrder
    ) {

        private static MealResponse from(DailyDietLogMeal meal) {
            return new MealResponse(
                    meal.getId(),
                    meal.getMealType(),
                    meal.getFoodCategory(),
                    meal.getFoodDescription(),
                    meal.getNotes(),
                    meal.getSortOrder());
        }
    }

    public record DeviationResponse(
            Long id,
            DietDeviationCategory deviationCategory,
            DietDeviationSeverity severity,
            String notes,
            int sortOrder
    ) {

        private static DeviationResponse from(DailyDietLogDeviation deviation) {
            return new DeviationResponse(
                    deviation.getId(),
                    deviation.getDeviationCategory(),
                    deviation.getSeverity(),
                    deviation.getNotes(),
                    deviation.getSortOrder());
        }
    }

    public record PhotoReferenceResponse(
            Long id,
            Long mealId,
            String originalFilename,
            String contentType,
            Long sizeBytes,
            String storageKey,
            String caption,
            int sortOrder
    ) {

        private static PhotoReferenceResponse from(DailyDietLogPhotoReference photoReference) {
            var meal = photoReference.getMeal();
            return new PhotoReferenceResponse(
                    photoReference.getId(),
                    meal == null ? null : meal.getId(),
                    photoReference.getOriginalFilename(),
                    photoReference.getContentType(),
                    photoReference.getSizeBytes(),
                    photoReference.getStorageKey(),
                    photoReference.getCaption(),
                    photoReference.getSortOrder());
        }
    }

    private static Long patientProfileId(PatientProfile patientProfile) {
        return patientProfile == null ? null : patientProfile.getId();
    }

    private static String patientEmail(PatientProfile patientProfile) {
        return patientProfile == null ? null : email(patientProfile.getUser());
    }

    private static String email(User user) {
        return user == null ? null : user.getEmail();
    }
}
