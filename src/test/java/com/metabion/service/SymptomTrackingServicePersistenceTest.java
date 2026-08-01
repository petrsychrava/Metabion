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
import com.metabion.service.redflag.RedFlagEvaluationService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

@DataJpaTest(properties = {"spring.jpa.hibernate.ddl-auto=validate"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import({SymptomTrackingService.class, SymptomQuestionnaireAssembler.class})
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

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    SymptomTrackingService service;

    @MockitoBean
    AccessControlService accessControl;

    @MockitoBean
    RedFlagEvaluationService redFlags;

    PatientProfile patient;
    SymptomQuestionnaireVersion activeVersion;
    String email;

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @BeforeEach
    void setUp() {
        email = "symptom-service-patient-" + System.nanoTime() + "@example.com";
        patient = patientProfiles.saveAndFlush(new PatientProfile(patientUser(email)));
        activeVersion = new TransactionTemplate(transactionManager).execute(status -> {
            var version = versions.findActiveByQuestionnaireStableKey("ibd-symptom-check-in").orElseThrow();
            version.getQuestions().forEach(question -> question.getOptions().size());
            return version;
        });
    }

    @Test
    void sameDaySaveReplacesCheckInAnswersAndFlareStateWithRealRepositories() {
        var auth = new TestingAuthenticationToken(email, "password");
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

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void redFlagEvaluationFailureRollsBackCheckInAndAnswers() {
        var auth = new TestingAuthenticationToken(email, "password");
        auth.setAuthenticated(true);
        var date = LocalDate.of(2026, 6, 26);
        doThrow(new IllegalStateException("Red-flag evaluation failed"))
                .when(redFlags).evaluateSymptom(any());

        assertThatThrownBy(() -> service.saveForCurrentPatient(
                auth, completeRequest(date, FlareState.NO_FLARE, "none")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Red-flag evaluation failed");

        assertThat(checkIns.findByPatientProfileIdAndCheckInDate(patient.getId(), date)).isEmpty();
        assertThat(jdbcTemplate.queryForObject(
                """
                select count(*)
                from symptom_check_in_answers answer
                join symptom_check_ins check_in on check_in.id = answer.check_in_id
                where check_in.patient_profile_id = ? and check_in.check_in_date = ?
                """,
                Long.class,
                patient.getId(),
                date)).isZero();
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
