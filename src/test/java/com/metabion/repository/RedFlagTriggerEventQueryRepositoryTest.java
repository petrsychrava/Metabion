package com.metabion.repository;

import com.metabion.domain.PatientProfile;
import com.metabion.domain.RedFlagEvaluationRun;
import com.metabion.domain.RedFlagRuleStatus;
import com.metabion.domain.RedFlagRuleVersion;
import com.metabion.domain.RedFlagSeverity;
import com.metabion.domain.RedFlagSourceOperation;
import com.metabion.domain.RedFlagSourceType;
import com.metabion.domain.RedFlagTriggerEvent;
import com.metabion.domain.RoleName;
import com.metabion.domain.User;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class RedFlagTriggerEventQueryRepositoryTest {

    private static final Instant HISTORY_TOP = Instant.parse("2026-07-30T12:00:00Z");
    private static final Instant HISTORY_TIE = Instant.parse("2026-07-30T11:30:00Z");
    private static final Instant HISTORY_OLD = Instant.parse("2026-07-30T10:00:00Z");

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired UserRepository users;
    @Autowired PatientProfileRepository patientProfiles;
    @Autowired RedFlagRuleVersionRepository versions;
    @Autowired RedFlagEvaluationRunRepository runs;
    @Autowired RedFlagTriggerEventRepository events;
    @Autowired EntityManager entityManager;

    private PatientProfile patient;
    private PatientProfile otherPatient;

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @BeforeEach
    void setUp() {
        patient = patient("red-flag-history@example.com");
        otherPatient = patient("red-flag-other@example.com");
    }

    @Test
    void findCurrentForPatientReturnsOnlyCurrentEventsForThatPatientInStableOrder() {
        var noMatchSourceRule = activeSymptomVersion("SYM_SEVERE_ABDOMINAL_PAIN");
        var routineRule = activeSymptomVersion("SYM_MODERATE_DETERIORATION");
        var urgentRule = activeSymptomVersion("SYM_ACTIVE_FLARE");
        var emergencyRule = activeSymptomVersion("SYM_SIGNIFICANT_BLEEDING");

        var superseded = matchedRun(patient, 801L, HISTORY_OLD, RedFlagSeverity.EMERGENCY);
        event(superseded, noMatchSourceRule, HISTORY_OLD, RedFlagSeverity.EMERGENCY);
        var noMatchSuccessor = pendingNoMatchRun(patient, 801L, HISTORY_OLD.plusSeconds(30));
        supersede(superseded, noMatchSuccessor);

        var urgentRun = matchedRun(patient, 802L, HISTORY_TIE.minusSeconds(30), RedFlagSeverity.URGENT_REVIEW);
        var urgentEvent = event(urgentRun, urgentRule, HISTORY_TIE, RedFlagSeverity.URGENT_REVIEW);
        var routineRun = matchedRun(patient, 803L, HISTORY_TIE.minusSeconds(15), RedFlagSeverity.ROUTINE_REVIEW);
        var routineEvent = event(routineRun, routineRule, HISTORY_TIE, RedFlagSeverity.ROUTINE_REVIEW);
        var emergencyRun = matchedRun(patient, 804L, HISTORY_TOP.minusSeconds(15), RedFlagSeverity.EMERGENCY);
        var emergencyEvent = event(emergencyRun, emergencyRule, HISTORY_TOP, RedFlagSeverity.EMERGENCY);

        var otherRun = matchedRun(otherPatient, 901L, HISTORY_TOP, RedFlagSeverity.EMERGENCY);
        event(otherRun, emergencyRule, HISTORY_TOP.plusSeconds(5), RedFlagSeverity.EMERGENCY);
        entityManager.clear();

        assertThat(events.findCurrentForPatient(patient.getId()))
                .extracting(RedFlagTriggerEvent::getId)
                .containsExactly(emergencyEvent.getId(), routineEvent.getId(), urgentEvent.getId());
    }

    @Test
    void findHistoryPageAppliesSeverityTimeFiltersAndGapFreeKeysetPages() {
        var oldRule = activeSymptomVersion("SYM_SEVERE_ABDOMINAL_PAIN");
        var routineRule = activeSymptomVersion("SYM_MODERATE_DETERIORATION");
        var urgentRule = activeSymptomVersion("SYM_ACTIVE_FLARE");
        var emergencyRule = activeSymptomVersion("SYM_SIGNIFICANT_BLEEDING");

        var superseded = matchedRun(patient, 811L, HISTORY_OLD, RedFlagSeverity.EMERGENCY);
        var supersededEvent = event(superseded, oldRule, HISTORY_OLD, RedFlagSeverity.EMERGENCY);
        var noMatchSuccessor = pendingNoMatchRun(patient, 811L, HISTORY_OLD.plusSeconds(30));
        supersede(superseded, noMatchSuccessor);

        var urgentRun = matchedRun(patient, 812L, HISTORY_TIE.minusSeconds(30), RedFlagSeverity.URGENT_REVIEW);
        var urgentEvent = event(urgentRun, urgentRule, HISTORY_TIE, RedFlagSeverity.URGENT_REVIEW);
        var routineRun = matchedRun(patient, 813L, HISTORY_TIE.minusSeconds(15), RedFlagSeverity.ROUTINE_REVIEW);
        var routineEvent = event(routineRun, routineRule, HISTORY_TIE, RedFlagSeverity.ROUTINE_REVIEW);
        var emergencyRun = matchedRun(patient, 814L, HISTORY_TOP.minusSeconds(15), RedFlagSeverity.EMERGENCY);
        var emergencyEvent = event(emergencyRun, emergencyRule, HISTORY_TOP, RedFlagSeverity.EMERGENCY);

        var otherRun = matchedRun(otherPatient, 902L, HISTORY_TOP, RedFlagSeverity.EMERGENCY);
        event(otherRun, emergencyRule, HISTORY_TOP.plusSeconds(5), RedFlagSeverity.EMERGENCY);
        entityManager.clear();

        var firstPage = events.findHistoryPage(
                patient.getId(), null, null, null, null, null,
                PageRequest.of(0, 2));

        assertThat(firstPage).extracting(RedFlagTriggerEvent::getId)
                .containsExactly(emergencyEvent.getId(), routineEvent.getId());

        var secondPage = events.findHistoryPage(
                patient.getId(), null, null, null,
                firstPage.getLast().getTriggeredAt(),
                firstPage.getLast().getId(),
                PageRequest.of(0, 2));

        assertThat(secondPage).extracting(RedFlagTriggerEvent::getId)
                .containsExactly(urgentEvent.getId(), supersededEvent.getId());
        assertThat(secondPage).doesNotContainAnyElementsOf(firstPage);

        assertThat(events.findHistoryPage(
                patient.getId(),
                RedFlagSeverity.URGENT_REVIEW,
                HISTORY_TIE,
                HISTORY_TOP.plusSeconds(1),
                null,
                null,
                PageRequest.of(0, 10)))
                .extracting(RedFlagTriggerEvent::getId)
                .containsExactly(urgentEvent.getId());
    }

    @Test
    void findRuleKeysByEvaluationRunIdReturnsSortedStableKeys() {
        var severePain = activeSymptomVersion("SYM_SEVERE_ABDOMINAL_PAIN");
        var significantBleeding = activeSymptomVersion("SYM_SIGNIFICANT_BLEEDING");
        var run = matchedRun(patient, 821L, HISTORY_TOP, RedFlagSeverity.EMERGENCY);
        event(run, significantBleeding, HISTORY_TOP.plusSeconds(2), RedFlagSeverity.EMERGENCY);
        event(run, severePain, HISTORY_TOP.plusSeconds(1), RedFlagSeverity.EMERGENCY);
        entityManager.clear();

        assertThat(events.findRuleKeysByEvaluationRunId(run.getId()))
                .containsExactly("SYM_SEVERE_ABDOMINAL_PAIN", "SYM_SIGNIFICANT_BLEEDING");
    }

    private PatientProfile patient(String email) {
        var user = new User(email, "hash");
        user.setEnabled(true);
        user.addRole(RoleName.PATIENT);
        return patientProfiles.saveAndFlush(new PatientProfile(users.saveAndFlush(user)));
    }

    private RedFlagEvaluationRun matchedRun(
            PatientProfile targetPatient, Long sourceId, Instant evaluatedAt, RedFlagSeverity severity) {
        var run = RedFlagEvaluationRun.pending(
                targetPatient, RedFlagSourceType.SYMPTOM_CHECK_IN, sourceId,
                RedFlagSourceOperation.UPSERT, evaluatedAt, severity);
        run.markCurrent();
        return runs.saveAndFlush(run);
    }

    private RedFlagEvaluationRun pendingNoMatchRun(
            PatientProfile targetPatient, Long sourceId, Instant evaluatedAt) {
        return runs.saveAndFlush(RedFlagEvaluationRun.pending(
                targetPatient, RedFlagSourceType.SYMPTOM_CHECK_IN, sourceId,
                RedFlagSourceOperation.REMOVE, evaluatedAt, null));
    }

    private void supersede(RedFlagEvaluationRun previous, RedFlagEvaluationRun successor) {
        var managedPrevious = runs.findCurrentForUpdate(previous.getSourceType(), previous.getSourceId())
                .orElseThrow();
        managedPrevious.supersedeWith(successor);
        runs.saveAndFlush(managedPrevious);
        successor.markCurrent();
        runs.saveAndFlush(successor);
        entityManager.flush();
    }

    private RedFlagTriggerEvent event(
            RedFlagEvaluationRun run, RedFlagRuleVersion version, Instant triggeredAt, RedFlagSeverity severity) {
        return events.saveAndFlush(new RedFlagTriggerEvent(
                run, version, version.getConditionGroups().getFirst(), severity, triggeredAt,
                "{\"facts\":[{\"factKey\":\"" + version.getRule().getStableKey() + "\"}]}"));
    }

    private RedFlagRuleVersion activeSymptomVersion(String stableKey) {
        return versions.findByStatusAndTriggerSource(RedFlagRuleStatus.ACTIVE, RedFlagSourceType.SYMPTOM_CHECK_IN)
                .stream()
                .filter(version -> version.getRule().getStableKey().equals(stableKey))
                .findFirst()
                .orElseThrow();
    }
}
