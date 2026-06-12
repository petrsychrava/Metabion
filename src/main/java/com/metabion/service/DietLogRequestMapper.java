package com.metabion.service;

import com.metabion.domain.DailyDietLog;
import com.metabion.domain.DailyDietLogDeviation;
import com.metabion.domain.DailyDietLogMeal;
import com.metabion.domain.DailyDietLogPhotoReference;
import com.metabion.domain.DailyMeasurementEntry;
import com.metabion.domain.PatientProfile;
import com.metabion.dto.DailyDietLogRequest;
import com.metabion.dto.DailyMeasurementEntryRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;

@Service
public class DietLogRequestMapper {

    private final StorageKeyValidator storageKeyValidator;

    public DietLogRequestMapper(StorageKeyValidator storageKeyValidator) {
        this.storageKeyValidator = storageKeyValidator;
    }

    public void applyTo(DailyDietLog log, DailyDietLogRequest request) {
        log.setLogDate(request.logDate());
        log.setAdherenceLevel(request.adherenceLevel());
        log.setAppetiteLevel(request.appetiteLevel());
        log.setNotes(trimToNull(request.notes()));
        log.setMetadata(trimToNull(request.metadata()));
        log.replaceChildren(mealsFrom(request), deviationsFrom(request), photoReferencesFrom(request));
    }

    public DailyMeasurementEntry measurementFrom(PatientProfile patient,
                                                 DailyDietLog log,
                                                 DailyMeasurementEntryRequest request) {
        return new DailyMeasurementEntry(
                patient,
                log,
                request.measurementType(),
                request.value(),
                request.unit(),
                request.measuredAt(),
                request.context(),
                trimToNull(request.notes()),
                trimToNull(request.metadata()));
    }

    static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        var trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private List<DailyDietLogMeal> mealsFrom(DailyDietLogRequest request) {
        var requests = request.mealsOrEmpty();
        var meals = new ArrayList<DailyDietLogMeal>(requests.size());
        for (var i = 0; i < requests.size(); i++) {
            var meal = requests.get(i);
            if (meal == null) {
                throw badRequest("meal is required");
            }
            if (meal.mealType() == null) {
                throw badRequest("mealType is required");
            }
            if (meal.foodCategory() == null) {
                throw badRequest("foodCategory is required");
            }
            meals.add(new DailyDietLogMeal(
                    meal.mealType(),
                    meal.foodCategory(),
                    trimToNull(meal.foodDescription()),
                    trimToNull(meal.notes()),
                    i));
        }
        return meals;
    }

    private List<DailyDietLogDeviation> deviationsFrom(DailyDietLogRequest request) {
        var requests = request.deviationsOrEmpty();
        var deviations = new ArrayList<DailyDietLogDeviation>(requests.size());
        for (var i = 0; i < requests.size(); i++) {
            var deviation = requests.get(i);
            if (deviation == null) {
                throw badRequest("deviation is required");
            }
            if (deviation.deviationCategory() == null) {
                throw badRequest("deviationCategory is required");
            }
            if (deviation.severity() == null) {
                throw badRequest("severity is required");
            }
            deviations.add(new DailyDietLogDeviation(
                    deviation.deviationCategory(),
                    deviation.severity(),
                    trimToNull(deviation.notes()),
                    i));
        }
        return deviations;
    }

    private List<DailyDietLogPhotoReference> photoReferencesFrom(DailyDietLogRequest request) {
        var requests = request.photoReferencesOrEmpty();
        var photoReferences = new ArrayList<DailyDietLogPhotoReference>(requests.size());
        for (var i = 0; i < requests.size(); i++) {
            var photo = requests.get(i);
            if (photo == null) {
                throw badRequest("photoReference is required");
            }
            var storageKey = trimToNull(photo.storageKey());
            storageKeyValidator.validate(storageKey);
            photoReferences.add(new DailyDietLogPhotoReference(
                    trimToNull(photo.originalFilename()),
                    trimToNull(photo.contentType()),
                    photo.sizeBytes(),
                    storageKey,
                    trimToNull(photo.caption()),
                    i));
        }
        return photoReferences;
    }

    private static ResponseStatusException badRequest(String reason) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
    }
}
