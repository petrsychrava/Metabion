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
import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.format.annotation.DateTimeFormat;

import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

public class DietLogForm {

    @NotNull
    private LocalDate logDate;

    @NotNull
    private DietAdherenceLevel adherenceLevel;

    @NotNull
    private AppetiteLevel appetiteLevel;

    @Size(max = 1000)
    private String notes;

    @Valid
    private List<MealRow> meals = new ArrayList<>();

    @Valid
    private MeasurementRow glucoseMeasurement = new MeasurementRow();

    @Valid
    private MeasurementRow ketoneMeasurement = new MeasurementRow();

    private String patientTimezone = "UTC";

    private MeasurementUnit glucoseUnitPreference;

    public DailyDietLogRequest toRequest() {
        var populatedMeals = mealsOrEmpty().stream()
                .filter(row -> !row.isBlank())
                .toList();
        var deviations = deviationsFrom(populatedMeals);
        var photoReferences = photoReferencesFrom(populatedMeals);
        var measurements = fixedMeasurements();
        return new DailyDietLogRequest(
                logDate,
                adherenceLevel,
                appetiteLevel,
                notes,
                populatedMeals.stream()
                        .map(MealRow::toRequest)
                        .toList(),
                deviations,
                photoReferences,
                measurements);
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

    public MeasurementRow getGlucoseMeasurement() {
        if (glucoseMeasurement == null) {
            glucoseMeasurement = new MeasurementRow();
        }
        return glucoseMeasurement;
    }

    public void setGlucoseMeasurement(MeasurementRow glucoseMeasurement) {
        this.glucoseMeasurement = glucoseMeasurement;
    }

    public MeasurementRow getKetoneMeasurement() {
        if (ketoneMeasurement == null) {
            ketoneMeasurement = new MeasurementRow();
        }
        return ketoneMeasurement;
    }

    public void setKetoneMeasurement(MeasurementRow ketoneMeasurement) {
        this.ketoneMeasurement = ketoneMeasurement;
    }

    public String getPatientTimezone() {
        return patientTimezone;
    }

    public void setPatientTimezone(String patientTimezone) {
        this.patientTimezone = patientTimezone;
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

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private List<DailyDietLogRequest.DeviationRequest> deviationsFrom(List<MealRow> populatedMeals) {
        var result = new ArrayList<DailyDietLogRequest.DeviationRequest>();
        for (var i = 0; i < populatedMeals.size(); i++) {
            var deviation = populatedMeals.get(i).getDeviation();
            if (!deviation.isBlank()) {
                result.add(deviation.toRequest(i));
            }
        }
        return result;
    }

    private List<DailyDietLogRequest.PhotoUploadReferenceRequest> photoReferencesFrom(List<MealRow> populatedMeals) {
        var result = new ArrayList<DailyDietLogRequest.PhotoUploadReferenceRequest>();
        for (var i = 0; i < populatedMeals.size(); i++) {
            for (var photo : populatedMeals.get(i).getPhotoReferences()) {
                if (!photo.isBlank()) {
                    result.add(photo.toRequest(i));
                }
            }
        }
        return result;
    }

    private List<DailyMeasurementEntryRequest> fixedMeasurements() {
        var result = new ArrayList<DailyMeasurementEntryRequest>();
        if (glucoseMeasurement != null && !glucoseMeasurement.isBlank()) {
            result.add(glucoseMeasurement.toRequest(
                    MeasurementType.GLUCOSE,
                    glucoseUnitPreference == null ? MeasurementUnit.MMOL_L : glucoseUnitPreference,
                    measuredAt(glucoseMeasurement)));
        }
        if (ketoneMeasurement != null && !ketoneMeasurement.isBlank()) {
            result.add(ketoneMeasurement.toRequest(
                    MeasurementType.KETONE,
                    MeasurementUnit.MMOL_L,
                    measuredAt(ketoneMeasurement)));
        }
        return result;
    }

    private Instant measuredAt(MeasurementRow row) {
        if (logDate == null || row.getMeasuredTime() == null) {
            return null;
        }
        var zone = zoneOrUtc(patientTimezone);
        return logDate.atTime(row.getMeasuredTime()).atZone(zone).toInstant();
    }

    private ZoneId zoneOrUtc(String zoneId) {
        if (zoneId == null || zoneId.isBlank()) {
            return ZoneId.of("UTC");
        }
        try {
            return ZoneId.of(zoneId);
        } catch (DateTimeException exception) {
            return ZoneId.of("UTC");
        }
    }

    public static class MealRow {
        private MealType mealType;
        private FoodCategory foodCategory;

        @Size(max = 500)
        private String foodDescription;

        @Size(max = 1000)
        private String notes;

        @Valid
        private DeviationRow deviation = new DeviationRow();

        @Valid
        private List<PhotoReferenceRow> photoReferences = new ArrayList<>();

        public DailyDietLogRequest.MealRequest toRequest() {
            return new DailyDietLogRequest.MealRequest(mealType, foodCategory, foodDescription, notes);
        }

        boolean isBlank() {
            return mealType == null
                    && foodCategory == null
                    && blank(foodDescription)
                    && blank(notes)
                    && getDeviation().isBlank()
                    && getPhotoReferences().stream().allMatch(PhotoReferenceRow::isBlank);
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

        public DeviationRow getDeviation() {
            if (deviation == null) {
                deviation = new DeviationRow();
            }
            return deviation;
        }

        public void setDeviation(DeviationRow deviation) {
            this.deviation = deviation;
        }

        public List<PhotoReferenceRow> getPhotoReferences() {
            if (photoReferences == null) {
                photoReferences = new ArrayList<>();
            }
            return photoReferences;
        }

        public void setPhotoReferences(List<PhotoReferenceRow> photoReferences) {
            this.photoReferences = photoReferences;
        }
    }

    public static class DeviationRow {
        private DietDeviationCategory deviationCategory;
        private DietDeviationSeverity severity;

        @Size(max = 1000)
        private String notes;

        public DailyDietLogRequest.DeviationRequest toRequest(int mealIndex) {
            return new DailyDietLogRequest.DeviationRequest(mealIndex, deviationCategory, severity, notes);
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
        private Long uploadId;

        @Size(max = 500)
        private String caption;

        private String originalFilename;
        private String contentUrl;

        public DailyDietLogRequest.PhotoUploadReferenceRequest toRequest(int mealIndex) {
            return new DailyDietLogRequest.PhotoUploadReferenceRequest(mealIndex, uploadId, caption);
        }

        boolean isBlank() {
            return uploadId == null
                    && blank(caption);
        }

        public Long getUploadId() {
            return uploadId;
        }

        public void setUploadId(Long uploadId) {
            this.uploadId = uploadId;
        }

        public String getCaption() {
            return caption;
        }

        public void setCaption(String caption) {
            this.caption = caption;
        }

        public String getOriginalFilename() {
            return originalFilename;
        }

        public void setOriginalFilename(String originalFilename) {
            this.originalFilename = originalFilename;
        }

        public String getContentUrl() {
            return contentUrl;
        }

        public void setContentUrl(String contentUrl) {
            this.contentUrl = contentUrl;
        }
    }

    public static class MeasurementRow {
        private MeasurementType measurementType;

        @Digits(integer = 6, fraction = 2)
        private BigDecimal value;

        private MeasurementUnit unit;

        @DateTimeFormat(pattern = "HH:mm")
        private LocalTime measuredTime;

        private Instant measuredAt;
        private MeasurementContext context;

        @Size(max = 1000)
        private String notes;

        public DailyMeasurementEntryRequest toRequest(MeasurementType measurementType,
                                                      MeasurementUnit unit,
                                                      Instant measuredAt) {
            return new DailyMeasurementEntryRequest(
                    measurementType,
                    value,
                    unit,
                    measuredAt,
                    context,
                    notes);
        }

        boolean isBlank() {
            return value == null
                    && measuredTime == null
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

        public LocalTime getMeasuredTime() {
            return measuredTime;
        }

        public void setMeasuredTime(LocalTime measuredTime) {
            this.measuredTime = measuredTime;
        }

        public Instant getMeasuredAt() {
            return measuredAt;
        }

        public void setMeasuredAt(Instant measuredAt) {
            this.measuredAt = measuredAt;
            this.measuredTime = measuredAt == null ? null : measuredAt.atZone(java.time.ZoneOffset.UTC).toLocalTime();
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
