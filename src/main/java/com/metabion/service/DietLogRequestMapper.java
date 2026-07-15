package com.metabion.service;

import com.metabion.domain.DailyDietLog;
import com.metabion.domain.DailyDietLogDeviation;
import com.metabion.domain.DailyDietLogMeal;
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

    public void applyTo(DailyDietLog log, DailyDietLogRequest request) {
        log.setLogDate(request.logDate());
        log.setAdherenceLevel(request.adherenceLevel());
        log.setAppetiteLevel(request.appetiteLevel());
        log.setNotes(trimToNull(request.notes()));
        log.setMetadata(trimToNull(request.metadata()));
        var meals = mealsFrom(request);
        log.replaceChildren(meals, deviationsFrom(request, meals));
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
            meals.add(new DailyDietLogMeal(
                    meal.mealType(),
                    trimToNull(meal.foodDescription()),
                    trimToNull(meal.notes()),
                    i));
        }
        return meals;
    }

    private List<DailyDietLogDeviation> deviationsFrom(DailyDietLogRequest request,
                                                       List<DailyDietLogMeal> meals) {
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
            var mapped = new DailyDietLogDeviation(
                    deviation.deviationCategory(),
                    deviation.severity(),
                    trimToNull(deviation.notes()),
                    i);
            if (deviation.mealIndex() == null
                    || deviation.mealIndex() < 0
                    || deviation.mealIndex() >= meals.size()) {
                throw badRequest("deviation mealIndex is invalid");
            }
            mapped.setMeal(meals.get(deviation.mealIndex()));
            deviations.add(mapped);
        }
        return deviations;
    }

    private static ResponseStatusException badRequest(String reason) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
    }
}
