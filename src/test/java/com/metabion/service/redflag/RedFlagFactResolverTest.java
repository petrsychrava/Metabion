package com.metabion.service.redflag;

import com.metabion.domain.FlareState;
import com.metabion.domain.LabResult;
import com.metabion.domain.LabResultConfirmationStatus;
import com.metabion.domain.LabResultSet;
import com.metabion.domain.LabResultSource;
import com.metabion.domain.LabTestDefinition;
import com.metabion.domain.PatientProfile;
import com.metabion.domain.RoleName;
import com.metabion.domain.Sex;
import com.metabion.domain.SymptomAnswerType;
import com.metabion.domain.SymptomCheckIn;
import com.metabion.domain.SymptomCheckInAnswer;
import com.metabion.domain.SymptomQuestion;
import com.metabion.domain.SymptomQuestionOption;
import com.metabion.domain.SymptomQuestionnaireVersion;
import com.metabion.domain.User;
import com.metabion.repository.SymptomCheckInRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedFlagFactResolverTest {

    private static final LocalDate COLLECTION_DATE = LocalDate.of(2026, 7, 28);

    private SymptomCheckInRepository checkIns;
    private RedFlagFactResolver resolver;
    private PatientProfile patient;
    private User patientUser;

    @BeforeEach
    void setUp() {
        checkIns = mock(SymptomCheckInRepository.class);
        resolver = new RedFlagFactResolver(checkIns, new RedFlagFactRegistry());
        patientUser = new User("patient@example.com", "hash");
        patientUser.addRole(RoleName.PATIENT);
        patient = new PatientProfile(patientUser);
        patient.setId(44L);
        patient.setSex(Sex.FEMALE);
    }

    @Test
    void symptomMapsOnlyRegisteredFlareNumericAndChoiceFactsInStableOrder() {
        var checkIn = symptomCheckIn(81L, COLLECTION_DATE, FlareState.ACTIVE_FLARE);
        checkIn.setNotes("private clinician narrative must never be inspected");
        addNumeric(checkIn, "stool-frequency", "9.00");
        addChoice(checkIn, "general-wellbeing", "very-unwell");
        addChoice(checkIn, "blood-in-stool", "visible");
        addChoice(checkIn, "abdominal-pain", "severe");
        addText(checkIn, "unregistered-notes", "private answer text");

        var input = resolver.forSymptom(checkIn);

        assertThat(input.trigger().sourceType()).isEqualTo(com.metabion.domain.RedFlagSourceType.SYMPTOM_CHECK_IN);
        assertThat(input.trigger().sourceId()).isEqualTo(81L);
        assertThat(input.trigger().observedOn()).isEqualTo(COLLECTION_DATE);
        assertThat(input.trigger().facts()).extracting(RedFlagFact::key)
                .containsExactly("symptom.flare_state", "symptom.abdominal_pain",
                        "symptom.blood_in_stool", "symptom.general_wellbeing", "symptom.stool_frequency");
        assertThat(input.trigger().facts()).extracting(RedFlagFact::textValue)
                .containsExactly("ACTIVE_FLARE", "severe", "visible", "very-unwell", null);
        assertThat(input.trigger().facts().getLast().decimalValue()).isEqualByComparingTo("9");
        assertThat(input.trigger().facts().getLast().unit()).isEqualTo("count/day");
        assertThat(input.trigger().facts()).allSatisfy(fact -> {
            assertThat(fact.key()).doesNotContain("notes");
            assertThat(fact.textValue()).isNotEqualTo("private answer text");
        });
        assertThat(input.lookback()).isEmpty();
    }

    @Test
    void labUsesCanonicalValuesAndLoadsInclusiveSevenDaySymptomContext() {
        var lab = labResultSet(91L);
        lab.replaceResults(List.of(
                labResult(lab, "CRP", "31.2", "mg/dL", "312.000000", "mg/L"),
                labResult(lab, "CREATININE", "4.005", "mg/dL", "354.000000", "umol/L"),
                labResult(lab, "UNREGISTERED", "1", "x", "99.000000", "x")), Instant.EPOCH);
        var older = symptomCheckIn(80L, COLLECTION_DATE.minusDays(7), FlareState.SUSPECTED_FLARE);
        var sameDay = symptomCheckIn(82L, COLLECTION_DATE, FlareState.NO_FLARE);
        when(checkIns.findForRedFlagContext(44L, COLLECTION_DATE.minusDays(7), COLLECTION_DATE))
                .thenReturn(List.of(sameDay, older));

        var input = resolver.forLab(lab);

        assertThat(input.trigger().facts()).extracting(RedFlagFact::key)
                .containsExactly("lab.CRP", "lab.CREATININE");
        assertThat(input.trigger().facts()).extracting(RedFlagFact::decimalValue)
                .containsExactly(new BigDecimal("312.000000"), new BigDecimal("354.000000"));
        assertThat(input.trigger().facts()).extracting(RedFlagFact::unit)
                .containsExactly("mg/L", "umol/L");
        assertThat(input.lookback()).extracting(RedFlagFactSet::sourceId).containsExactly(82L, 80L);
        verify(checkIns).findForRedFlagContext(44L, LocalDate.of(2026, 7, 21), COLLECTION_DATE);
        assertThat(input.patientProfile().facts()).singleElement().satisfies(fact -> {
            assertThat(fact.key()).isEqualTo("patient.sex");
            assertThat(fact.textValue()).isEqualTo("FEMALE");
        });
    }

    @Test
    void labRemovalHasEmptyTriggerAndNoLookback() {
        var input = resolver.forLabRemoval(labResultSet(91L));

        assertThat(input.trigger().sourceType()).isEqualTo(com.metabion.domain.RedFlagSourceType.LAB_RESULT_SET);
        assertThat(input.trigger().sourceId()).isEqualTo(91L);
        assertThat(input.trigger().facts()).isEmpty();
        assertThat(input.patientProfile().facts()).singleElement()
                .extracting(RedFlagFact::textValue).isEqualTo("FEMALE");
        assertThat(input.lookback()).isEmpty();
        verify(checkIns, never()).findForRedFlagContext(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @ParameterizedTest
    @EnumSource(value = Sex.class, names = {"INTERSEX", "PREFER_NOT_TO_SAY"})
    void omitsNonBinaryProfileSex(Sex sex) {
        patient.setSex(sex);

        assertThat(resolver.forSymptom(symptomCheckIn(81L, COLLECTION_DATE, FlareState.NO_FLARE))
                .patientProfile().facts()).isEmpty();
    }

    @Test
    void omitsMissingProfileSex() {
        patient.setSex(null);

        assertThat(resolver.forLabRemoval(labResultSet(91L)).patientProfile().facts()).isEmpty();
    }

    private SymptomCheckIn symptomCheckIn(Long id, LocalDate date, FlareState flareState) {
        var checkIn = new SymptomCheckIn(patient, mock(SymptomQuestionnaireVersion.class), date, flareState);
        ReflectionTestUtils.setField(checkIn, "id", id);
        return checkIn;
    }

    private void addNumeric(SymptomCheckIn checkIn, String key, String value) {
        var question = question(checkIn, key, SymptomAnswerType.NUMERIC);
        SymptomCheckInAnswer.numeric(checkIn, question, new BigDecimal(value), BigDecimal.ZERO);
    }

    private void addChoice(SymptomCheckIn checkIn, String key, String optionKey) {
        var question = question(checkIn, key, SymptomAnswerType.SINGLE_CHOICE);
        var option = new SymptomQuestionOption(optionKey, optionKey, BigDecimal.ZERO, 1);
        question.addOption(option);
        SymptomCheckInAnswer.choice(checkIn, question, option);
    }

    private void addText(SymptomCheckIn checkIn, String key, String value) {
        SymptomCheckInAnswer.text(checkIn, question(checkIn, key, SymptomAnswerType.TEXT), value);
    }

    private SymptomQuestion question(SymptomCheckIn checkIn, String key, SymptomAnswerType type) {
        var question = new SymptomQuestion(key, key, type, 1);
        question.setQuestionnaireVersion(checkIn.getQuestionnaireVersion());
        return question;
    }

    private LabResultSet labResultSet(Long id) {
        var lab = new LabResultSet(patient, COLLECTION_DATE, "private lab notes",
                LabResultSource.MANUAL, LabResultConfirmationStatus.CONFIRMED, patientUser, Instant.EPOCH);
        ReflectionTestUtils.setField(lab, "id", id);
        return lab;
    }

    private LabResult labResult(LabResultSet set, String code, String reportedValue,
            String reportedUnit, String canonicalValue, String canonicalUnit) {
        var definition = mock(LabTestDefinition.class);
        when(definition.getCode()).thenReturn(code);
        return new LabResult(set, definition, new BigDecimal(reportedValue), reportedUnit,
                new BigDecimal(canonicalValue), canonicalUnit, null, null);
    }
}
