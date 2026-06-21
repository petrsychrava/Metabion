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
import java.time.LocalTime;
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
        assertThat(updated.deviations().getFirst().mealIndex()).isNull();
        assertThat(updated.photoReferences()).containsExactly(photo);
        assertThat(updated.photoReferences().getFirst().mealIndex()).isNull();
        assertThat(updated.measurements()).containsExactly(measurement);
    }

    @Test
    void formToRequestMapsMealScopedDeviationPhotosAndFixedMeasurements() {
        var form = new DietLogForm();
        form.setLogDate(LocalDate.of(2026, 6, 10));
        form.setAdherenceLevel(DietAdherenceLevel.PARTIAL);
        form.setAppetiteLevel(AppetiteLevel.VARIABLE);
        form.setNotes("  Felt fine overall  ");
        form.setGlucoseUnitPreference(MeasurementUnit.MG_DL);
        form.setPatientTimezone("Europe/Prague");

        var blankMeal = new DietLogForm.MealRow();
        var meal = new DietLogForm.MealRow();
        meal.setMealType(MealType.DINNER);
        meal.setFoodCategory(FoodCategory.LOW_CARB_VEGETABLES);
        meal.setFoodDescription("Steamed greens");
        meal.setNotes("Good tolerance");
        meal.getDeviation().setDeviationCategory(DietDeviationCategory.EXCESS_CARBS);
        meal.getDeviation().setSeverity(DietDeviationSeverity.MODERATE);
        meal.getDeviation().setNotes("Small dessert");
        var photo = new DietLogForm.PhotoReferenceRow();
        photo.setUploadId(51L);
        photo.setCaption("Dinner plate");
        meal.setPhotoReferences(List.of(new DietLogForm.PhotoReferenceRow(), photo));
        form.setMeals(List.of(blankMeal, meal));

        var glucose = new DietLogForm.MeasurementRow();
        glucose.setValue(new BigDecimal("104.00"));
        glucose.setMeasuredTime(LocalTime.of(7, 30));
        glucose.setContext(MeasurementContext.FASTING);
        glucose.setNotes("Morning");
        form.setGlucoseMeasurement(glucose);

        var ketone = new DietLogForm.MeasurementRow();
        ketone.setValue(new BigDecimal("1.20"));
        ketone.setMeasuredTime(LocalTime.of(20, 0));
        ketone.setContext(MeasurementContext.BEDTIME);
        ketone.setNotes("Evening");
        form.setKetoneMeasurement(ketone);

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
                    assertThat(row.mealIndex()).isZero();
                    assertThat(row.deviationCategory()).isEqualTo(DietDeviationCategory.EXCESS_CARBS);
                    assertThat(row.severity()).isEqualTo(DietDeviationSeverity.MODERATE);
                    assertThat(row.notes()).isEqualTo("Small dessert");
                });
        assertThat(request.photoReferencesOrEmpty()).singleElement()
                .satisfies(row -> {
                    assertThat(row.mealIndex()).isZero();
                    assertThat(row.uploadId()).isEqualTo(51L);
                    assertThat(row.caption()).isEqualTo("Dinner plate");
                });
        assertThat(request.measurementsOrEmpty()).hasSize(2);
        assertThat(request.measurementsOrEmpty()).extracting("measurementType")
                .containsExactly(MeasurementType.GLUCOSE, MeasurementType.KETONE);
        assertThat(request.measurementsOrEmpty().get(0).value()).isEqualByComparingTo("104.00");
        assertThat(request.measurementsOrEmpty().get(0).unit()).isEqualTo(MeasurementUnit.MG_DL);
        assertThat(request.measurementsOrEmpty().get(0).measuredAt())
                .isEqualTo(Instant.parse("2026-06-10T05:30:00Z"));
        assertThat(request.measurementsOrEmpty().get(0).context()).isEqualTo(MeasurementContext.FASTING);
        assertThat(request.measurementsOrEmpty().get(0).notes()).isEqualTo("Morning");
        assertThat(request.measurementsOrEmpty().get(1).value()).isEqualByComparingTo("1.20");
        assertThat(request.measurementsOrEmpty().get(1).unit()).isEqualTo(MeasurementUnit.MMOL_L);
        assertThat(request.measurementsOrEmpty().get(1).measuredAt())
                .isEqualTo(Instant.parse("2026-06-10T18:00:00Z"));
        assertThat(request.measurementsOrEmpty().get(1).context()).isEqualTo(MeasurementContext.BEDTIME);
        assertThat(request.measurementsOrEmpty().get(1).notes()).isEqualTo("Evening");
    }

    @Test
    void formToRequestFiltersEmptyFixedMeasurementRows() {
        var form = new DietLogForm();
        form.setLogDate(LocalDate.of(2026, 6, 10));
        form.setAdherenceLevel(DietAdherenceLevel.FULL);
        form.setAppetiteLevel(AppetiteLevel.NORMAL);
        form.setGlucoseUnitPreference(MeasurementUnit.MMOL_L);
        form.setPatientTimezone("UTC");
        form.setGlucoseMeasurement(new DietLogForm.MeasurementRow());
        form.setKetoneMeasurement(new DietLogForm.MeasurementRow());

        var request = form.toRequest();

        assertThat(request.measurementsOrEmpty()).isEmpty();
    }

    @Test
    void formToRequestIncludesLegacyTopLevelOptionalRows() {
        var form = new DietLogForm();
        form.setLogDate(LocalDate.of(2026, 6, 10));
        form.setAdherenceLevel(DietAdherenceLevel.PARTIAL);
        form.setAppetiteLevel(AppetiteLevel.VARIABLE);

        var deviation = new DietLogForm.DeviationRow();
        deviation.setDeviationCategory(DietDeviationCategory.EXCESS_CARBS);
        deviation.setSeverity(DietDeviationSeverity.MODERATE);
        deviation.setNotes("Legacy dessert");
        form.setDeviations(List.of(new DietLogForm.DeviationRow(), deviation));

        var photo = new DietLogForm.PhotoReferenceRow();
        photo.setUploadId(52L);
        photo.setCaption("Legacy plate");
        form.setPhotoReferences(List.of(new DietLogForm.PhotoReferenceRow(), photo));

        var measuredAt = Instant.parse("2026-06-10T20:00:00Z");
        var measurement = new DietLogForm.MeasurementRow();
        measurement.setMeasurementType(MeasurementType.KETONE);
        measurement.setValue(new BigDecimal("1.20"));
        measurement.setUnit(MeasurementUnit.MMOL_L);
        measurement.setMeasuredAt(measuredAt);
        measurement.setContext(MeasurementContext.BEDTIME);
        measurement.setNotes("Legacy evening");
        form.setMeasurements(List.of(new DietLogForm.MeasurementRow(), measurement));

        var request = form.toRequest();

        assertThat(request.deviationsOrEmpty()).singleElement()
                .satisfies(row -> {
                    assertThat(row.mealIndex()).isNull();
                    assertThat(row.deviationCategory()).isEqualTo(DietDeviationCategory.EXCESS_CARBS);
                    assertThat(row.severity()).isEqualTo(DietDeviationSeverity.MODERATE);
                    assertThat(row.notes()).isEqualTo("Legacy dessert");
                });
        assertThat(request.photoReferencesOrEmpty()).singleElement()
                .satisfies(row -> {
                    assertThat(row.mealIndex()).isNull();
                    assertThat(row.uploadId()).isEqualTo(52L);
                    assertThat(row.caption()).isEqualTo("Legacy plate");
                });
        assertThat(request.measurementsOrEmpty()).singleElement()
                .satisfies(row -> {
                    assertThat(row.measurementType()).isEqualTo(MeasurementType.KETONE);
                    assertThat(row.value()).isEqualByComparingTo("1.20");
                    assertThat(row.unit()).isEqualTo(MeasurementUnit.MMOL_L);
                    assertThat(row.measuredAt()).isEqualTo(measuredAt);
                    assertThat(row.context()).isEqualTo(MeasurementContext.BEDTIME);
                    assertThat(row.notes()).isEqualTo("Legacy evening");
                });
        assertThat(measurement.getMeasuredAt()).isEqualTo(measuredAt);
    }

    @Test
    void formToRequestKeepsLegacyMeasurementWithOnlyTypeSelected() {
        var form = new DietLogForm();
        form.setLogDate(LocalDate.of(2026, 6, 10));
        form.setAdherenceLevel(DietAdherenceLevel.FULL);
        form.setAppetiteLevel(AppetiteLevel.NORMAL);

        var measurement = new DietLogForm.MeasurementRow();
        measurement.setMeasurementType(MeasurementType.GLUCOSE);
        form.setMeasurements(List.of(measurement));

        var request = form.toRequest();

        assertThat(request.measurementsOrEmpty()).singleElement()
                .satisfies(row -> {
                    assertThat(row.measurementType()).isEqualTo(MeasurementType.GLUCOSE);
                    assertThat(row.value()).isNull();
                    assertThat(row.unit()).isNull();
                    assertThat(row.measuredAt()).isNull();
                    assertThat(row.context()).isNull();
                });
    }

    @Test
    void formToRequestFallsBackToUtcForInvalidPatientTimezone() {
        var form = new DietLogForm();
        form.setLogDate(LocalDate.of(2026, 6, 10));
        form.setAdherenceLevel(DietAdherenceLevel.FULL);
        form.setAppetiteLevel(AppetiteLevel.NORMAL);
        form.setPatientTimezone("not/a-zone");

        var glucose = new DietLogForm.MeasurementRow();
        glucose.setValue(new BigDecimal("5.60"));
        glucose.setMeasuredTime(LocalTime.of(7, 30));
        form.setGlucoseMeasurement(glucose);

        var request = form.toRequest();

        assertThat(request.measurementsOrEmpty()).singleElement()
                .satisfies(row -> assertThat(row.measuredAt())
                        .isEqualTo(Instant.parse("2026-06-10T07:30:00Z")));
    }

    @Test
    void formToRequestUsesPostFilterMealIndexesForNestedDeviations() {
        var form = new DietLogForm();
        form.setLogDate(LocalDate.of(2026, 6, 10));
        form.setAdherenceLevel(DietAdherenceLevel.PARTIAL);
        form.setAppetiteLevel(AppetiteLevel.VARIABLE);

        var firstMeal = new DietLogForm.MealRow();
        firstMeal.setMealType(MealType.BREAKFAST);
        firstMeal.setFoodCategory(FoodCategory.PROTEIN);
        firstMeal.getDeviation().setDeviationCategory(DietDeviationCategory.DINING_OUT);
        firstMeal.getDeviation().setSeverity(DietDeviationSeverity.MINOR);

        var secondMeal = new DietLogForm.MealRow();
        secondMeal.setMealType(MealType.DINNER);
        secondMeal.setFoodCategory(FoodCategory.LOW_CARB_VEGETABLES);
        secondMeal.getDeviation().setDeviationCategory(DietDeviationCategory.EXCESS_CARBS);
        secondMeal.getDeviation().setSeverity(DietDeviationSeverity.MODERATE);

        form.setMeals(List.of(firstMeal, new DietLogForm.MealRow(), secondMeal));

        var request = form.toRequest();

        assertThat(request.deviationsOrEmpty()).extracting(DailyDietLogRequest.DeviationRequest::mealIndex)
                .containsExactly(0, 1);
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
