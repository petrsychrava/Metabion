package com.metabion.service;

import com.metabion.domain.AppetiteLevel;
import com.metabion.domain.DietAdherenceLevel;
import com.metabion.domain.FlareState;
import com.metabion.domain.MealType;
import com.metabion.domain.MeasurementContext;
import com.metabion.domain.MeasurementType;
import com.metabion.domain.MeasurementUnit;
import com.metabion.domain.PatientProfile;
import com.metabion.domain.RoleName;
import com.metabion.domain.User;
import com.metabion.dto.DailyCheckInForm;
import com.metabion.dto.DailyDietLogRequest;
import com.metabion.dto.DailyMeasurementEntryRequest;
import com.metabion.dto.SymptomCheckInRequest;
import com.metabion.repository.DailyDietLogRepository;
import com.metabion.repository.DailyMeasurementEntryRepository;
import com.metabion.repository.PatientProfileRepository;
import com.metabion.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;

@DataJpaTest(properties = {"spring.jpa.hibernate.ddl-auto=validate"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import({
        DailyCheckInService.class,
        DietLogService.class,
        MeasurementWindowService.class,
        MeasurementValidator.class,
        DietLogRequestMapper.class,
        DietLogResponseAssembler.class,
        DateRangeValidator.class
})
class DailyCheckInServicePersistenceTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    DailyCheckInService service;

    @Autowired
    UserRepository users;

    @Autowired
    PatientProfileRepository patientProfiles;

    @Autowired
    DailyDietLogRepository dailyDietLogs;

    @Autowired
    DailyMeasurementEntryRepository measurements;

    @MockitoBean
    AccessControlService accessControl;

    @MockitoBean
    DietLogPhotoService dietLogPhotoService;

    @MockitoBean
    SymptomTrackingService symptomTrackingService;

    PatientProfile patient;

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @BeforeEach
    void setUp() {
        patient = patientProfiles.saveAndFlush(new PatientProfile(patientUser("daily-check-in-patient@example.com")));
        patient.setTimezone("UTC");
        patient = patientProfiles.saveAndFlush(patient);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void symptomFailureRollsBackDietLogAndMeasurements() {
        var date = LocalDate.of(2026, 6, 26);
        var symptomRequest = new SymptomCheckInRequest(
                date,
                10L,
                FlareState.NO_FLARE,
                List.of(new SymptomCheckInRequest.AnswerRequest(100L, null, null, new BigDecimal("3"))),
                "symptom note");
        var form = new DailyCheckInForm(dietLogRequest(date), symptomRequest);
        var auth = new TestingAuthenticationToken("daily-check-in-patient@example.com", "password");
        auth.setAuthenticated(true);
        doThrow(new ResponseStatusException(HttpStatus.BAD_REQUEST, "required symptom answers are missing"))
                .when(symptomTrackingService).saveForCurrentPatient(any(), eq(symptomRequest));

        assertThatThrownBy(() -> service.saveForCurrentPatient(auth, form))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("required symptom answers are missing");

        assertThat(dailyDietLogs.findByPatientProfileIdAndLogDate(patient.getId(), date)).isEmpty();
        assertThat(measurements.findByPatientProfileIdAndMeasuredAtGreaterThanEqualAndMeasuredAtLessThanOrderByMeasuredAtAsc(
                patient.getId(),
                Instant.parse("2026-06-26T00:00:00Z"),
                Instant.parse("2026-06-27T00:00:00Z"))).isEmpty();
    }

    private DailyDietLogRequest dietLogRequest(LocalDate date) {
        return new DailyDietLogRequest(
                date,
                DietAdherenceLevel.MOSTLY,
                AppetiteLevel.NORMAL,
                "Stable day",
                List.of(new DailyDietLogRequest.MealRequest(
                        MealType.LUNCH,
                        "Salmon",
                        "ok")),
                List.of(),
                List.of(),
                List.of(new DailyMeasurementEntryRequest(
                        MeasurementType.GLUCOSE,
                        new BigDecimal("5.8"),
                        MeasurementUnit.MMOL_L,
                        Instant.parse("2026-06-26T07:30:00Z"),
                        MeasurementContext.FASTING,
                        "morning")));
    }

    private User patientUser(String email) {
        var user = new User(email, "{noop}password");
        user.setEnabled(true);
        user.addRole(RoleName.PATIENT);
        return users.saveAndFlush(user);
    }
}
