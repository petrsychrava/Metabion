package com.metabion.dto;

import com.metabion.domain.AppetiteLevel;
import com.metabion.domain.DietAdherenceLevel;
import com.metabion.domain.DietDeviationCategory;
import com.metabion.domain.DietDeviationSeverity;
import com.metabion.domain.FoodCategory;
import com.metabion.domain.MealType;
import com.metabion.domain.MeasurementContext;
import com.metabion.domain.MeasurementType;
import com.metabion.domain.MeasurementUnit;
import org.junit.jupiter.api.Test;

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
        var photo = new DailyDietLogRequest.PhotoReferenceRequest(
                "meal.jpg",
                "image/jpeg",
                128L,
                "diet/meal.jpg",
                "Breakfast");
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
        photo.setOriginalFilename("dinner.jpg");
        photo.setContentType("image/jpeg");
        photo.setSizeBytes(1024L);
        photo.setStorageKey("diet/dinner.jpg");
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
                    assertThat(row.originalFilename()).isEqualTo("dinner.jpg");
                    assertThat(row.contentType()).isEqualTo("image/jpeg");
                    assertThat(row.sizeBytes()).isEqualTo(1024L);
                    assertThat(row.storageKey()).isEqualTo("diet/dinner.jpg");
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
}
