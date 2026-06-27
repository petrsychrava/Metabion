package com.metabion.service;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

@Service
public class DateRangeValidator {

    public void validate(LocalDate from, LocalDate to) {
        if (from == null || to == null) {
            throw badRequest("from and to are required");
        }
        if (from.isAfter(to)) {
            throw badRequest("from must be on or before to");
        }
        if (ChronoUnit.DAYS.between(from, to) > 370) {
            throw badRequest("date range cannot exceed 370 days");
        }
    }

    private static ResponseStatusException badRequest(String reason) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
    }
}
