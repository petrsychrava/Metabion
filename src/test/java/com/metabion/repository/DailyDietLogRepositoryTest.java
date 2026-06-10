package com.metabion.repository;

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
import com.metabion.domain.Sex;
import com.metabion.domain.User;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest(properties = {"spring.jpa.hibernate.ddl-auto=validate"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class DailyDietLogRepositoryTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    UserRepository users;

    @Autowired
    PatientProfileRepository patientProfiles;

    @Autowired
    DailyDietLogRepository dailyDietLogs;

    @Autowired
    DailyMeasurementEntryRepository measurementEntries;

    @Autowired
    EntityManager entityManager;

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Test
    void persistsPatientGlucoseUnitPreferenceAndDailyLog() {
        var patient = createPatient("daily-log@example.com");
        patient.setGlucoseUnitPreference(MeasurementUnit.MG_DL);
        patientProfiles.saveAndFlush(patient);

        var log = new DailyDietLog(patient, LocalDate.of(2026, 6, 10));
        log.setAdherenceLevel(DietAdherenceLevel.MOSTLY);
        log.setAppetiteLevel(AppetiteLevel.NORMAL);
        log.setNotes("Stable day");
        dailyDietLogs.saveAndFlush(log);
        entityManager.clear();

        var loaded = dailyDietLogs.findByPatientProfileIdAndLogDate(patient.getId(), LocalDate.of(2026, 6, 10))
                .orElseThrow();

        assertThat(loaded.getPatientProfile().getGlucoseUnitPreference()).isEqualTo(MeasurementUnit.MG_DL);
        assertThat(loaded.getAdherenceLevel()).isEqualTo(DietAdherenceLevel.MOSTLY);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void oneDailyLogPerPatientDateIsEnforced() {
        var patient = createPatient("daily-log-unique@example.com");
        dailyDietLogs.saveAndFlush(new DailyDietLog(patient, LocalDate.of(2026, 6, 10)));

        assertThatThrownBy(() -> dailyDietLogs.saveAndFlush(new DailyDietLog(patient, LocalDate.of(2026, 6, 10))))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void cascadesMealsDeviationsAndPhotoReferences() {
        var patient = createPatient("daily-log-children@example.com");
        var log = new DailyDietLog(patient, LocalDate.of(2026, 6, 10));
        log.addMeal(new DailyDietLogMeal(
                MealType.BREAKFAST,
                FoodCategory.PROTEIN,
                "Eggs",
                "No issues",
                0));
        log.addDeviation(new DailyDietLogDeviation(
                DietDeviationCategory.DINING_OUT,
                DietDeviationSeverity.MINOR,
                "Restaurant meal",
                0));
        log.addPhotoReference(new DailyDietLogPhotoReference(
                "breakfast.jpg",
                "image/jpeg",
                1024L,
                "daily-logs/1/breakfast.jpg",
                "Breakfast",
                0));

        dailyDietLogs.saveAndFlush(log);
        entityManager.clear();

        var loaded = dailyDietLogs.findByPatientProfileIdAndLogDate(patient.getId(), LocalDate.of(2026, 6, 10))
                .orElseThrow();

        assertThat(loaded.getMeals()).hasSize(1);
        assertThat(loaded.getDeviations()).hasSize(1);
        assertThat(loaded.getPhotoReferences()).hasSize(1);
    }

    @Test
    void queriesMeasurementsByPatientAndRange() {
        var patient = createPatient("daily-measurement@example.com");
        var log = dailyDietLogs.saveAndFlush(new DailyDietLog(patient, LocalDate.of(2026, 6, 10)));
        measurementEntries.saveAndFlush(new DailyMeasurementEntry(
                patient,
                log,
                MeasurementType.GLUCOSE,
                new BigDecimal("5.8"),
                MeasurementUnit.MMOL_L,
                Instant.parse("2026-06-10T07:00:00Z"),
                MeasurementContext.FASTING,
                null));
        entityManager.clear();

        var entries = measurementEntries.findByPatientProfileIdAndMeasuredAtBetweenOrderByMeasuredAtDesc(
                patient.getId(),
                Instant.parse("2026-06-10T00:00:00Z"),
                Instant.parse("2026-06-11T00:00:00Z"));

        assertThat(entries).hasSize(1);
        assertThat(entries.getFirst().getMeasurementType()).isEqualTo(MeasurementType.GLUCOSE);
    }

    private PatientProfile createPatient(String email) {
        var profile = new PatientProfile(createUser(email, RoleName.PATIENT));
        profile.setDateOfBirth(LocalDate.of(1990, 1, 1));
        profile.setSex(Sex.FEMALE);
        profile.setCountryRegion("CZ");
        profile.setTimezone("Europe/Prague");
        return patientProfiles.saveAndFlush(profile);
    }

    private User createUser(String email, RoleName role) {
        var user = users.saveAndFlush(new User(email, "hash"));
        user.addRole(role);
        return users.saveAndFlush(user);
    }
}
