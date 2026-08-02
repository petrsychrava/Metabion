package com.metabion.service.redflag;

import com.metabion.config.TimeConfig;
import com.metabion.domain.FlareState;
import com.metabion.domain.LabResult;
import com.metabion.domain.LabResultConfirmationStatus;
import com.metabion.domain.LabResultSet;
import com.metabion.domain.LabResultSource;
import com.metabion.domain.PatientProfile;
import com.metabion.domain.RedFlagEvaluationRun;
import com.metabion.domain.RedFlagSeverity;
import com.metabion.domain.RedFlagSourceOperation;
import com.metabion.domain.RedFlagSourceType;
import com.metabion.domain.RoleName;
import com.metabion.domain.Sex;
import com.metabion.domain.SymptomQuestion;
import com.metabion.domain.SymptomQuestionnaireVersion;
import com.metabion.domain.User;
import com.metabion.dto.LabResultRemovalRequest;
import com.metabion.dto.LabResultRequest;
import com.metabion.dto.LabResultSetRequest;
import com.metabion.dto.LabResultSetResponse;
import com.metabion.dto.SymptomCheckInRequest;
import com.metabion.repository.LabResultSetRepository;
import com.metabion.repository.LabTestDefinitionRepository;
import com.metabion.repository.PatientProfileRepository;
import com.metabion.repository.RedFlagEvaluationRunRepository;
import com.metabion.repository.SymptomQuestionnaireVersionRepository;
import com.metabion.repository.UserRepository;
import com.metabion.service.AccessControlService;
import com.metabion.service.DateRangeValidator;
import com.metabion.service.LabAuditService;
import com.metabion.service.LabCatalogService;
import com.metabion.service.LabResponseAssembler;
import com.metabion.service.LabResultService;
import com.metabion.service.LabUnitConversionService;
import com.metabion.service.SymptomQuestionnaireAssembler;
import com.metabion.service.SymptomTrackingService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
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
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import({
        LabResultService.class,
        LabAuditService.class,
        LabResponseAssembler.class,
        LabCatalogService.class,
        LabUnitConversionService.class,
        DateRangeValidator.class,
        SymptomTrackingService.class,
        SymptomQuestionnaireAssembler.class,
        RedFlagEvaluationService.class,
        RedFlagRuleCatalog.class,
        RedFlagFactResolver.class,
        RedFlagFactRegistry.class,
        PatientRedFlagResponseAssembler.class,
        RedFlagSnapshotSerializer.class,
        TimeConfig.class
})
class LabRedFlagIntegrationTest {

    private static final LocalDate COLLECTION_DATE = LocalDate.of(2026, 7, 20);

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired LabResultService labs;
    @Autowired SymptomTrackingService symptoms;
    @Autowired RedFlagEvaluationService redFlags;
    @Autowired UserRepository users;
    @Autowired PatientProfileRepository patientProfiles;
    @Autowired LabResultSetRepository resultSets;
    @Autowired LabTestDefinitionRepository definitions;
    @Autowired RedFlagEvaluationRunRepository runs;
    @Autowired SymptomQuestionnaireVersionRepository versions;
    @Autowired EntityManager entityManager;

    @MockitoBean AccessControlService accessControl;

    private PatientProfile patient;
    private User patientUser;
    private SymptomQuestionnaireVersion activeVersion;
    private TestingAuthenticationToken authentication;

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @BeforeEach
    void setUp() {
        var email = "lab-red-flag-" + System.nanoTime() + "@example.com";
        patientUser = new User(email, "{noop}password");
        patientUser.setEnabled(true);
        patientUser.addRole(RoleName.PATIENT);
        patientUser = users.saveAndFlush(patientUser);
        patient = patientProfiles.saveAndFlush(new PatientProfile(patientUser));
        activeVersion = versions.findActiveByQuestionnaireStableKey("ibd-symptom-check-in").orElseThrow();
        authentication = new TestingAuthenticationToken(email, "password");
        authentication.setAuthenticated(true);
    }

    @ParameterizedTest(name = "{0} {1} -> {3}")
    @MethodSource("canonicalBoundaries")
    void evaluatesExactCanonicalBoundaries(
            String code, String value, Sex sex, RedFlagSeverity expectedSeverity,
            List<String> expectedRuleKeys) {
        patient.setSex(sex);
        patientProfiles.saveAndFlush(patient);

        var run = evaluateCanonical(List.of(new LabValue(code, value)));

        assertThat(run.getOverallSeverity()).isEqualTo(expectedSeverity);
        assertThat(ruleKeys(run)).containsExactlyInAnyOrderElementsOf(expectedRuleKeys);
    }

    static Stream<Arguments> canonicalBoundaries() {
        return Stream.of(
                boundary("SODIUM", "120", null, RedFlagSeverity.EMERGENCY, "LAB_SODIUM_CRITICAL"),
                boundary("SODIUM", "120.01", null, null),
                boundary("SODIUM", "159.99", null, null),
                boundary("SODIUM", "160", null, RedFlagSeverity.EMERGENCY, "LAB_SODIUM_CRITICAL"),
                boundary("POTASSIUM", "2.50", null, RedFlagSeverity.EMERGENCY, "LAB_POTASSIUM_CRITICAL"),
                boundary("POTASSIUM", "2.51", null, null),
                boundary("POTASSIUM", "6.49", null, null),
                boundary("POTASSIUM", "6.50", null, RedFlagSeverity.EMERGENCY, "LAB_POTASSIUM_CRITICAL"),
                boundary("CRP", "45", null, null),
                boundary("CRP", "45.01", null, RedFlagSeverity.ROUTINE_REVIEW, "LAB_CRP_ELEVATED"),
                boundary("CRP", "99.99", null, RedFlagSeverity.ROUTINE_REVIEW, "LAB_CRP_ELEVATED"),
                boundary("CRP", "100", null, RedFlagSeverity.URGENT_REVIEW, "LAB_CRP_HIGH"),
                boundary("CRP", "299.99", null, RedFlagSeverity.URGENT_REVIEW, "LAB_CRP_HIGH"),
                boundary("CRP", "300", null, RedFlagSeverity.EMERGENCY, "LAB_CRP_CRITICAL"),
                boundary("MAGNESIUM", "0.40", null, RedFlagSeverity.URGENT_REVIEW,
                        "LAB_MAGNESIUM_CRITICAL_LOW"),
                boundary("MAGNESIUM", "0.41", null, null),
                boundary("UREA", "29.99", null, null),
                boundary("UREA", "30", null, RedFlagSeverity.URGENT_REVIEW, "LAB_UREA_CRITICAL_HIGH"),
                boundary("CREATININE", "353.99", null, null),
                boundary("CREATININE", "354", null, RedFlagSeverity.URGENT_REVIEW,
                        "LAB_CREATININE_CRITICAL_HIGH"),
                boundary("ALT", "499.99", null, null),
                boundary("AST", "499.99", null, null),
                boundary("ALT", "500", null, RedFlagSeverity.URGENT_REVIEW,
                        "LAB_TRANSAMINASE_CRITICAL_HIGH"),
                boundary("AST", "500", null, RedFlagSeverity.URGENT_REVIEW,
                        "LAB_TRANSAMINASE_CRITICAL_HIGH"),
                boundary("ALBUMIN", "10", null, RedFlagSeverity.URGENT_REVIEW,
                        "LAB_ALBUMIN_CRITICAL_LOW"),
                boundary("ALBUMIN", "10.01", null, RedFlagSeverity.ROUTINE_REVIEW, "LAB_ALBUMIN_LOW"),
                boundary("ALBUMIN", "29.99", null, RedFlagSeverity.ROUTINE_REVIEW, "LAB_ALBUMIN_LOW"),
                boundary("ALBUMIN", "30", null, null),
                boundary("FECAL_CALPROTECTIN", "99.99", null, null),
                boundary("FECAL_CALPROTECTIN", "100", null, RedFlagSeverity.ROUTINE_REVIEW,
                        "LAB_CALPROTECTIN_BORDERLINE"),
                boundary("FECAL_CALPROTECTIN", "250", null, RedFlagSeverity.ROUTINE_REVIEW,
                        "LAB_CALPROTECTIN_BORDERLINE"),
                boundary("FECAL_CALPROTECTIN", "250.01", null, RedFlagSeverity.URGENT_REVIEW,
                        "LAB_CALPROTECTIN_HIGH"));
    }

    @ParameterizedTest
    @MethodSource("criticalHaemoglobinProfiles")
    void haemoglobinSeventyIsUrgentForEveryProfileSex(Sex sex) {
        patient.setSex(sex);
        patientProfiles.saveAndFlush(patient);

        var run = evaluateCanonical(List.of(new LabValue("HEMOGLOBIN", "70")));

        assertThat(run.getOverallSeverity()).isEqualTo(RedFlagSeverity.URGENT_REVIEW);
        assertThat(ruleKeys(run)).containsExactly("LAB_HEMOGLOBIN_CRITICAL_LOW");
    }

    static Stream<Arguments> criticalHaemoglobinProfiles() {
        return Stream.of(arguments((Sex) null), arguments(Sex.FEMALE), arguments(Sex.MALE),
                arguments(Sex.INTERSEX), arguments(Sex.PREFER_NOT_TO_SAY));
    }

    @ParameterizedTest(name = "{0} {1} -> {2}")
    @MethodSource("sexSpecificHaemoglobinBoundaries")
    void appliesOnlyBinarySexSpecificRoutineHaemoglobinRules(
            Sex sex, String value, RedFlagSeverity expectedSeverity, List<String> expectedRuleKeys) {
        patient.setSex(sex);
        patientProfiles.saveAndFlush(patient);

        var run = evaluateCanonical(List.of(new LabValue("HEMOGLOBIN", value)));

        assertThat(run.getOverallSeverity()).isEqualTo(expectedSeverity);
        assertThat(ruleKeys(run)).containsExactlyInAnyOrderElementsOf(expectedRuleKeys);
    }

    static Stream<Arguments> sexSpecificHaemoglobinBoundaries() {
        return Stream.of(
                arguments(Sex.MALE, "70.01", RedFlagSeverity.ROUTINE_REVIEW,
                        List.of("LAB_HEMOGLOBIN_LOW_MALE")),
                arguments(Sex.MALE, "130", RedFlagSeverity.ROUTINE_REVIEW,
                        List.of("LAB_HEMOGLOBIN_LOW_MALE")),
                arguments(Sex.MALE, "130.01", null, List.of()),
                arguments(Sex.FEMALE, "70.01", RedFlagSeverity.ROUTINE_REVIEW,
                        List.of("LAB_HEMOGLOBIN_LOW_FEMALE")),
                arguments(Sex.FEMALE, "120", RedFlagSeverity.ROUTINE_REVIEW,
                        List.of("LAB_HEMOGLOBIN_LOW_FEMALE")),
                arguments(Sex.FEMALE, "120.01", null, List.of()),
                arguments(null, "100", null, List.of()),
                arguments(Sex.INTERSEX, "100", null, List.of()),
                arguments(Sex.PREFER_NOT_TO_SAY, "100", null, List.of()));
    }

    @Test
    void evaluatesConvertedCrpCanonicalValueRatherThanReportedValue() {
        var saved = saveLab(COLLECTION_DATE,
                new LabResultRequest("CRP", new BigDecimal("10"), "mg/dL", null, null));

        assertThat(saved.results()).singleElement()
                .extracting(result -> result.canonicalValue().stripTrailingZeros().toPlainString())
                .isEqualTo("100");
        var run = currentLabRun(saved.id());
        assertThat(run.getOverallSeverity()).isEqualTo(RedFlagSeverity.URGENT_REVIEW);
        assertThat(ruleKeys(run)).containsExactly("LAB_CRP_HIGH");
    }

    @Test
    void referenceRangesAndNotesDoNotCreateMatches() {
        var response = labs.saveForCurrentPatient(authentication, new LabResultSetRequest(
                null, null, COLLECTION_DATE, "CRP 300 emergency",
                List.of(new LabResultRequest("CRP", new BigDecimal("1"), "mg/dL",
                        new BigDecimal("300"), new BigDecimal("400")))));

        var run = currentLabRun(response.id());
        assertThat(run.getOverallSeverity()).isNull();
        assertThat(run.getEvents()).isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("qualifyingSymptomContexts")
    void qualifyingSevenDaySymptomContextMakesElevatedCrpUrgentAndRetainsRoutineEvent(
            String label, FlareState flareState, String stool, String pain, String blood,
            String wellbeing, String expectedGroup) {
        saveSymptoms(COLLECTION_DATE.minusDays(7), flareState, stool, pain, blood, wellbeing);

        var lab = saveLab(COLLECTION_DATE, canonicalRequest("CRP", "45.01"));

        var run = currentLabRun(lab.id());
        assertThat(run.getOverallSeverity()).isEqualTo(RedFlagSeverity.URGENT_REVIEW);
        assertThat(ruleKeys(run)).containsExactlyInAnyOrder(
                "LAB_CRP_SYMPTOM_CONTEXT", "LAB_CRP_ELEVATED");
        assertThat(run.getEvents().stream()
                .filter(event -> event.getRuleVersion().getRule().getStableKey()
                        .equals("LAB_CRP_SYMPTOM_CONTEXT"))
                .findFirst().orElseThrow().getMatchedGroup().getStableKey()).isEqualTo(expectedGroup);
    }

    static Stream<Arguments> qualifyingSymptomContexts() {
        return Stream.of(
                arguments("active flare", FlareState.ACTIVE_FLARE, "3", "none", "none", "well", "G1"),
                arguments("stool above eight", FlareState.NO_FLARE, "9", "none", "none", "well", "G2"),
                arguments("stool six plus visible blood", FlareState.NO_FLARE, "6", "none", "visible", "well", "G3"),
                arguments("stool six plus moderate pain", FlareState.NO_FLARE, "6", "moderate", "none", "well", "G4"),
                arguments("stool six plus very unwell", FlareState.NO_FLARE, "6", "none", "none", "very-unwell", "G5"),
                arguments("severe pain", FlareState.NO_FLARE, "3", "severe", "none", "well", "G6"),
                arguments("significant bleeding", FlareState.NO_FLARE, "3", "none", "significant", "well", "G7"));
    }

    @Test
    void symptomContextEightDaysEarlierDoesNotQualify() {
        saveSymptoms(COLLECTION_DATE.minusDays(8), FlareState.ACTIVE_FLARE,
                "9", "severe", "significant", "very-unwell");

        var lab = saveLab(COLLECTION_DATE, canonicalRequest("CRP", "99.99"));

        var run = currentLabRun(lab.id());
        assertThat(run.getOverallSeverity()).isEqualTo(RedFlagSeverity.ROUTINE_REVIEW);
        assertThat(ruleKeys(run)).containsExactly("LAB_CRP_ELEVATED");
    }

    @Test
    void compoundContextCannotCombineAnswersFromDifferentCheckIns() {
        saveSymptoms(COLLECTION_DATE.minusDays(7), FlareState.NO_FLARE,
                "6", "none", "none", "well");
        saveSymptoms(COLLECTION_DATE.minusDays(6), FlareState.NO_FLARE,
                "3", "moderate", "visible", "very-unwell");

        var lab = saveLab(COLLECTION_DATE, canonicalRequest("CRP", "45.01"));

        var run = currentLabRun(lab.id());
        assertThat(run.getOverallSeverity()).isEqualTo(RedFlagSeverity.ROUTINE_REVIEW);
        assertThat(ruleKeys(run)).containsExactly("LAB_CRP_ELEVATED");
    }

    @Test
    void laterSymptomWriteDoesNotRetriggerOrSupersedeLabSourceRun() {
        var lab = saveLab(COLLECTION_DATE, canonicalRequest("CRP", "45.01"));
        var original = currentLabRun(lab.id());
        var originalId = original.getId();
        activeVersion = versions.findActiveByQuestionnaireStableKey("ibd-symptom-check-in").orElseThrow();

        saveSymptoms(COLLECTION_DATE.plusDays(1), FlareState.ACTIVE_FLARE,
                "9", "severe", "significant", "very-unwell");

        var labHistory = labHistory(lab.id());
        assertThat(labHistory).singleElement().satisfies(run -> {
            assertThat(run.getId()).isEqualTo(originalId);
            assertThat(run.isCurrent()).isTrue();
            assertThat(run.getSourceOperation()).isEqualTo(RedFlagSourceOperation.UPSERT);
        });
    }

    @Test
    void updateSupersedesPriorLabRunAndRemovalCreatesCurrentNoMatchHistory() {
        var created = saveLab(COLLECTION_DATE, canonicalRequest("CRP", "300"));
        var createdRun = currentLabRun(created.id());
        assertThat(createdRun.getOverallSeverity()).isEqualTo(RedFlagSeverity.EMERGENCY);
        assertThat(ruleKeys(createdRun)).containsExactly("LAB_CRP_CRITICAL");

        var updated = labs.updateForCurrentPatient(authentication, created.id(),
                new LabResultSetRequest(created.id(), created.version(), COLLECTION_DATE, null,
                        List.of(canonicalRequest("CRP", "45"))));
        var afterUpdate = labHistory(created.id());
        assertThat(afterUpdate).hasSize(2);
        var updateRun = afterUpdate.stream().filter(RedFlagEvaluationRun::isCurrent).findFirst().orElseThrow();
        var supersededCreate = afterUpdate.stream()
                .filter(run -> run.getId().equals(createdRun.getId())).findFirst().orElseThrow();
        assertThat(updateRun.getOverallSeverity()).isNull();
        assertThat(updateRun.getEvents()).isEmpty();
        assertThat(supersededCreate.getSupersededByRun().getId()).isEqualTo(updateRun.getId());

        labs.removeForCurrentPatient(authentication, created.id(),
                new LabResultRemovalRequest(created.id(), updated.version(), "duplicate"));

        var afterRemoval = labHistory(created.id());
        assertThat(afterRemoval).hasSize(3);
        var removal = afterRemoval.stream().filter(RedFlagEvaluationRun::isCurrent).findFirst().orElseThrow();
        assertThat(removal.getSourceOperation()).isEqualTo(RedFlagSourceOperation.REMOVE);
        assertThat(removal.getOverallSeverity()).isNull();
        assertThat(removal.getEvents()).isEmpty();
        assertThat(afterRemoval).filteredOn(run -> run.getSourceOperation() == RedFlagSourceOperation.UPSERT)
                .hasSize(2).allSatisfy(run -> assertThat(run.isCurrent()).isFalse());
    }

    private RedFlagEvaluationRun evaluateCanonical(List<LabValue> values) {
        var set = new LabResultSet(patient, COLLECTION_DATE, "not evaluated",
                LabResultSource.MANUAL, LabResultConfirmationStatus.CONFIRMED,
                patientUser, Instant.parse("2026-07-20T10:00:00Z"));
        set.replaceResults(values.stream().map(value -> {
            var definition = definitions.findByCodeAndActiveTrue(value.code()).orElseThrow();
            return new LabResult(set, definition, new BigDecimal(value.value()), definition.getCanonicalUnit(),
                    new BigDecimal(value.value()), definition.getCanonicalUnit(), null, null);
        }).toList(), Instant.parse("2026-07-20T10:00:00Z"));
        var saved = resultSets.saveAndFlush(set);
        redFlags.evaluateLab(saved);
        return currentLabRun(saved.getId());
    }

    private LabResultSetResponse saveLab(LocalDate date, LabResultRequest result) {
        return labs.saveForCurrentPatient(authentication,
                new LabResultSetRequest(null, null, date, null, List.of(result)));
    }

    private LabResultRequest canonicalRequest(String code, String value) {
        var definition = definitions.findByCodeAndActiveTrue(code).orElseThrow();
        return new LabResultRequest(code, new BigDecimal(value), definition.getCanonicalUnit(), null, null);
    }

    private void saveSymptoms(LocalDate date, FlareState flareState, String stool,
            String pain, String blood, String wellbeing) {
        symptoms.saveForCurrentPatient(authentication, new SymptomCheckInRequest(
                date, activeVersion.getId(), flareState,
                List.of(
                        numericAnswer("stool-frequency", stool),
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
                .findFirst().orElseThrow();
        return new SymptomCheckInRequest.AnswerRequest(question.getId(), option.getId(), null, null);
    }

    private SymptomQuestion question(String stableKey) {
        return activeVersion.getQuestions().stream()
                .filter(question -> question.getStableKey().equals(stableKey))
                .findFirst().orElseThrow();
    }

    private RedFlagEvaluationRun currentLabRun(Long sourceId) {
        return labHistory(sourceId).stream().filter(RedFlagEvaluationRun::isCurrent)
                .findFirst().orElseThrow();
    }

    private List<RedFlagEvaluationRun> labHistory(Long sourceId) {
        entityManager.flush();
        entityManager.clear();
        return runs.findByPatientProfileIdOrderByEvaluatedAtDescIdDesc(patient.getId()).stream()
                .filter(run -> run.getSourceType() == RedFlagSourceType.LAB_RESULT_SET)
                .filter(run -> run.getSourceId().equals(sourceId))
                .toList();
    }

    private List<String> ruleKeys(RedFlagEvaluationRun run) {
        return run.getEvents().stream()
                .map(event -> event.getRuleVersion().getRule().getStableKey())
                .toList();
    }

    private static Arguments boundary(String code, String value, Sex sex,
            RedFlagSeverity severity, String... ruleKeys) {
        return arguments(code, value, sex, severity, List.of(ruleKeys));
    }

    private record LabValue(String code, String value) { }
}
