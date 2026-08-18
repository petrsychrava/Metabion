package com.metabion.service.redflag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metabion.domain.PatientProfile;
import com.metabion.domain.RedFlagEvaluationRun;
import com.metabion.domain.RedFlagRule;
import com.metabion.domain.RedFlagRuleVersion;
import com.metabion.domain.RedFlagSeverity;
import com.metabion.domain.RedFlagSourceType;
import com.metabion.domain.RedFlagTriggerEvent;
import com.metabion.domain.RoleName;
import com.metabion.domain.User;
import com.metabion.dto.redflag.ClinicalRedFlagEventResponse;
import com.metabion.dto.redflag.ClinicalRedFlagHistoryResponse;
import com.metabion.dto.redflag.ClinicalRedFlagSnapshotResponse;
import com.metabion.dto.redflag.PatientRedFlagEventResponse;
import com.metabion.dto.redflag.PatientRedFlagHistoryResponse;
import com.metabion.dto.redflag.PatientRedFlagSnapshotResponse;
import com.metabion.dto.redflag.RedFlagHistoryQuery;
import com.metabion.dto.redflag.RedFlagMatchedInputsResponse;
import com.metabion.dto.redflag.RedFlagWriteOutcomeResponse;
import com.metabion.repository.PatientProfileRepository;
import com.metabion.repository.RedFlagTriggerEventRepository;
import com.metabion.repository.UserRepository;
import com.metabion.service.AccessControlService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RedFlagEventQueryServiceTest {

    private static final Long USER_ID = 11L;
    private static final Long PATIENT_ID = 41L;
    private static final Instant DETECTED_AT = Instant.parse("2026-07-29T12:00:00Z");
    private static final String MATCHED_INPUTS = "{\"facts\":[{\"sourceType\":\"LAB_RESULT_SET\","
            + "\"sourceId\":91,\"factKey\":\"lab.CRP\",\"observedOn\":\"2026-07-28\","
            + "\"decimalValue\":\"312\",\"textValue\":null,\"unit\":\"mg/L\"}]}";

    @Mock UserRepository users;
    @Mock PatientProfileRepository patientProfiles;
    @Mock RedFlagTriggerEventRepository events;
    @Mock AccessControlService accessControl;

    private RedFlagHistoryCursorCodec cursorCodec;
    private RedFlagEventQueryService service;

    @BeforeEach
    void setUp() {
        cursorCodec = new RedFlagHistoryCursorCodec();
        var serializer = serializer();
        service = new RedFlagEventQueryService(
                users,
                patientProfiles,
                events,
                accessControl,
                cursorCodec,
                new PatientRedFlagResponseAssembler(),
                new ClinicalRedFlagResponseAssembler(serializer));
    }

    @Test
    void patientCurrentUsesOneEventSetAndRestrictedProjection() {
        var authentication = authentication("Patient@Example.com");
        patientContext(authentication, "UTC");
        var emergency = emergencyEvent();
        var routine = routineEvent();
        when(events.findCurrentForPatient(PATIENT_ID))
                .thenReturn(List.of(emergency, routine));

        PatientRedFlagSnapshotResponse result = service.currentForCurrentPatient(authentication);

        assertThat(result.highestSeverity()).isEqualTo(RedFlagSeverity.EMERGENCY);
        assertThat(result.flags())
                .extracting(PatientRedFlagEventResponse::ruleKey)
                .containsExactly("SYM_SEVERE_ABDOMINAL_PAIN", "SYM_SUSPECTED_FLARE");
        assertThat(result.flags().getFirst()).satisfies(flag -> {
            assertThat(flag.eventId()).isEqualTo(701L);
            assertThat(flag.detectedAt()).isEqualTo(DETECTED_AT);
            assertThat(flag.sourceType()).isEqualTo(RedFlagSourceType.SYMPTOM_CHECK_IN);
            assertThat(flag.current()).isTrue();
            assertThat(flag.supersededAt()).isNull();
        });
        verify(events).findCurrentForPatient(PATIENT_ID);
        verify(events, never()).findHistoryPage(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void patientCurrentEmptyResultsHaveNullHighestSeverity() {
        var authentication = authentication("patient@example.com");
        patientContext(authentication, "UTC");
        when(events.findCurrentForPatient(PATIENT_ID)).thenReturn(List.of());

        assertThat(service.currentForCurrentPatient(authentication))
                .isEqualTo(new PatientRedFlagSnapshotResponse(null, List.of()));
    }

    @Test
    void patientHistoryUsesFiltersPaginationAndPatientTimezone() {
        var authentication = authentication("patient@example.com");
        patientContext(authentication, "America/New_York");
        var first = event(801L, "SYM_ONE", 1, RedFlagSeverity.URGENT_REVIEW,
                Instant.parse("2026-07-29T12:00:00Z"), true, null, RedFlagSourceType.SYMPTOM_CHECK_IN, 301L);
        var extra = event(800L, "SYM_TWO", 1, RedFlagSeverity.URGENT_REVIEW,
                Instant.parse("2026-07-29T11:00:00Z"), true, null, RedFlagSourceType.SYMPTOM_CHECK_IN, 302L);
        when(events.findHistoryPage(eq(PATIENT_ID), eq(RedFlagSeverity.URGENT_REVIEW),
                any(), any(), isNull(), isNull(), any()))
                .thenReturn(List.of(first, extra));

        PatientRedFlagHistoryResponse result = service.historyForCurrentPatient(authentication,
                new RedFlagHistoryQuery(
                        LocalDate.of(2026, 7, 28),
                        LocalDate.of(2026, 7, 29),
                        RedFlagSeverity.URGENT_REVIEW,
                        null,
                        1));

        assertThat(result.items())
                .extracting(PatientRedFlagEventResponse::eventId)
                .containsExactly(801L);
        assertThat(result.nextCursor()).isEqualTo(cursorCodec.encode(first.getTriggeredAt(), first.getId()));
        var fromCaptor = ArgumentCaptor.forClass(Instant.class);
        var toCaptor = ArgumentCaptor.forClass(Instant.class);
        var pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(events).findHistoryPage(eq(PATIENT_ID), eq(RedFlagSeverity.URGENT_REVIEW),
                fromCaptor.capture(), toCaptor.capture(), isNull(), isNull(), pageableCaptor.capture());
        assertThat(fromCaptor.getValue()).isEqualTo(Instant.parse("2026-07-28T04:00:00Z"));
        assertThat(toCaptor.getValue()).isEqualTo(Instant.parse("2026-07-30T04:00:00Z"));
        assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(2);
    }

    @Test
    void historyCursorIsDecodedIntoRepositoryKeyset() {
        var authentication = authentication("patient@example.com");
        patientContext(authentication, "UTC");
        var cursor = cursorCodec.encode(Instant.parse("2026-07-29T12:00:00Z"), 801L);
        when(events.findHistoryPage(eq(PATIENT_ID), isNull(), isNull(), isNull(),
                any(), eq(801L), any()))
                .thenReturn(List.of());

        service.historyForCurrentPatient(authentication,
                new RedFlagHistoryQuery(null, null, null, cursor, 25));

        verify(events).findHistoryPage(eq(PATIENT_ID), isNull(), isNull(), isNull(),
                eq(Instant.parse("2026-07-29T12:00:00Z")), eq(801L), any());
    }

    @Test
    void invalidHistoryQueryIsRejectedBeforeRepositoryRead() {
        var authentication = authentication("patient@example.com");
        patientContext(authentication, "UTC");

        assertStatus(() -> service.historyForCurrentPatient(authentication,
                new RedFlagHistoryQuery(LocalDate.of(2026, 7, 30), LocalDate.of(2026, 7, 29),
                        null, null, 25)), HttpStatus.BAD_REQUEST);
        assertStatus(() -> service.historyForCurrentPatient(authentication,
                new RedFlagHistoryQuery(LocalDate.of(2025, 7, 1), LocalDate.of(2026, 7, 29),
                        null, null, 25)), HttpStatus.BAD_REQUEST);
        assertStatus(() -> service.historyForCurrentPatient(authentication,
                new RedFlagHistoryQuery(null, null, null, null, 101)), HttpStatus.BAD_REQUEST);

        verify(events, never()).findHistoryPage(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void clinicalHistoryAddsOnlyRuleVersionAndMatchedInputs() {
        var authentication = authentication("physician@example.com");
        authenticatedUser(authentication, RoleName.PHYSICIAN);
        var patient = patientProfile("UTC");
        var event = clinicalEvent();
        when(accessControl.canViewPatientClinicalData(authentication, PATIENT_ID)).thenReturn(true);
        when(patientProfiles.findById(PATIENT_ID)).thenReturn(Optional.of(patient));
        when(events.findHistoryPage(eq(PATIENT_ID), isNull(),
                any(), any(), isNull(), isNull(), any()))
                .thenReturn(List.of(event));

        ClinicalRedFlagHistoryResponse result = service.historyForClinicalPatient(
                authentication, PATIENT_ID,
                new RedFlagHistoryQuery(null, null, null, null, 25));

        assertThat(result.items()).singleElement().satisfies(flag -> {
            assertThat(flag.ruleVersion()).isEqualTo(1);
            assertThat(flag.matchedInputs().facts()).singleElement()
                    .extracting(RedFlagMatchedInputsResponse.Fact::factKey)
                    .isEqualTo("lab.CRP");
        });
    }

    @ParameterizedTest
    @EnumSource(value = RoleName.class, names = {"NUTRITION_SPECIALIST", "PHYSICIAN"})
    void assignedClinicalReaderCanReadCurrentEvents(RoleName role) {
        var authentication = authentication("staff@example.com");
        authenticatedUser(authentication, role);
        var patient = patientProfile("UTC");
        when(accessControl.canViewPatientClinicalData(authentication, PATIENT_ID)).thenReturn(true);
        when(patientProfiles.findById(PATIENT_ID)).thenReturn(Optional.of(patient));
        when(events.findCurrentForPatient(PATIENT_ID)).thenReturn(List.of());

        assertThat(service.currentForClinicalPatient(authentication, PATIENT_ID))
                .isEqualTo(new ClinicalRedFlagSnapshotResponse(null, List.of()));

        var ordered = inOrder(accessControl, patientProfiles, events);
        ordered.verify(accessControl).canViewPatientClinicalData(authentication, PATIENT_ID);
        ordered.verify(patientProfiles).findById(PATIENT_ID);
        ordered.verify(events).findCurrentForPatient(PATIENT_ID);
    }

    @Test
    void adminCanReadClinicalCurrentWithoutAssignmentCheck() {
        var authentication = authentication("admin@example.com");
        authenticatedUser(authentication, RoleName.ADMIN);
        var patient = patientProfile("UTC");
        var routine = routineEvent();
        when(patientProfiles.findById(PATIENT_ID)).thenReturn(Optional.of(patient));
        when(events.findCurrentForPatient(PATIENT_ID)).thenReturn(List.of(routine));

        ClinicalRedFlagSnapshotResponse result = service.currentForClinicalPatient(authentication, PATIENT_ID);

        assertThat(result.highestSeverity()).isEqualTo(RedFlagSeverity.ROUTINE_REVIEW);
        verifyNoInteractions(accessControl);
    }

    @Test
    void unassignedClinicalReaderIsForbiddenBeforePatientLookupOrRepositoryRead() {
        var authentication = authentication("staff@example.com");
        authenticatedUser(authentication, RoleName.NUTRITION_SPECIALIST);
        when(accessControl.canViewPatientClinicalData(authentication, PATIENT_ID)).thenReturn(false);

        assertStatus(() -> service.currentForClinicalPatient(authentication, PATIENT_ID), HttpStatus.FORBIDDEN);

        verify(accessControl).canViewPatientClinicalData(authentication, PATIENT_ID);
        verifyNoInteractions(patientProfiles, events);
    }

    @Test
    void coordinatorIsForbiddenBeforeAssignmentPatientLookupOrRepositoryRead() {
        var authentication = authentication("coordinator@example.com");
        authenticatedUser(authentication, RoleName.COORDINATOR);

        assertStatus(() -> service.historyForClinicalPatient(authentication, PATIENT_ID,
                new RedFlagHistoryQuery(null, null, null, null, 25)), HttpStatus.FORBIDDEN);

        verifyNoInteractions(accessControl, patientProfiles, events);
    }

    @Test
    void authorizedClinicalReaderGetsNotFoundForMissingPatientAfterAccessCheck() {
        var authentication = authentication("physician@example.com");
        authenticatedUser(authentication, RoleName.PHYSICIAN);
        when(accessControl.canViewPatientClinicalData(authentication, PATIENT_ID)).thenReturn(true);
        when(patientProfiles.findById(PATIENT_ID)).thenReturn(Optional.empty());

        assertStatus(() -> service.currentForClinicalPatient(authentication, PATIENT_ID), HttpStatus.NOT_FOUND);

        verifyNoInteractions(events);
    }

    @Test
    void currentPatientBoundaryRequiresPatientRoleAndOwnProfile() {
        var authentication = authentication("staff@example.com");
        authenticatedUser(authentication, RoleName.PHYSICIAN);

        assertStatus(() -> service.currentForCurrentPatient(authentication), HttpStatus.FORBIDDEN);

        verifyNoInteractions(patientProfiles, accessControl, events);
    }

    @Test
    void responseAssemblersMapWriteOutcomeToRestrictedCurrentFlags() {
        var outcome = new RedFlagEvaluationOutcome(
                RedFlagSeverity.EMERGENCY,
                List.of(new RedFlagEvaluationOutcome.Flag(
                        901L, "SYM_SEVERE_ABDOMINAL_PAIN", RedFlagSeverity.EMERGENCY,
                        DETECTED_AT, RedFlagSourceType.SYMPTOM_CHECK_IN, 601L,
                        1, "{\"facts\":[]}")),
                List.of("SYM_SUSPECTED_FLARE"));

        var response = new PatientRedFlagResponseAssembler().outcome(outcome);

        assertThat(response.highestSeverity()).isEqualTo(RedFlagSeverity.EMERGENCY);
        assertThat(response.currentFlags()).containsExactly(new PatientRedFlagEventResponse(
                901L, "SYM_SEVERE_ABDOMINAL_PAIN", RedFlagSeverity.EMERGENCY,
                DETECTED_AT, RedFlagSourceType.SYMPTOM_CHECK_IN, 601L, true, null));
        assertThat(response.clearedRuleKeys()).containsExactly("SYM_SUSPECTED_FLARE");
    }

    @Test
    void publicRecordsExposeNoAuditOnlyFields() {
        assertThat(List.of(
                PatientRedFlagEventResponse.class,
                ClinicalRedFlagEventResponse.class,
                PatientRedFlagSnapshotResponse.class,
                ClinicalRedFlagSnapshotResponse.class,
                PatientRedFlagHistoryResponse.class,
                ClinicalRedFlagHistoryResponse.class,
                RedFlagMatchedInputsResponse.class,
                RedFlagWriteOutcomeResponse.class))
                .allSatisfy(type -> assertThat(recordComponentNames(type))
                        .doesNotContain("evaluationRunId", "sourceOperation", "matchedGroupKey"));
    }

    @Test
    void serviceIsTransactionalReadOnly() {
        var transactional = AnnotatedElementUtils.findMergedAnnotation(
                RedFlagEventQueryService.class, Transactional.class);

        assertThat(transactional).isNotNull();
        assertThat(transactional.readOnly()).isTrue();
    }

    private RedFlagTriggerEvent emergencyEvent() {
        return event(701L, "SYM_SEVERE_ABDOMINAL_PAIN", 1, RedFlagSeverity.EMERGENCY,
                DETECTED_AT, true, null, RedFlagSourceType.SYMPTOM_CHECK_IN, 601L);
    }

    private RedFlagTriggerEvent routineEvent() {
        return event(702L, "SYM_SUSPECTED_FLARE", 2, RedFlagSeverity.ROUTINE_REVIEW,
                DETECTED_AT.minusSeconds(60), true, null, RedFlagSourceType.SYMPTOM_CHECK_IN, 602L);
    }

    private RedFlagTriggerEvent clinicalEvent() {
        return event(703L, "LAB_HIGH_CRP", 1, RedFlagSeverity.URGENT_REVIEW,
                DETECTED_AT.minusSeconds(120), false, DETECTED_AT,
                RedFlagSourceType.LAB_RESULT_SET, 91L);
    }

    private PatientProfile patientContext(Authentication authentication, String timezone) {
        authenticatedUser(authentication, RoleName.PATIENT);
        var patient = patientProfile(timezone);
        when(patientProfiles.findByUserId(USER_ID)).thenReturn(Optional.of(patient));
        return patient;
    }

    private PatientProfile patientProfile(String timezone) {
        var patient = mock(PatientProfile.class);
        lenient().when(patient.getId()).thenReturn(PATIENT_ID);
        lenient().when(patient.getTimezone()).thenReturn(timezone);
        return patient;
    }

    private User authenticatedUser(Authentication authentication, RoleName role) {
        var user = new User(authentication.getName(), "hash");
        user.setId(USER_ID);
        user.addRole(role);
        when(users.findByEmail(authentication.getName().trim().toLowerCase(Locale.ROOT)))
                .thenReturn(Optional.of(user));
        return user;
    }

    private RedFlagTriggerEvent event(
            Long id, String ruleKey, int versionNumber, RedFlagSeverity severity,
            Instant triggeredAt, boolean current, Instant supersededAt,
            RedFlagSourceType sourceType, Long sourceId) {
        var rule = mock(RedFlagRule.class);
        lenient().when(rule.getStableKey()).thenReturn(ruleKey);
        var version = mock(RedFlagRuleVersion.class);
        lenient().when(version.getRule()).thenReturn(rule);
        lenient().when(version.getVersionNumber()).thenReturn(versionNumber);
        var run = mock(RedFlagEvaluationRun.class);
        lenient().when(run.getSourceType()).thenReturn(sourceType);
        lenient().when(run.getSourceId()).thenReturn(sourceId);
        lenient().when(run.isCurrent()).thenReturn(current);
        if (supersededAt != null) {
            var successor = mock(RedFlagEvaluationRun.class);
            lenient().when(successor.getEvaluatedAt()).thenReturn(supersededAt);
            lenient().when(run.getSupersededByRun()).thenReturn(successor);
        } else {
            lenient().when(run.getSupersededByRun()).thenReturn(null);
        }
        var event = mock(RedFlagTriggerEvent.class);
        lenient().when(event.getId()).thenReturn(id);
        lenient().when(event.getRuleVersion()).thenReturn(version);
        lenient().when(event.getSeverity()).thenReturn(severity);
        lenient().when(event.getTriggeredAt()).thenReturn(triggeredAt);
        lenient().when(event.getMatchedInputs()).thenReturn(MATCHED_INPUTS);
        lenient().when(event.getEvaluationRun()).thenReturn(run);
        return event;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private RedFlagSnapshotSerializer serializer() {
        ObjectProvider<ObjectMapper> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable(any())).thenReturn(new ObjectMapper());
        return new RedFlagSnapshotSerializer(provider);
    }

    private static Set<String> recordComponentNames(Class<?> type) {
        return Arrays.stream(type.getRecordComponents())
                .map(RecordComponent::getName)
                .collect(Collectors.toSet());
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
