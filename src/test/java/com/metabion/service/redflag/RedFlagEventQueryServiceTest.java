package com.metabion.service.redflag;

import com.metabion.domain.PatientProfile;
import com.metabion.domain.RedFlagEvaluationRun;
import com.metabion.domain.RedFlagRule;
import com.metabion.domain.RedFlagRuleConditionGroup;
import com.metabion.domain.RedFlagRuleVersion;
import com.metabion.domain.RedFlagSeverity;
import com.metabion.domain.RedFlagSourceOperation;
import com.metabion.domain.RedFlagSourceType;
import com.metabion.domain.RedFlagTriggerEvent;
import com.metabion.domain.RoleName;
import com.metabion.domain.User;
import com.metabion.dto.redflag.RedFlagEvaluationRunView;
import com.metabion.dto.redflag.RedFlagTriggerEventView;
import com.metabion.repository.PatientProfileRepository;
import com.metabion.repository.RedFlagEvaluationRunRepository;
import com.metabion.repository.UserRepository;
import com.metabion.service.AccessControlService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RedFlagEventQueryServiceTest {

    private static final Long USER_ID = 11L;
    private static final Long PATIENT_ID = 41L;
    private static final Instant LATER = Instant.parse("2026-07-29T12:00:00Z");
    private static final Instant EARLIER = Instant.parse("2026-07-29T11:00:00Z");

    @Mock UserRepository users;
    @Mock PatientProfileRepository patientProfiles;
    @Mock RedFlagEvaluationRunRepository runs;
    @Mock AccessControlService accessControl;

    private RedFlagEventQueryService service;

    @BeforeEach
    void setUp() {
        service = new RedFlagEventQueryService(users, patientProfiles, runs, accessControl);
    }

    @Test
    void patientCurrentDerivesOwnProfileAndMapsOpaqueSnapshotsInStableEventOrder() {
        var authentication = authentication("Patient@Example.com");
        var patient = patientContext(authentication, RoleName.PATIENT);
        var firstEvent = event(701L, "SYM_SEVERE_PAIN", 3, "pain-and-fever",
                RedFlagSeverity.EMERGENCY, LATER.plusSeconds(1),
                "{\"facts\":[{\"factKey\":\"symptom.pain\",\"raw\":\"keep-me\"}]}");
        var secondEvent = event(702L, "SYM_BLEEDING", 7, "bleeding",
                RedFlagSeverity.URGENT_REVIEW, LATER.plusSeconds(2),
                "{\"unrecognized\":true,\"nested\":{\"value\":12}}");
        var current = run(501L, RedFlagSourceType.SYMPTOM_CHECK_IN, 801L,
                RedFlagSourceOperation.UPSERT, LATER, RedFlagSeverity.EMERGENCY,
                true, null, List.of(firstEvent, secondEvent));
        when(runs.findByPatientProfileIdAndCurrentTrueOrderByEvaluatedAtDescIdDesc(PATIENT_ID))
                .thenReturn(List.of(current));

        var result = service.currentForCurrentPatient(authentication);

        assertThat(result).containsExactly(new RedFlagEvaluationRunView(
                501L, RedFlagSourceType.SYMPTOM_CHECK_IN, 801L,
                RedFlagSourceOperation.UPSERT, LATER, RedFlagSeverity.EMERGENCY,
                true, null, List.of(
                        new RedFlagTriggerEventView(
                                701L, "SYM_SEVERE_PAIN", 3, "pain-and-fever",
                                RedFlagSeverity.EMERGENCY, LATER.plusSeconds(1),
                                "{\"facts\":[{\"factKey\":\"symptom.pain\",\"raw\":\"keep-me\"}]}"),
                        new RedFlagTriggerEventView(
                                702L, "SYM_BLEEDING", 7, "bleeding",
                                RedFlagSeverity.URGENT_REVIEW, LATER.plusSeconds(2),
                                "{\"unrecognized\":true,\"nested\":{\"value\":12}}"))));
        verify(patientProfiles).findByUserId(USER_ID);
        verify(runs).findByPatientProfileIdAndCurrentTrueOrderByEvaluatedAtDescIdDesc(patient.getId());
        verify(runs, never()).findByPatientProfileIdOrderByEvaluatedAtDescIdDesc(PATIENT_ID);
    }

    @Test
    void patientHistoryIncludesSupersededRunsAndPreservesRepositoryRunOrder() {
        var authentication = authentication("patient@example.com");
        patientContext(authentication, RoleName.PATIENT);
        var current = run(502L, RedFlagSourceType.LAB_RESULT_SET, 902L,
                RedFlagSourceOperation.REMOVE, LATER, null, true, null, List.of());
        var superseded = run(501L, RedFlagSourceType.LAB_RESULT_SET, 902L,
                RedFlagSourceOperation.UPSERT, EARLIER, RedFlagSeverity.ROUTINE_REVIEW,
                false, current, List.of());
        when(runs.findByPatientProfileIdOrderByEvaluatedAtDescIdDesc(PATIENT_ID))
                .thenReturn(List.of(current, superseded));

        var result = service.historyForCurrentPatient(authentication);

        assertThat(result).extracting(RedFlagEvaluationRunView::id)
                .containsExactly(502L, 501L);
        assertThat(result.getFirst().current()).isTrue();
        assertThat(result.get(1).current()).isFalse();
        assertThat(result.get(1).supersededByRunId()).isEqualTo(502L);
        verify(runs).findByPatientProfileIdOrderByEvaluatedAtDescIdDesc(PATIENT_ID);
        verify(runs, never()).findByPatientProfileIdAndCurrentTrueOrderByEvaluatedAtDescIdDesc(PATIENT_ID);
    }

    @Test
    void patientHighestUsesOnlyCurrentRunsAndComparesSeverityPriority() {
        var authentication = authentication("patient@example.com");
        patientContext(authentication, RoleName.PATIENT);
        var routine = severityRun(RedFlagSeverity.ROUTINE_REVIEW);
        var emergency = severityRun(RedFlagSeverity.EMERGENCY);
        var urgent = severityRun(RedFlagSeverity.URGENT_REVIEW);
        when(runs.findByPatientProfileIdAndCurrentTrueOrderByEvaluatedAtDescIdDesc(PATIENT_ID))
                .thenReturn(List.of(routine, emergency, urgent));

        assertThat(service.currentHighestForCurrentPatient(authentication))
                .contains(RedFlagSeverity.EMERGENCY);

        verify(runs).findByPatientProfileIdAndCurrentTrueOrderByEvaluatedAtDescIdDesc(PATIENT_ID);
        verify(runs, never()).findByPatientProfileIdOrderByEvaluatedAtDescIdDesc(PATIENT_ID);
    }

    @Test
    void patientHighestIsEmptyWhenCurrentRunsHaveNoMatch() {
        var authentication = authentication("patient@example.com");
        patientContext(authentication, RoleName.PATIENT);
        var noMatch = severityRun(null);
        when(runs.findByPatientProfileIdAndCurrentTrueOrderByEvaluatedAtDescIdDesc(PATIENT_ID))
                .thenReturn(List.of(noMatch));

        assertThat(service.currentHighestForCurrentPatient(authentication)).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(value = RoleName.class, names = {"NUTRITION_SPECIALIST", "PHYSICIAN"})
    void assignedClinicalReaderCanReadCurrentRuns(RoleName role) {
        var authentication = authentication("staff@example.com");
        authenticatedUser(authentication, role);
        when(accessControl.canViewPatientClinicalData(authentication, PATIENT_ID)).thenReturn(true);
        when(runs.findByPatientProfileIdAndCurrentTrueOrderByEvaluatedAtDescIdDesc(PATIENT_ID))
                .thenReturn(List.of());

        assertThat(service.currentForClinicalPatient(authentication, PATIENT_ID)).isEmpty();

        verify(accessControl).canViewPatientClinicalData(authentication, PATIENT_ID);
        verify(runs).findByPatientProfileIdAndCurrentTrueOrderByEvaluatedAtDescIdDesc(PATIENT_ID);
    }

    @Test
    void assignedPhysicianCanReadHistory() {
        var authentication = authentication("physician@example.com");
        authenticatedUser(authentication, RoleName.PHYSICIAN);
        when(accessControl.canViewPatientClinicalData(authentication, PATIENT_ID)).thenReturn(true);
        when(runs.findByPatientProfileIdOrderByEvaluatedAtDescIdDesc(PATIENT_ID)).thenReturn(List.of());

        assertThat(service.historyForClinicalPatient(authentication, PATIENT_ID)).isEmpty();

        verify(accessControl).canViewPatientClinicalData(authentication, PATIENT_ID);
        verify(runs).findByPatientProfileIdOrderByEvaluatedAtDescIdDesc(PATIENT_ID);
    }

    @Test
    void adminCanReadHighestWithoutAssignmentCheck() {
        var authentication = authentication("admin@example.com");
        authenticatedUser(authentication, RoleName.ADMIN);
        var urgent = severityRun(RedFlagSeverity.URGENT_REVIEW);
        when(runs.findByPatientProfileIdAndCurrentTrueOrderByEvaluatedAtDescIdDesc(PATIENT_ID))
                .thenReturn(List.of(urgent));

        assertThat(service.currentHighestForClinicalPatient(authentication, PATIENT_ID))
                .contains(RedFlagSeverity.URGENT_REVIEW);

        verifyNoInteractions(accessControl);
        verify(runs).findByPatientProfileIdAndCurrentTrueOrderByEvaluatedAtDescIdDesc(PATIENT_ID);
    }

    @Test
    void unassignedClinicalReaderIsForbiddenBeforeRepositoryRead() {
        var authentication = authentication("staff@example.com");
        authenticatedUser(authentication, RoleName.NUTRITION_SPECIALIST);
        when(accessControl.canViewPatientClinicalData(authentication, PATIENT_ID)).thenReturn(false);

        assertStatus(() -> service.currentForClinicalPatient(authentication, PATIENT_ID), HttpStatus.FORBIDDEN);

        verify(accessControl).canViewPatientClinicalData(authentication, PATIENT_ID);
        verifyNoInteractions(runs);
    }

    @Test
    void coordinatorIsForbiddenBeforeAssignmentOrRepositoryRead() {
        var authentication = authentication("coordinator@example.com");
        authenticatedUser(authentication, RoleName.COORDINATOR);

        assertStatus(() -> service.historyForClinicalPatient(authentication, PATIENT_ID), HttpStatus.FORBIDDEN);

        verifyNoInteractions(accessControl, runs);
    }

    @Test
    void unauthenticatedPatientAndClinicalQueriesAreUnauthorized() {
        assertStatus(() -> service.currentForCurrentPatient(null), HttpStatus.UNAUTHORIZED);
        assertStatus(() -> service.currentHighestForClinicalPatient(null, PATIENT_ID), HttpStatus.UNAUTHORIZED);

        verifyNoInteractions(patientProfiles, accessControl, runs);
    }

    @Test
    void nonPatientCannotUseCurrentPatientBoundary() {
        var authentication = authentication("staff@example.com");
        authenticatedUser(authentication, RoleName.PHYSICIAN);

        assertStatus(() -> service.historyForCurrentPatient(authentication), HttpStatus.FORBIDDEN);

        verifyNoInteractions(patientProfiles, accessControl, runs);
    }

    @Test
    void currentPatientMethodsExposeNoPatientIdentifierParameter() {
        assertThat(Arrays.stream(RedFlagEventQueryService.class.getDeclaredMethods())
                .filter(method -> method.getName().endsWith("ForCurrentPatient"))
                .map(Method::getParameterTypes)
                .toList())
                .hasSize(3)
                .allSatisfy(parameterTypes -> assertThat(parameterTypes)
                        .containsExactly(Authentication.class));
    }

    @Test
    void serviceIsTransactionalReadOnly() {
        var transactional = AnnotatedElementUtils.findMergedAnnotation(
                RedFlagEventQueryService.class, Transactional.class);

        assertThat(transactional).isNotNull();
        assertThat(transactional.readOnly()).isTrue();
    }

    private PatientProfile patientContext(Authentication authentication, RoleName role) {
        var user = authenticatedUser(authentication, role);
        var patient = mock(PatientProfile.class);
        when(patient.getId()).thenReturn(PATIENT_ID);
        when(patientProfiles.findByUserId(USER_ID)).thenReturn(Optional.of(patient));
        return patient;
    }

    private User authenticatedUser(Authentication authentication, RoleName role) {
        var user = new User(authentication.getName(), "hash");
        user.setId(USER_ID);
        user.addRole(role);
        when(users.findByEmail(authentication.getName().trim().toLowerCase())).thenReturn(Optional.of(user));
        return user;
    }

    private RedFlagEvaluationRun run(
            Long id, RedFlagSourceType sourceType, Long sourceId,
            RedFlagSourceOperation sourceOperation, Instant evaluatedAt,
            RedFlagSeverity overallSeverity, boolean current,
            RedFlagEvaluationRun supersededBy, List<RedFlagTriggerEvent> events) {
        var run = mock(RedFlagEvaluationRun.class);
        when(run.getId()).thenReturn(id);
        when(run.getSourceType()).thenReturn(sourceType);
        when(run.getSourceId()).thenReturn(sourceId);
        when(run.getSourceOperation()).thenReturn(sourceOperation);
        when(run.getEvaluatedAt()).thenReturn(evaluatedAt);
        when(run.getOverallSeverity()).thenReturn(overallSeverity);
        when(run.isCurrent()).thenReturn(current);
        when(run.getSupersededByRun()).thenReturn(supersededBy);
        when(run.getEvents()).thenReturn(events);
        return run;
    }

    private RedFlagEvaluationRun severityRun(RedFlagSeverity severity) {
        var run = mock(RedFlagEvaluationRun.class);
        when(run.getOverallSeverity()).thenReturn(severity);
        return run;
    }

    private RedFlagTriggerEvent event(
            Long id, String ruleKey, int versionNumber, String groupKey,
            RedFlagSeverity severity, Instant triggeredAt, String matchedInputs) {
        var rule = mock(RedFlagRule.class);
        when(rule.getStableKey()).thenReturn(ruleKey);
        var version = mock(RedFlagRuleVersion.class);
        when(version.getRule()).thenReturn(rule);
        when(version.getVersionNumber()).thenReturn(versionNumber);
        var group = mock(RedFlagRuleConditionGroup.class);
        when(group.getStableKey()).thenReturn(groupKey);
        var event = mock(RedFlagTriggerEvent.class);
        when(event.getId()).thenReturn(id);
        when(event.getRuleVersion()).thenReturn(version);
        when(event.getMatchedGroup()).thenReturn(group);
        when(event.getSeverity()).thenReturn(severity);
        when(event.getTriggeredAt()).thenReturn(triggeredAt);
        when(event.getMatchedInputs()).thenReturn(matchedInputs);
        return event;
    }

    private static TestingAuthenticationToken authentication(String email) {
        var authentication = new TestingAuthenticationToken(email, "n/a");
        authentication.setAuthenticated(true);
        return authentication;
    }

    private static void assertStatus(
            org.assertj.core.api.ThrowableAssert.ThrowingCallable operation, HttpStatus expected) {
        assertThatThrownBy(operation)
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode()).isEqualTo(expected));
    }
}
