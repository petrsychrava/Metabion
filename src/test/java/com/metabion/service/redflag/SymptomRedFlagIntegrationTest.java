package com.metabion.service.redflag;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metabion.config.TimeConfig;
import com.metabion.domain.FlareState;
import com.metabion.domain.PatientProfile;
import com.metabion.domain.RedFlagEvaluationRun;
import com.metabion.domain.RedFlagSeverity;
import com.metabion.domain.RedFlagTriggerEvent;
import com.metabion.domain.RoleName;
import com.metabion.domain.SymptomQuestion;
import com.metabion.domain.SymptomQuestionnaireVersion;
import com.metabion.domain.User;
import com.metabion.dto.SymptomCheckInRequest;
import com.metabion.repository.PatientProfileRepository;
import com.metabion.repository.RedFlagEvaluationRunRepository;
import com.metabion.repository.SymptomQuestionnaireVersionRepository;
import com.metabion.repository.UserRepository;
import com.metabion.service.AccessControlService;
import com.metabion.service.SymptomQuestionnaireAssembler;
import com.metabion.service.SymptomTrackingService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import({
        SymptomTrackingService.class,
        SymptomQuestionnaireAssembler.class,
        RedFlagEvaluationService.class,
        RedFlagRuleCatalog.class,
        RedFlagFactResolver.class,
        RedFlagFactRegistry.class,
        RedFlagSnapshotSerializer.class,
        TimeConfig.class
})
class SymptomRedFlagIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired SymptomTrackingService symptoms;
    @Autowired UserRepository users;
    @Autowired PatientProfileRepository patientProfiles;
    @Autowired SymptomQuestionnaireVersionRepository versions;
    @Autowired RedFlagEvaluationRunRepository runs;
    @Autowired EntityManager entityManager;

    @MockitoBean AccessControlService accessControl;

    PatientProfile patient;
    SymptomQuestionnaireVersion activeVersion;
    TestingAuthenticationToken authentication;

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @BeforeEach
    void setUp() {
        var email = "symptom-red-flag-" + System.nanoTime() + "@example.com";
        patient = patientProfiles.saveAndFlush(new PatientProfile(patientUser(email)));
        activeVersion = versions.findActiveByQuestionnaireStableKey("ibd-symptom-check-in").orElseThrow();
        authentication = new TestingAuthenticationToken(email, "password");
        authentication.setAuthenticated(true);
    }

    @ParameterizedTest
    @CsvSource({
            "severe, none",
            "none, significant"
    })
    void severePainAndSignificantBloodIndividuallyProduceEmergency(String pain, String blood) {
        var response = save(LocalDate.of(2026, 7, 20), FlareState.NO_FLARE, "3", pain, blood, "well");

        var run = runForSource(response.id());
        assertThat(run.getOverallSeverity()).isEqualTo(RedFlagSeverity.EMERGENCY);
        assertThat(ruleKeys(run)).containsExactly(pain.equals("severe")
                ? "SYM_SEVERE_ABDOMINAL_PAIN"
                : "SYM_SIGNIFICANT_BLEEDING");
    }

    @Test
    void activeFlareProducesUrgentReview() {
        var response = save(LocalDate.of(2026, 7, 20), FlareState.ACTIVE_FLARE,
                "3", "none", "none", "well");

        var run = runForSource(response.id());
        assertThat(run.getOverallSeverity()).isEqualTo(RedFlagSeverity.URGENT_REVIEW);
        assertThat(ruleKeys(run)).containsExactly("SYM_ACTIVE_FLARE");
    }

    @Test
    void stoolNineIsUrgentWhileEightDoesNotMatchHighFrequency() {
        var eight = save(LocalDate.of(2026, 7, 19), FlareState.NO_FLARE,
                "8", "none", "none", "well");
        var nine = save(LocalDate.of(2026, 7, 20), FlareState.NO_FLARE,
                "9", "none", "none", "well");

        assertThat(ruleKeys(runForSource(eight.id()))).doesNotContain("SYM_HIGH_STOOL_FREQUENCY");
        var nineRun = runForSource(nine.id());
        assertThat(nineRun.getOverallSeverity()).isEqualTo(RedFlagSeverity.URGENT_REVIEW);
        assertThat(ruleKeys(nineRun)).containsExactly("SYM_HIGH_STOOL_FREQUENCY");
    }

    @ParameterizedTest
    @CsvSource({
            "visible, none, well, G1",
            "none, moderate, well, G2",
            "none, none, very-unwell, G3"
    })
    void stoolSixCombinedWithEachSevereActivityAlternativeIsUrgent(
            String blood, String pain, String wellbeing, String selectedGroup) {
        var response = save(LocalDate.of(2026, 7, 20), FlareState.NO_FLARE,
                "6", pain, blood, wellbeing);

        var run = runForSource(response.id());
        var event = event(run, "SYM_COMBINED_SEVERE_ACTIVITY");
        assertThat(run.getOverallSeverity()).isEqualTo(RedFlagSeverity.URGENT_REVIEW);
        assertThat(event.getSeverity()).isEqualTo(RedFlagSeverity.URGENT_REVIEW);
        assertThat(event.getMatchedGroup().getStableKey()).isEqualTo(selectedGroup);
    }

    @ParameterizedTest
    @CsvSource({
            "SUSPECTED_FLARE, none, none, well, SYM_SUSPECTED_FLARE",
            "NO_FLARE, none, visible, well, SYM_MODERATE_DETERIORATION",
            "NO_FLARE, moderate, none, well, SYM_MODERATE_DETERIORATION",
            "NO_FLARE, none, none, very-unwell, SYM_MODERATE_DETERIORATION"
    })
    void suspectedFlareAndEachChoiceBasedModerateDeteriorationAlternativeAreRoutine(
            FlareState flareState, String pain, String blood, String wellbeing, String ruleKey) {
        var response = save(LocalDate.of(2026, 7, 20), flareState,
                "3", pain, blood, wellbeing);

        var run = runForSource(response.id());
        assertThat(run.getOverallSeverity()).isEqualTo(RedFlagSeverity.ROUTINE_REVIEW);
        assertThat(ruleKeys(run)).containsExactly(ruleKey);
    }

    @ParameterizedTest
    @CsvSource({
            "3, false",
            "4, true",
            "5, true",
            "6, false"
    })
    void moderateDeteriorationStoolRangeMatchesOnlyFourAndFive(String stoolFrequency, boolean matches) {
        var response = save(LocalDate.of(2026, 7, 20), FlareState.NO_FLARE,
                stoolFrequency, "none", "none", "well");

        var run = runForSource(response.id());
        assertThat(ruleKeys(run).contains("SYM_MODERATE_DETERIORATION"))
                .isEqualTo(matches);
        assertThat(run.getOverallSeverity()).isEqualTo(matches ? RedFlagSeverity.ROUTINE_REVIEW : null);
    }

    @Test
    void multipleMatchesPersistMultipleEventsAndUseTheHighestRunSeverity() {
        var response = save(LocalDate.of(2026, 7, 20), FlareState.ACTIVE_FLARE,
                "9", "severe", "significant", "very-unwell");

        var run = runForSource(response.id());
        assertThat(run.getOverallSeverity()).isEqualTo(RedFlagSeverity.EMERGENCY);
        assertThat(ruleKeys(run)).containsExactlyInAnyOrder(
                "SYM_SEVERE_ABDOMINAL_PAIN",
                "SYM_SIGNIFICANT_BLEEDING",
                "SYM_ACTIVE_FLARE",
                "SYM_HIGH_STOOL_FREQUENCY",
                "SYM_COMBINED_SEVERE_ACTIVITY",
                "SYM_MODERATE_DETERIORATION");
        assertThat(run.getEvents()).hasSize(6);
    }

    @Test
    void snapshotsContainOnlyFactsFromTheSelectedMatchedGroup() throws JsonProcessingException {
        var response = save(LocalDate.of(2026, 7, 20), FlareState.NO_FLARE,
                "6", "moderate", "visible", "very-unwell");

        var run = runForSource(response.id());
        var combined = event(run, "SYM_COMBINED_SEVERE_ACTIVITY");
        assertThat(combined.getMatchedGroup().getStableKey()).isEqualTo("G1");
        assertThat(snapshotFactKeys(combined)).containsExactlyInAnyOrder(
                "symptom.stool_frequency", "symptom.blood_in_stool");

        var moderate = event(run, "SYM_MODERATE_DETERIORATION");
        assertThat(moderate.getMatchedGroup().getStableKey()).isEqualTo("G2");
        assertThat(snapshotFactKeys(moderate)).containsExactly("symptom.blood_in_stool");
    }

    @Test
    void sameDayUpdateSupersedesTheRunAndPreservesOldEvents() {
        var date = LocalDate.of(2026, 7, 20);
        var created = save(date, FlareState.NO_FLARE, "3", "severe", "none", "well");
        entityManager.flush();
        entityManager.clear();
        var originalRun = runForSource(created.id());
        var originalEvent = event(originalRun, "SYM_SEVERE_ABDOMINAL_PAIN");
        var originalRunId = originalRun.getId();
        var originalEventId = originalEvent.getId();
        var originalSnapshot = originalEvent.getMatchedInputs();

        var updated = save(date, FlareState.NO_FLARE, "3", "none", "none", "well");
        entityManager.flush();
        entityManager.clear();

        var history = runs.findByPatientProfileIdOrderByEvaluatedAtDescIdDesc(patient.getId());
        assertThat(updated.id()).isEqualTo(created.id());
        assertThat(history).hasSize(2);
        var current = history.stream().filter(RedFlagEvaluationRun::isCurrent).findFirst().orElseThrow();
        var superseded = history.stream().filter(run -> run.getId().equals(originalRunId)).findFirst().orElseThrow();
        assertThat(current.getSourceId()).isEqualTo(created.id());
        assertThat(current.getOverallSeverity()).isNull();
        assertThat(current.getEvents()).isEmpty();
        assertThat(superseded.isCurrent()).isFalse();
        assertThat(superseded.getSupersededByRun().getId()).isEqualTo(current.getId());
        assertThat(superseded.getEvents()).singleElement().satisfies(event -> {
            assertThat(event.getId()).isEqualTo(originalEventId);
            assertThat(event.getMatchedInputs()).isEqualTo(originalSnapshot);
            assertThat(event.getRuleVersion().getRule().getStableKey())
                    .isEqualTo("SYM_SEVERE_ABDOMINAL_PAIN");
        });
    }

    private com.metabion.dto.SymptomCheckInResponse save(
            LocalDate date, FlareState flareState, String stoolFrequency,
            String pain, String blood, String wellbeing) {
        return symptoms.saveForCurrentPatient(authentication, new SymptomCheckInRequest(
                date,
                activeVersion.getId(),
                flareState,
                List.of(
                        numericAnswer("stool-frequency", stoolFrequency),
                        optionAnswer("abdominal-pain", pain),
                        optionAnswer("blood-in-stool", blood),
                        optionAnswer("urgency", "none"),
                        optionAnswer("general-wellbeing", wellbeing)),
                null));
    }

    private SymptomCheckInRequest.AnswerRequest numericAnswer(String questionKey, String value) {
        return new SymptomCheckInRequest.AnswerRequest(
                question(questionKey).getId(), null, null, new BigDecimal(value));
    }

    private SymptomCheckInRequest.AnswerRequest optionAnswer(String questionKey, String optionKey) {
        var question = question(questionKey);
        var option = question.getOptions().stream()
                .filter(candidate -> candidate.getStableKey().equals(optionKey))
                .findFirst()
                .orElseThrow();
        return new SymptomCheckInRequest.AnswerRequest(question.getId(), option.getId(), null, null);
    }

    private SymptomQuestion question(String stableKey) {
        return activeVersion.getQuestions().stream()
                .filter(question -> question.getStableKey().equals(stableKey))
                .findFirst()
                .orElseThrow();
    }

    private RedFlagEvaluationRun runForSource(Long sourceId) {
        entityManager.flush();
        entityManager.clear();
        return runs.findByPatientProfileIdAndCurrentTrueOrderByEvaluatedAtDescIdDesc(patient.getId()).stream()
                .filter(run -> run.getSourceId().equals(sourceId))
                .findFirst()
                .orElseThrow();
    }

    private List<String> ruleKeys(RedFlagEvaluationRun run) {
        return run.getEvents().stream()
                .map(event -> event.getRuleVersion().getRule().getStableKey())
                .toList();
    }

    private RedFlagTriggerEvent event(RedFlagEvaluationRun run, String ruleKey) {
        return run.getEvents().stream()
                .filter(event -> event.getRuleVersion().getRule().getStableKey().equals(ruleKey))
                .findFirst()
                .orElseThrow();
    }

    private List<String> snapshotFactKeys(RedFlagTriggerEvent event) throws JsonProcessingException {
        var facts = new ObjectMapper().readTree(event.getMatchedInputs()).path("facts");
        return StreamSupport.stream(facts.spliterator(), false)
                .map(fact -> fact.path("factKey").asText())
                .toList();
    }

    private User patientUser(String email) {
        var user = new User(email, "{noop}password");
        user.setEnabled(true);
        user.addRole(RoleName.PATIENT);
        return users.saveAndFlush(user);
    }
}
