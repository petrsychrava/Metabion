package com.metabion.dto;

import com.metabion.domain.PatientProfile;
import com.metabion.domain.Sex;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Size;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.ZoneId;

public record PatientProfileForm(
        @NotNull @Past LocalDate dateOfBirth,
        @NotNull Sex sex,
        @NotBlank @Size(max = 100) String countryRegion,
        @NotBlank @Size(max = 100) String timezone
) {

    public static PatientProfileForm from(PatientProfile patient) {
        return new PatientProfileForm(
                patient.getDateOfBirth(),
                patient.getSex(),
                patient.getCountryRegion(),
                patient.getTimezone());
    }

    @AssertTrue(message = "timezone must be a valid ZoneId")
    public boolean isTimezoneValid() {
        if (timezone == null || timezone.isBlank()) {
            return true;
        }
        try {
            ZoneId.of(timezone.trim());
            return true;
        } catch (DateTimeException ex) {
            return false;
        }
    }
}
