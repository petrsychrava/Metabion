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
import com.metabion.domain.DietLogPhotoStatus;
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
        var meal = new DailyDietLogMeal(
                MealType.BREAKFAST,
                "Eggs",
                "No issues",
                0);
        log.addMeal(meal);
        var deviation = new DailyDietLogDeviation(
                DietDeviationCategory.DINING_OUT,
                DietDeviationSeverity.MINOR,
                "Restaurant meal",
                0);
        deviation.setMeal(meal);
        log.addDeviation(deviation);
        var photoReference = DailyDietLogPhotoReference.pending(
                patient,
                patient.getUser(),
                "breakfast.jpg",
                "image/jpeg",
                1024L,
                "3".repeat(64),
                "daily-logs/1/breakfast.jpg");
        photoReference.attachTo(log, "Breakfast", 0);
        photoReference.setMeal(meal);
        log.addPhotoReference(photoReference);

        dailyDietLogs.saveAndFlush(log);
        entityManager.clear();

        var loaded = dailyDietLogs.findByPatientProfileIdAndLogDate(patient.getId(), LocalDate.of(2026, 6, 10))
                .orElseThrow();

        assertThat(loaded.getMeals()).hasSize(1);
        assertThat(loaded.getMeals()).singleElement()
                .satisfies(savedMeal -> {
                    assertThat(savedMeal.getMealType()).isEqualTo(MealType.BREAKFAST);
                    assertThat(savedMeal.getFoodDescription()).isEqualTo("Eggs");
                    assertThat(savedMeal.getNotes()).isEqualTo("No issues");
                    assertThat(savedMeal.getSortOrder()).isZero();
                });
        assertThat(loaded.getDeviations()).hasSize(1);
        assertThat(loaded.getPhotoReferences()).hasSize(1);
    }

    @Test
    void persistsDeviationMealAssociation() {
        var patient = createPatient("deviation-meal@example.com");
        var log = new DailyDietLog(patient, LocalDate.of(2026, 6, 10));
        log.setAdherenceLevel(DietAdherenceLevel.MOSTLY);
        log.setAppetiteLevel(AppetiteLevel.NORMAL);
        var meal = new DailyDietLogMeal(
                MealType.LUNCH,
                "Salmon",
                "Meal notes",
                0);
        log.addMeal(meal);
        var deviation = new DailyDietLogDeviation(
                DietDeviationCategory.DINING_OUT,
                DietDeviationSeverity.MINOR,
                "Restaurant",
                0);
        deviation.setMeal(meal);
        log.addDeviation(deviation);

        var saved = dailyDietLogs.saveAndFlush(log);
        entityManager.clear();

        var reloaded = dailyDietLogs.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getDeviations()).singleElement()
                .satisfies(row -> assertThat(row.getMeal().getId())
                        .isEqualTo(reloaded.getMeals().getFirst().getId()));
    }

    @Test
    void photoReferenceStoresUploadLifecycleMetadata() {
        var patient = createPatient("photo-lifecycle@example.com");
        var uploader = patient.getUser();
        var log = new DailyDietLog(patient, LocalDate.of(2026, 6, 10));
        log.setAdherenceLevel(DietAdherenceLevel.MOSTLY);
        log.setAppetiteLevel(AppetiteLevel.NORMAL);
        var sha256 = "0f4e2a" + "0".repeat(58);

        var photo = DailyDietLogPhotoReference.pending(
                patient,
                uploader,
                "plate.jpg",
                "image/jpeg",
                1234L,
                sha256,
                "diet-log-photos/10/2026/06/14/file.jpg");
        photo.attachTo(log, "Lunch plate", 0);
        log.addPhotoReference(photo);

        entityManager.persist(log);
        entityManager.flush();
        entityManager.clear();

        var saved = dailyDietLogs.findByPatientProfileIdAndLogDate(patient.getId(), LocalDate.of(2026, 6, 10))
                .orElseThrow();

        assertThat(saved.getPhotoReferences()).singleElement()
                .satisfies(savedPhoto -> {
                    assertThat(savedPhoto.getPatientProfile().getId()).isEqualTo(patient.getId());
                    assertThat(savedPhoto.getUploadedByUser().getId()).isEqualTo(uploader.getId());
                    assertThat(savedPhoto.getStatus()).isEqualTo(DietLogPhotoStatus.ATTACHED);
                    assertThat(savedPhoto.getOriginalFilename()).isEqualTo("plate.jpg");
                    assertThat(savedPhoto.getContentType()).isEqualTo("image/jpeg");
                    assertThat(savedPhoto.getSizeBytes()).isEqualTo(1234L);
                    assertThat(savedPhoto.getSha256()).isEqualTo(sha256);
                    assertThat(savedPhoto.getStorageKey()).isEqualTo("diet-log-photos/10/2026/06/14/file.jpg");
                    assertThat(savedPhoto.getCaption()).isEqualTo("Lunch plate");
                    assertThat(savedPhoto.getAttachedAt()).isNotNull();
                    assertThat(savedPhoto.getRemovedAt()).isNull();
                });
    }

    @Test
    void pendingPhotoReferenceCanExistWithoutDailyDietLog() {
        var patient = createPatient("pending-photo@example.com");
        var uploader = patient.getUser();
        var sha256 = "1".repeat(64);
        var photo = DailyDietLogPhotoReference.pending(
                patient,
                uploader,
                "snack.png",
                "image/png",
                2048L,
                sha256,
                "diet-log-photos/pending/snack.png");

        entityManager.persist(photo);
        entityManager.flush();
        var photoId = photo.getId();
        entityManager.clear();

        var saved = entityManager.find(DailyDietLogPhotoReference.class, photoId);

        assertThat(saved.getStatus()).isEqualTo(DietLogPhotoStatus.PENDING);
        assertThat(saved.getPatientProfile().getId()).isEqualTo(patient.getId());
        assertThat(saved.getUploadedByUser().getId()).isEqualTo(uploader.getId());
        assertThat(saved.getStorageKey()).isEqualTo("diet-log-photos/pending/snack.png");
        assertThat(saved.getSha256()).isEqualTo(sha256);
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getDailyDietLog()).isNull();
    }

    @Test
    void attachToRejectsDailyLogForDifferentPatient() {
        var patient = createPatient("photo-owner@example.com");
        var otherPatient = createPatient("other-photo-owner@example.com");
        var photo = DailyDietLogPhotoReference.pending(
                patient,
                patient.getUser(),
                "plate.jpg",
                "image/jpeg",
                1234L,
                "2".repeat(64),
                "diet-log-photos/pending/plate.jpg");
        var otherLog = new DailyDietLog(otherPatient, LocalDate.of(2026, 6, 10));

        assertThatThrownBy(() -> photo.attachTo(otherLog, "Wrong patient", 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void pendingPhotoReferenceWithRemovalAuditIsRejected() {
        var patient = createPatient("pending-removed-audit@example.com");
        var photo = DailyDietLogPhotoReference.pending(
                patient,
                patient.getUser(),
                "pending.jpg",
                "image/jpeg",
                1234L,
                "4".repeat(64),
                "diet-log-photos/pending/invalid-pending.jpg");
        photo.setRemovedAt(Instant.parse("2026-06-10T12:00:00Z"));
        photo.setRemovedByUser(patient.getUser());

        assertThatThrownBy(() -> {
            entityManager.persist(photo);
            entityManager.flush();
        })
                .isInstanceOf(Exception.class)
                .hasMessageContaining("chk_daily_diet_log_photo_references_attached_state");
    }

    @Test
    void attachedPhotoReferenceWithRemovalAuditIsRejected() {
        var patient = createPatient("attached-removed-audit@example.com");
        var log = new DailyDietLog(patient, LocalDate.of(2026, 6, 10));
        var photo = DailyDietLogPhotoReference.pending(
                patient,
                patient.getUser(),
                "attached.jpg",
                "image/jpeg",
                1234L,
                "5".repeat(64),
                "diet-log-photos/attached/invalid-attached.jpg");
        photo.attachTo(log, "Attached", 0);
        photo.setRemovedAt(Instant.parse("2026-06-10T12:00:00Z"));
        photo.setRemovedByUser(patient.getUser());
        log.addPhotoReference(photo);

        assertThatThrownBy(() -> dailyDietLogs.saveAndFlush(log))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_daily_diet_log_photo_references_attached_state");
    }

    @Test
    void removedPhotoReferenceWithoutAttachedAtIsRejected() {
        var patient = createPatient("removed-without-attached-at@example.com");
        var log = new DailyDietLog(patient, LocalDate.of(2026, 6, 10));
        var photo = DailyDietLogPhotoReference.pending(
                patient,
                patient.getUser(),
                "removed.jpg",
                "image/jpeg",
                1234L,
                "6".repeat(64),
                "diet-log-photos/removed/invalid-removed.jpg");
        photo.attachTo(log, "Removed", 0);
        photo.setStatus(DietLogPhotoStatus.REMOVED);
        photo.setRemovedAt(Instant.parse("2026-06-10T12:00:00Z"));
        photo.setRemovedByUser(patient.getUser());
        log.addPhotoReference(photo);
        photo.setAttachedAt(null);

        assertThatThrownBy(() -> dailyDietLogs.saveAndFlush(log))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_daily_diet_log_photo_references_attached_state");
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

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void measurementDailyLogMustBelongToSamePatient() {
        var logPatient = createPatient("measurement-log-patient@example.com");
        var measurementPatient = createPatient("measurement-owner@example.com");
        var log = dailyDietLogs.saveAndFlush(new DailyDietLog(logPatient, LocalDate.of(2026, 6, 10)));

        assertThatThrownBy(() -> measurementEntries.saveAndFlush(new DailyMeasurementEntry(
                measurementPatient,
                log,
                MeasurementType.GLUCOSE,
                new BigDecimal("5.8"),
                MeasurementUnit.MMOL_L,
                Instant.parse("2026-06-10T07:00:00Z"),
                MeasurementContext.FASTING,
                null)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void photoReferenceMealMustBelongToSameDailyLog() {
        var patient = createPatient("photo-meal-owner@example.com");
        var logWithMeal = new DailyDietLog(patient, LocalDate.of(2026, 6, 10));
        var meal = new DailyDietLogMeal(MealType.BREAKFAST, "Eggs", null, 0);
        logWithMeal.addMeal(meal);
        dailyDietLogs.saveAndFlush(logWithMeal);

        var otherLog = new DailyDietLog(patient, LocalDate.of(2026, 6, 11));
        var photoReference = new DailyDietLogPhotoReference(null, null, 1024L, null, "Wrong meal link", 0);
        photoReference.setMeal(meal);
        otherLog.addPhotoReference(photoReference);

        assertThatThrownBy(() -> dailyDietLogs.saveAndFlush(otherLog))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void deviationMealMustBelongToSameDailyLog() {
        var patient = createPatient("deviation-meal-owner@example.com");
        var logWithMeal = new DailyDietLog(patient, LocalDate.of(2026, 6, 10));
        var meal = new DailyDietLogMeal(MealType.LUNCH, "Salmon", null, 0);
        logWithMeal.addMeal(meal);
        dailyDietLogs.saveAndFlush(logWithMeal);

        var otherLog = new DailyDietLog(patient, LocalDate.of(2026, 6, 11));
        var deviation = new DailyDietLogDeviation(
                DietDeviationCategory.DINING_OUT,
                DietDeviationSeverity.MINOR,
                "Wrong meal link",
                0);
        deviation.setMeal(meal);
        otherLog.addDeviation(deviation);

        assertThatThrownBy(() -> dailyDietLogs.saveAndFlush(otherLog))
                .isInstanceOf(DataIntegrityViolationException.class);
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
