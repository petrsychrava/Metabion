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

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

public class DietLogForm {

    private LocalDate logDate;
    private DietAdherenceLevel adherenceLevel;
    private AppetiteLevel appetiteLevel;
    private String notes;
    private List<MealRow> meals = new ArrayList<>();
    private List<DeviationRow> deviations = new ArrayList<>();
    private List<PhotoReferenceRow> photoReferences = new ArrayList<>();
    private List<MeasurementRow> measurements = new ArrayList<>();
    private MeasurementUnit glucoseUnitPreference;

    public DailyDietLogRequest toRequest() {
        return new DailyDietLogRequest(
                logDate,
                adherenceLevel,
                appetiteLevel,
                notes,
                mealsOrEmpty().stream()
                        .filter(row -> !row.isBlank())
                        .map(MealRow::toRequest)
                        .toList(),
                deviationsOrEmpty().stream()
                        .filter(row -> !row.isBlank())
                        .map(DeviationRow::toRequest)
                        .toList(),
                photoReferencesOrEmpty().stream()
                        .filter(row -> !row.isBlank())
                        .map(PhotoReferenceRow::toRequest)
                        .toList(),
                measurementsOrEmpty().stream()
                        .filter(row -> !row.isBlank())
                        .map(MeasurementRow::toRequest)
                        .toList());
    }

    public LocalDate getLogDate() {
        return logDate;
    }

    public void setLogDate(LocalDate logDate) {
        this.logDate = logDate;
    }

    public DietAdherenceLevel getAdherenceLevel() {
        return adherenceLevel;
    }

    public void setAdherenceLevel(DietAdherenceLevel adherenceLevel) {
        this.adherenceLevel = adherenceLevel;
    }

    public AppetiteLevel getAppetiteLevel() {
        return appetiteLevel;
    }

    public void setAppetiteLevel(AppetiteLevel appetiteLevel) {
        this.appetiteLevel = appetiteLevel;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public List<MealRow> getMeals() {
        return meals;
    }

    public void setMeals(List<MealRow> meals) {
        this.meals = meals;
    }

    public List<DeviationRow> getDeviations() {
        return deviations;
    }

    public void setDeviations(List<DeviationRow> deviations) {
        this.deviations = deviations;
    }

    public List<PhotoReferenceRow> getPhotoReferences() {
        return photoReferences;
    }

    public void setPhotoReferences(List<PhotoReferenceRow> photoReferences) {
        this.photoReferences = photoReferences;
    }

    public List<MeasurementRow> getMeasurements() {
        return measurements;
    }

    public void setMeasurements(List<MeasurementRow> measurements) {
        this.measurements = measurements;
    }

    public MeasurementUnit getGlucoseUnitPreference() {
        return glucoseUnitPreference;
    }

    public void setGlucoseUnitPreference(MeasurementUnit glucoseUnitPreference) {
        this.glucoseUnitPreference = glucoseUnitPreference;
    }

    private List<MealRow> mealsOrEmpty() {
        return meals == null ? List.of() : meals;
    }

    private List<DeviationRow> deviationsOrEmpty() {
        return deviations == null ? List.of() : deviations;
    }

    private List<PhotoReferenceRow> photoReferencesOrEmpty() {
        return photoReferences == null ? List.of() : photoReferences;
    }

    private List<MeasurementRow> measurementsOrEmpty() {
        return measurements == null ? List.of() : measurements;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    public static class MealRow {
        private MealType mealType;
        private FoodCategory foodCategory;
        private String foodDescription;
        private String notes;

        public DailyDietLogRequest.MealRequest toRequest() {
            return new DailyDietLogRequest.MealRequest(mealType, foodCategory, foodDescription, notes);
        }

        boolean isBlank() {
            return mealType == null
                    && foodCategory == null
                    && blank(foodDescription)
                    && blank(notes);
        }

        public MealType getMealType() {
            return mealType;
        }

        public void setMealType(MealType mealType) {
            this.mealType = mealType;
        }

        public FoodCategory getFoodCategory() {
            return foodCategory;
        }

        public void setFoodCategory(FoodCategory foodCategory) {
            this.foodCategory = foodCategory;
        }

        public String getFoodDescription() {
            return foodDescription;
        }

        public void setFoodDescription(String foodDescription) {
            this.foodDescription = foodDescription;
        }

        public String getNotes() {
            return notes;
        }

        public void setNotes(String notes) {
            this.notes = notes;
        }
    }

    public static class DeviationRow {
        private DietDeviationCategory deviationCategory;
        private DietDeviationSeverity severity;
        private String notes;

        public DailyDietLogRequest.DeviationRequest toRequest() {
            return new DailyDietLogRequest.DeviationRequest(deviationCategory, severity, notes);
        }

        boolean isBlank() {
            return deviationCategory == null
                    && severity == null
                    && blank(notes);
        }

        public DietDeviationCategory getDeviationCategory() {
            return deviationCategory;
        }

        public void setDeviationCategory(DietDeviationCategory deviationCategory) {
            this.deviationCategory = deviationCategory;
        }

        public DietDeviationSeverity getSeverity() {
            return severity;
        }

        public void setSeverity(DietDeviationSeverity severity) {
            this.severity = severity;
        }

        public String getNotes() {
            return notes;
        }

        public void setNotes(String notes) {
            this.notes = notes;
        }
    }

    public static class PhotoReferenceRow {
        private String originalFilename;
        private String contentType;
        private Long sizeBytes;
        private String storageKey;
        private String caption;

        public DailyDietLogRequest.PhotoReferenceRequest toRequest() {
            return new DailyDietLogRequest.PhotoReferenceRequest(
                    originalFilename,
                    contentType,
                    sizeBytes,
                    storageKey,
                    caption);
        }

        boolean isBlank() {
            return blank(originalFilename)
                    && blank(contentType)
                    && sizeBytes == null
                    && blank(storageKey)
                    && blank(caption);
        }

        public String getOriginalFilename() {
            return originalFilename;
        }

        public void setOriginalFilename(String originalFilename) {
            this.originalFilename = originalFilename;
        }

        public String getContentType() {
            return contentType;
        }

        public void setContentType(String contentType) {
            this.contentType = contentType;
        }

        public Long getSizeBytes() {
            return sizeBytes;
        }

        public void setSizeBytes(Long sizeBytes) {
            this.sizeBytes = sizeBytes;
        }

        public String getStorageKey() {
            return storageKey;
        }

        public void setStorageKey(String storageKey) {
            this.storageKey = storageKey;
        }

        public String getCaption() {
            return caption;
        }

        public void setCaption(String caption) {
            this.caption = caption;
        }
    }

    public static class MeasurementRow {
        private MeasurementType measurementType;
        private BigDecimal value;
        private MeasurementUnit unit;
        private Instant measuredAt;
        private MeasurementContext context;
        private String notes;

        public DailyMeasurementEntryRequest toRequest() {
            return new DailyMeasurementEntryRequest(
                    measurementType,
                    value,
                    unit,
                    measuredAt,
                    context,
                    notes);
        }

        boolean isBlank() {
            return measurementType == null
                    && value == null
                    && measuredAt == null
                    && context == null
                    && blank(notes);
        }

        public MeasurementType getMeasurementType() {
            return measurementType;
        }

        public void setMeasurementType(MeasurementType measurementType) {
            this.measurementType = measurementType;
        }

        public BigDecimal getValue() {
            return value;
        }

        public void setValue(BigDecimal value) {
            this.value = value;
        }

        public MeasurementUnit getUnit() {
            return unit;
        }

        public void setUnit(MeasurementUnit unit) {
            this.unit = unit;
        }

        public Instant getMeasuredAt() {
            return measuredAt;
        }

        public void setMeasuredAt(Instant measuredAt) {
            this.measuredAt = measuredAt;
        }

        public MeasurementContext getContext() {
            return context;
        }

        public void setContext(MeasurementContext context) {
            this.context = context;
        }

        public String getNotes() {
            return notes;
        }

        public void setNotes(String notes) {
            this.notes = notes;
        }
    }
}
