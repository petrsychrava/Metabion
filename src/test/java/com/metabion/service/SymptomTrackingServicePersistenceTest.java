package com.metabion.service;

import com.metabion.domain.FlareState;
import com.metabion.domain.PatientProfile;
import com.metabion.domain.RoleName;
import com.metabion.domain.SymptomQuestion;
import com.metabion.domain.SymptomQuestionnaireVersion;
import com.metabion.domain.User;
import com.metabion.dto.SymptomCheckInRequest;
import com.metabion.repository.PatientProfileRepository;
import com.metabion.repository.SymptomCheckInRepository;
import com.metabion.repository.SymptomQuestionnaireVersionRepository;
import com.metabion.repository.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@DataJpaTest(properties = {"spring.jpa.hibernate.ddl-auto=validate"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class SymptomTrackingServicePersistenceTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    UserRepository users;

    @Autowired
    PatientProfileRepository patientProfiles;

    @Autowired
    SymptomQuestionnaireVersionRepository versions;

    @Autowired
    SymptomCheckInRepository checkIns;

    @Autowired
    EntityManager entityManager;

    SymptomTrackingService service;
    PatientProfile patient;
    SymptomQuestionnaireVersion activeVersion;

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @BeforeEach
    void setUp() {
        service = new SymptomTrackingService(
                users,
                patientProfiles,
                versions,
                checkIns,
                mock(AccessControlService.class),
                new SymptomQuestionnaireAssembler());
        patient = patientProfiles.saveAndFlush(new PatientProfile(patientUser("symptom-service-patient@example.com")));
        activeVersion = versions.findActiveByQuestionnaireStableKey("ibd-symptom-check-in").orElseThrow();
    }

    @Test
    void sameDaySaveReplacesCheckInAnswersAndFlareStateWithRealRepositories() {
        var auth = new TestingAuthenticationToken("symptom-service-patient@example.com", "password");
        auth.setAuthenticated(true);
        var date = LocalDate.of(2026, 6, 26);

        var first = service.saveForCurrentPatient(auth, completeRequest(date, FlareState.NO_FLARE, "none"));
        entityManager.flush();
        entityManager.clear();

        var second = service.saveForCurrentPatient(auth, completeRequest(date, FlareState.ACTIVE_FLARE, "severe"));
        entityManager.flush();
        entityManager.clear();

        var checkInsForDate = checkIns.findByPatientProfileIdAndCheckInDateBetweenOrderByCheckInDateDesc(
                patient.getId(), date, date);
        assertThat(checkInsForDate).hasSize(1);
        var loaded = checkInsForDate.getFirst();
        assertThat(loaded.getId()).isEqualTo(first.id()).isEqualTo(second.id());
        assertThat(loaded.getCheckInDate()).isEqualTo(date);
        assertThat(loaded.getFlareState()).isEqualTo(FlareState.ACTIVE_FLARE);
        assertThat(loaded.getAnswers()).hasSize(5);
        assertThat(loaded.getAnswers())
                .extracting(answer -> answer.getQuestion().getStableKey())
                .containsExactlyInAnyOrder(
                        "stool-frequency",
                        "abdominal-pain",
                        "blood-in-stool",
                        "urgency",
                        "general-wellbeing");
    }

    private SymptomCheckInRequest completeRequest(LocalDate date, FlareState flareState, String painLevel) {
        return new SymptomCheckInRequest(
                date,
                activeVersion.getId(),
                flareState,
                List.of(
                        answer("stool-frequency", new BigDecimal("3")),
                        answer("abdominal-pain", optionId("abdominal-pain", painLevel)),
                        answer("blood-in-stool", optionId("blood-in-stool", "none")),
                        answer("urgency", optionId("urgency", "mild")),
                        answer("general-wellbeing", optionId("general-wellbeing", "well"))),
                null);
    }

    private SymptomCheckInRequest.AnswerRequest answer(String questionStableKey, BigDecimal answerNumeric) {
        return new SymptomCheckInRequest.AnswerRequest(
                question(questionStableKey).getId(),
                null,
                null,
                answerNumeric);
    }

    private SymptomCheckInRequest.AnswerRequest answer(String questionStableKey, Long optionId) {
        return new SymptomCheckInRequest.AnswerRequest(
                question(questionStableKey).getId(),
                optionId,
                null,
                null);
    }

    private Long optionId(String questionStableKey, String optionStableKey) {
        return question(questionStableKey).getOptions().stream()
                .filter(option -> option.getStableKey().equals(optionStableKey))
                .findFirst()
                .orElseThrow()
                .getId();
    }

    private SymptomQuestion question(String stableKey) {
        return activeVersion.getQuestions().stream()
                .filter(question -> question.getStableKey().equals(stableKey))
                .findFirst()
                .orElseThrow();
    }

    private User patientUser(String email) {
        var user = new User(email, "{noop}password");
        user.setEnabled(true);
        user.addRole(RoleName.PATIENT);
        return users.saveAndFlush(user);
    }
}
