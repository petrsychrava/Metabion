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
import com.metabion.domain.MeasurementContext;
import com.metabion.domain.MeasurementType;
import com.metabion.domain.MeasurementUnit;
import com.metabion.domain.PatientProfile;
import com.metabion.domain.RoleName;
import com.metabion.domain.User;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DietLogFormTest {

    @Test
    void requestNullListHelpersReturnEmptyLists() {
        var request = new DailyDietLogRequest(
                LocalDate.of(2026, 6, 10),
                DietAdherenceLevel.MOSTLY,
                AppetiteLevel.NORMAL,
                "Stable day",
                null,
                null,
                null,
                null);

        assertThat(request.mealsOrEmpty()).isEmpty();
        assertThat(request.deviationsOrEmpty()).isEmpty();
        assertThat(request.photoReferencesOrEmpty()).isEmpty();
        assertThat(request.measurementsOrEmpty()).isEmpty();
    }

    @Test
    void withMeasurementsPreservesOtherFields() {
        var meal = new DailyDietLogRequest.MealRequest(
                MealType.BREAKFAST,
                FoodCategory.PROTEIN,
                "Eggs",
                "No issues");
        var deviation = new DailyDietLogRequest.DeviationRequest(
                DietDeviationCategory.DINING_OUT,
                DietDeviationSeverity.MINOR,
                "Restaurant lunch");
        var photo = new DailyDietLogRequest.PhotoUploadReferenceRequest(50L, "Breakfast");
        var request = new DailyDietLogRequest(
                LocalDate.of(2026, 6, 10),
                DietAdherenceLevel.FULL,
                AppetiteLevel.HIGH,
                "Original notes",
                List.of(meal),
                List.of(deviation),
                List.of(photo),
                List.of());
        var measurement = new DailyMeasurementEntryRequest(
                MeasurementType.GLUCOSE,
                new BigDecimal("5.60"),
                MeasurementUnit.MMOL_L,
                Instant.parse("2026-06-10T07:15:00Z"),
                MeasurementContext.FASTING,
                "Morning");

        var updated = request.withMeasurements(List.of(measurement));

        assertThat(updated.logDate()).isEqualTo(request.logDate());
        assertThat(updated.adherenceLevel()).isEqualTo(request.adherenceLevel());
        assertThat(updated.appetiteLevel()).isEqualTo(request.appetiteLevel());
        assertThat(updated.notes()).isEqualTo(request.notes());
        assertThat(updated.meals()).containsExactly(meal);
        assertThat(updated.deviations()).containsExactly(deviation);
        assertThat(updated.photoReferences()).containsExactly(photo);
        assertThat(updated.measurements()).containsExactly(measurement);
    }

    @Test
    void formToRequestFiltersBlankOptionalRowsAndKeepsPopulatedRows() {
        var form = new DietLogForm();
        form.setLogDate(LocalDate.of(2026, 6, 10));
        form.setAdherenceLevel(DietAdherenceLevel.PARTIAL);
        form.setAppetiteLevel(AppetiteLevel.VARIABLE);
        form.setNotes("  Felt fine overall  ");

        var blankMeal = new DietLogForm.MealRow();
        var meal = new DietLogForm.MealRow();
        meal.setMealType(MealType.DINNER);
        meal.setFoodCategory(FoodCategory.LOW_CARB_VEGETABLES);
        meal.setFoodDescription("Steamed greens");
        meal.setNotes("Good tolerance");
        form.setMeals(List.of(blankMeal, meal));

        var blankDeviation = new DietLogForm.DeviationRow();
        var deviation = new DietLogForm.DeviationRow();
        deviation.setDeviationCategory(DietDeviationCategory.EXCESS_CARBS);
        deviation.setSeverity(DietDeviationSeverity.MODERATE);
        deviation.setNotes("Small dessert");
        form.setDeviations(List.of(blankDeviation, deviation));

        var blankPhoto = new DietLogForm.PhotoReferenceRow();
        var photo = new DietLogForm.PhotoReferenceRow();
        photo.setUploadId(51L);
        photo.setCaption("Dinner plate");
        form.setPhotoReferences(List.of(blankPhoto, photo));

        var blankMeasurement = new DietLogForm.MeasurementRow();
        var measurement = new DietLogForm.MeasurementRow();
        measurement.setMeasurementType(MeasurementType.KETONE);
        measurement.setValue(new BigDecimal("1.20"));
        measurement.setUnit(MeasurementUnit.MMOL_L);
        measurement.setMeasuredAt(Instant.parse("2026-06-10T20:00:00Z"));
        measurement.setContext(MeasurementContext.BEDTIME);
        measurement.setNotes("Evening");
        form.setMeasurements(List.of(blankMeasurement, measurement));

        var request = form.toRequest();

        assertThat(request.logDate()).isEqualTo(LocalDate.of(2026, 6, 10));
        assertThat(request.adherenceLevel()).isEqualTo(DietAdherenceLevel.PARTIAL);
        assertThat(request.appetiteLevel()).isEqualTo(AppetiteLevel.VARIABLE);
        assertThat(request.notes()).isEqualTo("  Felt fine overall  ");
        assertThat(request.mealsOrEmpty()).singleElement()
                .satisfies(row -> {
                    assertThat(row.mealType()).isEqualTo(MealType.DINNER);
                    assertThat(row.foodCategory()).isEqualTo(FoodCategory.LOW_CARB_VEGETABLES);
                    assertThat(row.foodDescription()).isEqualTo("Steamed greens");
                    assertThat(row.notes()).isEqualTo("Good tolerance");
                });
        assertThat(request.deviationsOrEmpty()).singleElement()
                .satisfies(row -> {
                    assertThat(row.deviationCategory()).isEqualTo(DietDeviationCategory.EXCESS_CARBS);
                    assertThat(row.severity()).isEqualTo(DietDeviationSeverity.MODERATE);
                    assertThat(row.notes()).isEqualTo("Small dessert");
                });
        assertThat(request.photoReferencesOrEmpty()).singleElement()
                .satisfies(row -> {
                    assertThat(row.uploadId()).isEqualTo(51L);
                    assertThat(row.caption()).isEqualTo("Dinner plate");
                });
        assertThat(request.measurementsOrEmpty()).singleElement()
                .satisfies(row -> {
                    assertThat(row.measurementType()).isEqualTo(MeasurementType.KETONE);
                    assertThat(row.value()).isEqualByComparingTo("1.20");
                    assertThat(row.unit()).isEqualTo(MeasurementUnit.MMOL_L);
                    assertThat(row.measuredAt()).isEqualTo(Instant.parse("2026-06-10T20:00:00Z"));
                    assertThat(row.context()).isEqualTo(MeasurementContext.BEDTIME);
                    assertThat(row.notes()).isEqualTo("Evening");
                });
    }

    @Test
    void formToRequestFiltersMeasurementRowWithOnlyUnit() {
        var form = new DietLogForm();
        form.setLogDate(LocalDate.of(2026, 6, 10));
        form.setAdherenceLevel(DietAdherenceLevel.FULL);
        form.setAppetiteLevel(AppetiteLevel.NORMAL);

        var unitOnlyMeasurement = new DietLogForm.MeasurementRow();
        unitOnlyMeasurement.setUnit(MeasurementUnit.MMOL_L);
        form.setMeasurements(List.of(unitOnlyMeasurement));

        var request = form.toRequest();

        assertThat(request.measurementsOrEmpty()).isEmpty();
    }

    @Test
    void dailyDietLogResponseMapsPatientChildrenMeasurementsAndNullMeasurements() {
        var patient = patientProfile(10L, "patient-response@example.com");
        var log = new DailyDietLog(patient, LocalDate.of(2026, 6, 10));
        ReflectionTestUtils.setField(log, "id", 20L);
        log.setAdherenceLevel(DietAdherenceLevel.MOSTLY);
        log.setAppetiteLevel(AppetiteLevel.NORMAL);
        log.setNotes("Stable day");

        var meal = new DailyDietLogMeal(
                MealType.LUNCH,
                FoodCategory.PROTEIN,
                "Chicken",
                "Lunch notes",
                1);
        ReflectionTestUtils.setField(meal, "id", 30L);
        log.addMeal(meal);

        var deviation = new DailyDietLogDeviation(
                DietDeviationCategory.DINING_OUT,
                DietDeviationSeverity.MINOR,
                "Small deviation",
                2);
        ReflectionTestUtils.setField(deviation, "id", 40L);
        deviation.setMeal(meal);
        log.addDeviation(deviation);

        var photo = new DailyDietLogPhotoReference(
                "plate.jpg",
                "image/jpeg",
                2048L,
                "diet/plate.jpg",
                "Lunch plate",
                3);
        ReflectionTestUtils.setField(photo, "id", 50L);
        photo.setMeal(meal);
        log.addPhotoReference(photo);

        var measurement = measurementEntry(60L, patient, log);

        var response = DailyDietLogResponse.from(log, List.of(measurement));
        var responseWithNullMeasurements = DailyDietLogResponse.from(log, null);

        assertThat(response.id()).isEqualTo(20L);
        assertThat(response.patientProfileId()).isEqualTo(10L);
        assertThat(response.patientEmail()).isEqualTo("patient-response@example.com");
        assertThat(response.logDate()).isEqualTo(LocalDate.of(2026, 6, 10));
        assertThat(response.adherenceLevel()).isEqualTo(DietAdherenceLevel.MOSTLY);
        assertThat(response.appetiteLevel()).isEqualTo(AppetiteLevel.NORMAL);
        assertThat(response.notes()).isEqualTo("Stable day");
        assertThat(response.meals()).singleElement()
                .satisfies(row -> {
                    assertThat(row.id()).isEqualTo(30L);
                    assertThat(row.mealType()).isEqualTo(MealType.LUNCH);
                    assertThat(row.foodCategory()).isEqualTo(FoodCategory.PROTEIN);
                    assertThat(row.foodDescription()).isEqualTo("Chicken");
                    assertThat(row.notes()).isEqualTo("Lunch notes");
                    assertThat(row.sortOrder()).isEqualTo(1);
                });
        assertThat(response.deviations()).singleElement()
                .satisfies(row -> {
                    assertThat(row.id()).isEqualTo(40L);
                    assertThat(row.mealId()).isEqualTo(30L);
                    assertThat(row.deviationCategory()).isEqualTo(DietDeviationCategory.DINING_OUT);
                    assertThat(row.severity()).isEqualTo(DietDeviationSeverity.MINOR);
                    assertThat(row.notes()).isEqualTo("Small deviation");
                    assertThat(row.sortOrder()).isEqualTo(2);
                });
        assertThat(response.photoReferences()).singleElement()
                .satisfies(row -> {
                    assertThat(row.id()).isEqualTo(50L);
                    assertThat(row.mealId()).isEqualTo(30L);
                    assertThat(row.originalFilename()).isEqualTo("plate.jpg");
                    assertThat(row.contentType()).isEqualTo("image/jpeg");
                    assertThat(row.sizeBytes()).isEqualTo(2048L);
                    assertThat(row.caption()).isEqualTo("Lunch plate");
                    assertThat(row.contentUrl()).isEqualTo("/api/diet-log-photos/50/content");
                    assertThat(row.sortOrder()).isEqualTo(3);
                });
        assertThat(response.measurements()).singleElement()
                .satisfies(row -> {
                    assertThat(row.id()).isEqualTo(60L);
                    assertThat(row.patientProfileId()).isEqualTo(10L);
                    assertThat(row.dailyDietLogId()).isEqualTo(20L);
                });
        assertThat(responseWithNullMeasurements.measurements()).isEmpty();
    }

    @Test
    void dailyMeasurementEntryResponseMapsPatientAndLogIds() {
        var patient = patientProfile(11L, "measurement-response@example.com");
        var log = new DailyDietLog(patient, LocalDate.of(2026, 6, 11));
        ReflectionTestUtils.setField(log, "id", 21L);
        var entry = measurementEntry(61L, patient, log);

        var response = DailyMeasurementEntryResponse.from(entry);

        assertThat(response.id()).isEqualTo(61L);
        assertThat(response.patientProfileId()).isEqualTo(11L);
        assertThat(response.dailyDietLogId()).isEqualTo(21L);
        assertThat(response.measurementType()).isEqualTo(MeasurementType.GLUCOSE);
        assertThat(response.value()).isEqualByComparingTo("5.70");
        assertThat(response.unit()).isEqualTo(MeasurementUnit.MMOL_L);
        assertThat(response.measuredAt()).isEqualTo(Instant.parse("2026-06-10T06:00:00Z"));
        assertThat(response.context()).isEqualTo(MeasurementContext.FASTING);
        assertThat(response.notes()).isEqualTo("Morning glucose");
        assertThat(response.createdAt()).isNotNull();
    }

    private static PatientProfile patientProfile(Long id, String email) {
        var user = new User(email, "hash");
        user.addRole(RoleName.PATIENT);
        ReflectionTestUtils.setField(user, "id", id + 100);
        var patient = new PatientProfile(user);
        ReflectionTestUtils.setField(patient, "id", id);
        return patient;
    }

    private static DailyMeasurementEntry measurementEntry(Long id, PatientProfile patient, DailyDietLog log) {
        var entry = new DailyMeasurementEntry(
                patient,
                log,
                MeasurementType.GLUCOSE,
                new BigDecimal("5.70"),
                MeasurementUnit.MMOL_L,
                Instant.parse("2026-06-10T06:00:00Z"),
                MeasurementContext.FASTING,
                "Morning glucose");
        ReflectionTestUtils.setField(entry, "id", id);
        return entry;
    }
}
