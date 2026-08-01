package com.metabion.service.redflag;

import com.metabion.domain.LabResultSet;
import com.metabion.domain.PatientProfile;
import com.metabion.domain.RedFlagEvaluationRun;
import com.metabion.domain.RedFlagRuleConditionGroup;
import com.metabion.domain.RedFlagRuleVersion;
import com.metabion.domain.RedFlagSeverity;
import com.metabion.domain.RedFlagSourceOperation;
import com.metabion.domain.RedFlagSourceType;
import com.metabion.domain.RedFlagTriggerEvent;
import com.metabion.domain.SymptomCheckIn;
import com.metabion.repository.RedFlagEvaluationRunRepository;
import com.metabion.repository.RedFlagTriggerEventRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RedFlagEvaluationServiceTest {

    private static final Long PATIENT_ID = 41L;
    private static final Long SYMPTOM_ID = 81L;
    private static final Long LAB_ID = 91L;
    private static final LocalDate OBSERVED_ON = LocalDate.of(2026, 7, 28);
    private static final Instant NOW = Instant.parse("2026-07-29T12:34:56Z");

    private RedFlagRuleCatalog catalog;
    private RedFlagFactResolver resolver;
    private RedFlagRuleEngine engine;
    private RedFlagSnapshotSerializer serializer;
    private RedFlagEvaluationRunRepository runs;
    private RedFlagTriggerEventRepository events;
    private EntityManager entityManager;
    private Clock clock;
    private RedFlagEvaluationService service;
    private PatientProfile patient;
    private SymptomCheckIn symptom;
    private LabResultSet lab;

    @BeforeEach
    void setUp() {
        catalog = mock(RedFlagRuleCatalog.class);
        resolver = mock(RedFlagFactResolver.class);
        engine = mock(RedFlagRuleEngine.class);
        serializer = mock(RedFlagSnapshotSerializer.class);
        runs = mock(RedFlagEvaluationRunRepository.class);
        events = mock(RedFlagTriggerEventRepository.class);
        entityManager = mock(EntityManager.class);
        clock = mock(Clock.class);
        service = new RedFlagEvaluationService(
                catalog, resolver, engine, serializer, runs, events, entityManager, clock);

        patient = mock(PatientProfile.class);
        when(patient.getId()).thenReturn(PATIENT_ID);
        symptom = mock(SymptomCheckIn.class);
        when(symptom.getId()).thenReturn(SYMPTOM_ID);
        when(symptom.getPatientProfile()).thenReturn(patient);
        lab = mock(LabResultSet.class);
        when(lab.getId()).thenReturn(LAB_ID);
        when(lab.getPatientProfile()).thenReturn(patient);
        when(clock.instant()).thenReturn(NOW);
    }

    @Test
    void isTransactionalSoPersistenceFailuresRollBackTheSourceWrite() {
        assertThat(AnnotatedElementUtils.hasAnnotation(
                RedFlagEvaluationService.class, Transactional.class)).isTrue();
    }

    @Test
    void persistsPendingBeforeLockingThenSupersedesAndLinksTwoExactMatches() {
        var input = input(RedFlagSourceType.SYMPTOM_CHECK_IN, SYMPTOM_ID, false);
        var routine = match(101L, 201L, RedFlagSeverity.ROUTINE_REVIEW, "symptom.stool_frequency");
        var emergency = match(102L, 202L, RedFlagSeverity.EMERGENCY, "symptom.abdominal_pain");
        var definitions = List.of(routine.rule(), emergency.rule());
        var result = new RedFlagEvaluationResult(List.of(routine, emergency), RedFlagSeverity.EMERGENCY);
        when(catalog.activeFor(RedFlagSourceType.SYMPTOM_CHECK_IN)).thenReturn(definitions);
        when(resolver.forSymptom(symptom)).thenReturn(input);
        when(engine.evaluate(definitions, input)).thenReturn(result);
        when(serializer.serialize(routine.matchedFacts())).thenReturn("{\"match\":1}");
        when(serializer.serialize(emergency.matchedFacts())).thenReturn("{\"match\":2}");

        var preceding = RedFlagEvaluationRun.pending(
                patient, RedFlagSourceType.SYMPTOM_CHECK_IN, SYMPTOM_ID,
                RedFlagSourceOperation.UPSERT, NOW.minusSeconds(60), RedFlagSeverity.URGENT_REVIEW);
        preceding.markCurrent();
        when(runs.findCurrentForUpdate(RedFlagSourceType.SYMPTOM_CHECK_IN, SYMPTOM_ID))
                .thenReturn(Optional.of(preceding));
        var version101 = mock(RedFlagRuleVersion.class);
        var version102 = mock(RedFlagRuleVersion.class);
        var group201 = mock(RedFlagRuleConditionGroup.class);
        var group202 = mock(RedFlagRuleConditionGroup.class);
        when(entityManager.getReference(RedFlagRuleVersion.class, 101L)).thenReturn(version101);
        when(entityManager.getReference(RedFlagRuleVersion.class, 102L)).thenReturn(version102);
        when(entityManager.getReference(RedFlagRuleConditionGroup.class, 201L)).thenReturn(group201);
        when(entityManager.getReference(RedFlagRuleConditionGroup.class, 202L)).thenReturn(group202);

        var successor = new AtomicReference<RedFlagEvaluationRun>();
        var savedStates = new ArrayList<RunState>();
        when(runs.saveAndFlush(any())).thenAnswer(invocation -> {
            var run = invocation.getArgument(0, RedFlagEvaluationRun.class);
            if (successor.get() == null) {
                successor.set(run);
                ReflectionTestUtils.setField(run, "id", 501L);
            }
            savedStates.add(new RunState(run, run.isCurrent(), run.getSupersededByRun()));
            return run;
        });

        service.evaluateSymptom(symptom);

        assertThat(savedStates).hasSize(3);
        assertThat(savedStates.get(0)).satisfies(state -> {
            assertThat(state.run()).isSameAs(successor.get());
            assertThat(state.current()).isFalse();
            assertThat(state.run().getId()).isEqualTo(501L);
        });
        assertThat(savedStates.get(1)).satisfies(state -> {
            assertThat(state.run()).isSameAs(preceding);
            assertThat(state.current()).isFalse();
            assertThat(state.supersededBy()).isSameAs(successor.get());
        });
        assertThat(savedStates.get(2)).satisfies(state -> {
            assertThat(state.run()).isSameAs(successor.get());
            assertThat(state.current()).isTrue();
        });
        assertThat(successor.get()).satisfies(run -> {
            assertThat(run.getPatientProfile()).isSameAs(patient);
            assertThat(run.getSourceType()).isEqualTo(RedFlagSourceType.SYMPTOM_CHECK_IN);
            assertThat(run.getSourceId()).isEqualTo(SYMPTOM_ID);
            assertThat(run.getSourceOperation()).isEqualTo(RedFlagSourceOperation.UPSERT);
            assertThat(run.getEvaluatedAt()).isEqualTo(NOW);
            assertThat(run.getOverallSeverity()).isEqualTo(RedFlagSeverity.EMERGENCY);
        });

        var eventCaptor = org.mockito.ArgumentCaptor.forClass(RedFlagTriggerEvent.class);
        verify(events, times(2)).saveAndFlush(eventCaptor.capture());
        assertThat(eventCaptor.getAllValues()).satisfiesExactly(
                event -> assertEvent(event, successor.get(), version101, group201,
                        RedFlagSeverity.ROUTINE_REVIEW, "{\"match\":1}"),
                event -> assertEvent(event, successor.get(), version102, group202,
                        RedFlagSeverity.EMERGENCY, "{\"match\":2}"));
        verify(clock, times(1)).instant();

        InOrder order = inOrder(catalog, resolver, engine, serializer, clock, runs, entityManager, events);
        order.verify(catalog).activeFor(RedFlagSourceType.SYMPTOM_CHECK_IN);
        order.verify(resolver).forSymptom(symptom);
        order.verify(engine).evaluate(definitions, input);
        order.verify(serializer).serialize(routine.matchedFacts());
        order.verify(serializer).serialize(emergency.matchedFacts());
        order.verify(clock).instant();
        order.verify(runs).saveAndFlush(same(successor.get()));
        order.verify(runs).findCurrentForUpdate(RedFlagSourceType.SYMPTOM_CHECK_IN, SYMPTOM_ID);
        order.verify(runs).saveAndFlush(same(preceding));
        order.verify(entityManager).getReference(RedFlagRuleVersion.class, 101L);
        order.verify(entityManager).getReference(RedFlagRuleConditionGroup.class, 201L);
        order.verify(events).saveAndFlush(same(eventCaptor.getAllValues().get(0)));
        order.verify(entityManager).getReference(RedFlagRuleVersion.class, 102L);
        order.verify(entityManager).getReference(RedFlagRuleConditionGroup.class, 202L);
        order.verify(events).saveAndFlush(same(eventCaptor.getAllValues().get(1)));
        order.verify(runs).saveAndFlush(same(successor.get()));
    }

    @Test
    void persistsCurrentNoMatchLabRunWithNullSeverityAndNoEvents() {
        var input = input(RedFlagSourceType.LAB_RESULT_SET, LAB_ID, false);
        var definitions = List.<RedFlagRuleDefinition>of();
        when(catalog.activeFor(RedFlagSourceType.LAB_RESULT_SET)).thenReturn(definitions);
        when(resolver.forLab(lab)).thenReturn(input);
        when(engine.evaluate(definitions, input)).thenReturn(new RedFlagEvaluationResult(List.of(), null));
        when(runs.findCurrentForUpdate(RedFlagSourceType.LAB_RESULT_SET, LAB_ID)).thenReturn(Optional.empty());

        var savedStates = new ArrayList<RunState>();
        when(runs.saveAndFlush(any())).thenAnswer(invocation -> {
            var run = invocation.getArgument(0, RedFlagEvaluationRun.class);
            savedStates.add(new RunState(run, run.isCurrent(), run.getSupersededByRun()));
            return run;
        });

        service.evaluateLab(lab);

        assertThat(savedStates).hasSize(2);
        assertThat(savedStates.get(0).current()).isFalse();
        assertThat(savedStates.get(1).current()).isTrue();
        assertThat(savedStates.get(1).run()).satisfies(run -> {
            assertThat(run.getSourceOperation()).isEqualTo(RedFlagSourceOperation.UPSERT);
            assertThat(run.getOverallSeverity()).isNull();
            assertThat(run.getEvaluatedAt()).isEqualTo(NOW);
        });
        verifyNoInteractions(serializer, entityManager, events);
        verify(clock, times(1)).instant();
    }

    @Test
    void removalEvaluatesEmptyTriggerAndSupersedesThePrecedingRun() {
        var input = input(RedFlagSourceType.LAB_RESULT_SET, LAB_ID, true);
        var definitions = List.<RedFlagRuleDefinition>of();
        when(catalog.activeFor(RedFlagSourceType.LAB_RESULT_SET)).thenReturn(definitions);
        when(resolver.forLabRemoval(lab)).thenReturn(input);
        when(engine.evaluate(definitions, input)).thenReturn(new RedFlagEvaluationResult(List.of(), null));
        var preceding = RedFlagEvaluationRun.pending(
                patient, RedFlagSourceType.LAB_RESULT_SET, LAB_ID,
                RedFlagSourceOperation.UPSERT, NOW.minusSeconds(60), RedFlagSeverity.URGENT_REVIEW);
        preceding.markCurrent();
        when(runs.findCurrentForUpdate(RedFlagSourceType.LAB_RESULT_SET, LAB_ID))
                .thenReturn(Optional.of(preceding));
        when(runs.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.evaluateLabRemoval(lab);

        var runCaptor = org.mockito.ArgumentCaptor.forClass(RedFlagEvaluationRun.class);
        verify(runs, times(3)).saveAndFlush(runCaptor.capture());
        var removal = runCaptor.getAllValues().getFirst();
        assertThat(input.trigger().facts()).isEmpty();
        assertThat(removal.getSourceOperation()).isEqualTo(RedFlagSourceOperation.REMOVE);
        assertThat(removal.getOverallSeverity()).isNull();
        assertThat(removal.isCurrent()).isTrue();
        assertThat(preceding.isCurrent()).isFalse();
        assertThat(preceding.getSupersededByRun()).isSameAs(removal);
        verifyNoInteractions(serializer, entityManager, events);
    }

    @Test
    void rejectsUnpersistedSourceBeforeLoadingRulesOrResolvingFacts() {
        when(symptom.getId()).thenReturn(null);

        assertThatThrownBy(() -> service.evaluateSymptom(symptom))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoEvaluationInteractions();
    }

    @Test
    void rejectsUnpersistedPatientBeforeLoadingRulesOrResolvingFacts() {
        when(patient.getId()).thenReturn(null);

        assertThatThrownBy(() -> service.evaluateLab(lab))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoEvaluationInteractions();
    }

    @Test
    void propagatesCatalogFailureWithoutResolvingFacts() {
        var failure = new IllegalStateException("invalid catalogue");
        when(catalog.activeFor(RedFlagSourceType.SYMPTOM_CHECK_IN)).thenThrow(failure);

        assertThatThrownBy(() -> service.evaluateSymptom(symptom)).isSameAs(failure);

        verifyNoInteractions(resolver, engine, serializer, runs, events, entityManager, clock);
    }

    @Test
    void propagatesResolverFailureWithoutEvaluatingOrPersisting() {
        var failure = new IllegalStateException("resolution failed");
        when(catalog.activeFor(RedFlagSourceType.SYMPTOM_CHECK_IN)).thenReturn(List.of());
        when(resolver.forSymptom(symptom)).thenThrow(failure);

        assertThatThrownBy(() -> service.evaluateSymptom(symptom)).isSameAs(failure);

        verifyNoInteractions(engine, serializer, runs, events, entityManager, clock);
    }

    @Test
    void propagatesEngineFailureWithoutSerializingOrPersisting() {
        var failure = new IllegalStateException("evaluation failed");
        var input = input(RedFlagSourceType.SYMPTOM_CHECK_IN, SYMPTOM_ID, false);
        var definitions = List.<RedFlagRuleDefinition>of();
        when(catalog.activeFor(RedFlagSourceType.SYMPTOM_CHECK_IN)).thenReturn(definitions);
        when(resolver.forSymptom(symptom)).thenReturn(input);
        when(engine.evaluate(definitions, input)).thenThrow(failure);

        assertThatThrownBy(() -> service.evaluateSymptom(symptom)).isSameAs(failure);

        verifyNoInteractions(serializer, runs, events, entityManager, clock);
    }

    @Test
    void propagatesSerializerFailureBeforeChangingCurrentRunState() {
        var failure = new IllegalStateException("serialization failed");
        var input = input(RedFlagSourceType.SYMPTOM_CHECK_IN, SYMPTOM_ID, false);
        var match = match(101L, 201L, RedFlagSeverity.EMERGENCY, "symptom.abdominal_pain");
        var definitions = List.of(match.rule());
        when(catalog.activeFor(RedFlagSourceType.SYMPTOM_CHECK_IN)).thenReturn(definitions);
        when(resolver.forSymptom(symptom)).thenReturn(input);
        when(engine.evaluate(definitions, input))
                .thenReturn(new RedFlagEvaluationResult(List.of(match), RedFlagSeverity.EMERGENCY));
        when(serializer.serialize(match.matchedFacts())).thenThrow(failure);

        assertThatThrownBy(() -> service.evaluateSymptom(symptom)).isSameAs(failure);

        verifyNoInteractions(runs, events, entityManager, clock);
    }

    @Test
    void propagatesPendingRunPersistenceFailureWithoutTakingTheCurrentRunLock() {
        var failure = new IllegalStateException("run persistence failed");
        stubNoMatchSymptom();
        when(runs.saveAndFlush(any())).thenThrow(failure);

        assertThatThrownBy(() -> service.evaluateSymptom(symptom)).isSameAs(failure);

        verify(runs, never()).findCurrentForUpdate(any(), any());
        verifyNoInteractions(events, entityManager);
    }

    @Test
    void propagatesSupersessionFlushFailureBeforeCreatingEventsOrMarkingSuccessorCurrent() {
        var failure = new IllegalStateException("supersession flush failed");
        var input = input(RedFlagSourceType.SYMPTOM_CHECK_IN, SYMPTOM_ID, false);
        var match = match(101L, 201L, RedFlagSeverity.EMERGENCY, "symptom.abdominal_pain");
        var definitions = List.of(match.rule());
        when(catalog.activeFor(RedFlagSourceType.SYMPTOM_CHECK_IN)).thenReturn(definitions);
        when(resolver.forSymptom(symptom)).thenReturn(input);
        when(engine.evaluate(definitions, input))
                .thenReturn(new RedFlagEvaluationResult(List.of(match), RedFlagSeverity.EMERGENCY));
        when(serializer.serialize(match.matchedFacts())).thenReturn("{}");
        var preceding = RedFlagEvaluationRun.pending(
                patient, RedFlagSourceType.SYMPTOM_CHECK_IN, SYMPTOM_ID,
                RedFlagSourceOperation.UPSERT, NOW.minusSeconds(60), RedFlagSeverity.URGENT_REVIEW);
        preceding.markCurrent();
        when(runs.findCurrentForUpdate(RedFlagSourceType.SYMPTOM_CHECK_IN, SYMPTOM_ID))
                .thenReturn(Optional.of(preceding));
        var successor = new AtomicReference<RedFlagEvaluationRun>();
        when(runs.saveAndFlush(any()))
                .thenAnswer(invocation -> {
                    var run = invocation.getArgument(0, RedFlagEvaluationRun.class);
                    successor.set(run);
                    return run;
                })
                .thenThrow(failure);

        assertThatThrownBy(() -> service.evaluateSymptom(symptom)).isSameAs(failure);

        assertThat(successor.get().isCurrent()).isFalse();
        verifyNoInteractions(events, entityManager);
    }

    @Test
    void propagatesEventPersistenceFailureAndDoesNotPerformFinalRunFlush() {
        var failure = new IllegalStateException("event persistence failed");
        var input = input(RedFlagSourceType.SYMPTOM_CHECK_IN, SYMPTOM_ID, false);
        var match = match(101L, 201L, RedFlagSeverity.EMERGENCY, "symptom.abdominal_pain");
        var definitions = List.of(match.rule());
        when(catalog.activeFor(RedFlagSourceType.SYMPTOM_CHECK_IN)).thenReturn(definitions);
        when(resolver.forSymptom(symptom)).thenReturn(input);
        when(engine.evaluate(definitions, input))
                .thenReturn(new RedFlagEvaluationResult(List.of(match), RedFlagSeverity.EMERGENCY));
        when(serializer.serialize(match.matchedFacts())).thenReturn("{}");
        when(runs.findCurrentForUpdate(RedFlagSourceType.SYMPTOM_CHECK_IN, SYMPTOM_ID))
                .thenReturn(Optional.empty());
        when(runs.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(entityManager.getReference(RedFlagRuleVersion.class, 101L))
                .thenReturn(mock(RedFlagRuleVersion.class));
        when(entityManager.getReference(RedFlagRuleConditionGroup.class, 201L))
                .thenReturn(mock(RedFlagRuleConditionGroup.class));
        when(events.saveAndFlush(any())).thenThrow(failure);

        assertThatThrownBy(() -> service.evaluateSymptom(symptom)).isSameAs(failure);

        verify(runs, times(1)).saveAndFlush(any());
    }

    private void stubNoMatchSymptom() {
        var input = input(RedFlagSourceType.SYMPTOM_CHECK_IN, SYMPTOM_ID, false);
        var definitions = List.<RedFlagRuleDefinition>of();
        when(catalog.activeFor(RedFlagSourceType.SYMPTOM_CHECK_IN)).thenReturn(definitions);
        when(resolver.forSymptom(symptom)).thenReturn(input);
        when(engine.evaluate(definitions, input)).thenReturn(new RedFlagEvaluationResult(List.of(), null));
    }

    private void verifyNoEvaluationInteractions() {
        verifyNoInteractions(catalog, resolver, engine, serializer, runs, events, entityManager, clock);
    }

    private RedFlagEvaluationInput input(RedFlagSourceType sourceType, Long sourceId, boolean emptyTrigger) {
        var triggerFacts = emptyTrigger
                ? List.<RedFlagFact>of()
                : List.of(new RedFlagFact("trigger.fact", BigDecimal.ONE, null, "unit"));
        return new RedFlagEvaluationInput(
                new RedFlagFactSet(sourceType, sourceId, OBSERVED_ON, triggerFacts),
                new RedFlagFactSet(RedFlagSourceType.PATIENT_PROFILE, PATIENT_ID, OBSERVED_ON, List.of()),
                List.of());
    }

    private RedFlagRuleMatch match(
            Long versionId, Long groupId, RedFlagSeverity severity, String factKey) {
        var definition = new RedFlagRuleDefinition(
                versionId, "RULE_" + versionId, 1,
                RedFlagSourceType.SYMPTOM_CHECK_IN, severity, List.of());
        var fact = new RedFlagRuleMatch.MatchedFact(
                RedFlagSourceType.SYMPTOM_CHECK_IN, SYMPTOM_ID, OBSERVED_ON,
                new RedFlagFact(factKey, BigDecimal.ONE, null, "unit"));
        return new RedFlagRuleMatch(definition, groupId, "GROUP_" + groupId, List.of(fact));
    }

    private void assertEvent(
            RedFlagTriggerEvent event, RedFlagEvaluationRun run,
            RedFlagRuleVersion version, RedFlagRuleConditionGroup group,
            RedFlagSeverity severity, String snapshot) {
        assertThat(event.getEvaluationRun()).isSameAs(run);
        assertThat(event.getRuleVersion()).isSameAs(version);
        assertThat(event.getMatchedGroup()).isSameAs(group);
        assertThat(event.getSeverity()).isEqualTo(severity);
        assertThat(event.getTriggeredAt()).isEqualTo(NOW);
        assertThat(event.getMatchedInputs()).isEqualTo(snapshot);
    }

    private record RunState(
            RedFlagEvaluationRun run, boolean current, RedFlagEvaluationRun supersededBy) { }
}
