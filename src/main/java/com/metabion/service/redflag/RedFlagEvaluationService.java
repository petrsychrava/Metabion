package com.metabion.service.redflag;

import com.metabion.domain.LabResultSet;
import com.metabion.domain.PatientProfile;
import com.metabion.domain.RedFlagEvaluationRun;
import com.metabion.domain.RedFlagRuleConditionGroup;
import com.metabion.domain.RedFlagRuleVersion;
import com.metabion.domain.RedFlagSourceOperation;
import com.metabion.domain.RedFlagSourceType;
import com.metabion.domain.RedFlagTriggerEvent;
import com.metabion.domain.SymptomCheckIn;
import com.metabion.repository.RedFlagEvaluationRunRepository;
import com.metabion.repository.RedFlagTriggerEventRepository;
import jakarta.persistence.EntityManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

@Service
@Transactional
public class RedFlagEvaluationService {

    private final RedFlagRuleCatalog catalog;
    private final RedFlagFactResolver resolver;
    private final RedFlagRuleEngine engine;
    private final RedFlagSnapshotSerializer serializer;
    private final RedFlagEvaluationRunRepository runs;
    private final RedFlagTriggerEventRepository events;
    private final EntityManager entityManager;
    private final Clock clock;

    @Autowired
    public RedFlagEvaluationService(
            RedFlagRuleCatalog catalog,
            RedFlagFactResolver resolver,
            RedFlagSnapshotSerializer serializer,
            RedFlagEvaluationRunRepository runs,
            RedFlagTriggerEventRepository events,
            EntityManager entityManager,
            Clock clock) {
        this(catalog, resolver, new RedFlagRuleEngine(), serializer,
                runs, events, entityManager, clock);
    }

    RedFlagEvaluationService(
            RedFlagRuleCatalog catalog,
            RedFlagFactResolver resolver,
            RedFlagRuleEngine engine,
            RedFlagSnapshotSerializer serializer,
            RedFlagEvaluationRunRepository runs,
            RedFlagTriggerEventRepository events,
            EntityManager entityManager,
            Clock clock) {
        this.catalog = catalog;
        this.resolver = resolver;
        this.engine = engine;
        this.serializer = serializer;
        this.runs = runs;
        this.events = events;
        this.entityManager = entityManager;
        this.clock = clock;
    }

    public RedFlagEvaluationOutcome evaluateSymptom(SymptomCheckIn checkIn) {
        var patient = requirePersisted(checkIn == null ? null : checkIn.getId(),
                checkIn == null ? null : checkIn.getPatientProfile());
        var definitions = catalog.activeFor(RedFlagSourceType.SYMPTOM_CHECK_IN);
        var input = resolver.forSymptom(checkIn);
        return evaluate(RedFlagSourceOperation.UPSERT, patient, definitions, input);
    }

    public RedFlagEvaluationOutcome evaluateLab(LabResultSet resultSet) {
        var patient = requirePersisted(resultSet == null ? null : resultSet.getId(),
                resultSet == null ? null : resultSet.getPatientProfile());
        var definitions = catalog.activeFor(RedFlagSourceType.LAB_RESULT_SET);
        var input = resolver.forLab(resultSet);
        return evaluate(RedFlagSourceOperation.UPSERT, patient, definitions, input);
    }

    public RedFlagEvaluationOutcome evaluateLabRemoval(LabResultSet resultSet) {
        var patient = requirePersisted(resultSet == null ? null : resultSet.getId(),
                resultSet == null ? null : resultSet.getPatientProfile());
        var definitions = catalog.activeFor(RedFlagSourceType.LAB_RESULT_SET);
        var input = resolver.forLabRemoval(resultSet);
        return evaluate(RedFlagSourceOperation.REMOVE, patient, definitions, input);
    }

    private RedFlagEvaluationOutcome evaluate(
            RedFlagSourceOperation operation,
            PatientProfile patient,
            List<RedFlagRuleDefinition> definitions,
            RedFlagEvaluationInput input) {
        var result = engine.evaluate(definitions, input);
        if (operation == RedFlagSourceOperation.REMOVE) {
            result = new RedFlagEvaluationResult(List.of(), null);
        }
        var serializedMatches = result.matches().stream()
                .map(match -> new SerializedMatch(match, serializer.serialize(match.matchedFacts())))
                .toList();
        var evaluatedAt = Instant.now(clock);
        var trigger = input.trigger();
        var run = RedFlagEvaluationRun.pending(
                patient, trigger.sourceType(), trigger.sourceId(), operation,
                evaluatedAt, result.overallSeverity());

        runs.saveAndFlush(run);
        var precedingKeys = runs.findCurrentForUpdate(trigger.sourceType(), trigger.sourceId()).map(preceding -> {
            var keys = events.findRuleKeysByEvaluationRunId(preceding.getId());
            preceding.supersedeWith(run);
            runs.saveAndFlush(preceding);
            return keys;
        }).orElseGet(List::of);
        run.markCurrent();

        var persistedFlags = serializedMatches.stream().map(serialized -> {
            var match = serialized.match();
            var version = entityManager.getReference(
                    RedFlagRuleVersion.class, match.rule().versionId());
            var group = entityManager.getReference(
                    RedFlagRuleConditionGroup.class, match.matchedGroupId());
            var event = events.saveAndFlush(new RedFlagTriggerEvent(
                    run, version, group, match.rule().severity(),
                    evaluatedAt, serialized.snapshot()));
            return new RedFlagEvaluationOutcome.Flag(
                    event.getId(),
                    match.rule().ruleKey(),
                    match.rule().severity(),
                    evaluatedAt,
                    trigger.sourceType(),
                    trigger.sourceId());
        }).toList();
        var currentKeys = persistedFlags.stream()
                .map(RedFlagEvaluationOutcome.Flag::ruleKey)
                .collect(Collectors.toSet());
        var clearedKeys = precedingKeys.stream()
                .filter(key -> !currentKeys.contains(key))
                .distinct()
                .sorted()
                .toList();
        runs.saveAndFlush(run);
        return new RedFlagEvaluationOutcome(
                result.overallSeverity(), persistedFlags, clearedKeys);
    }

    private PatientProfile requirePersisted(Long sourceId, PatientProfile patient) {
        if (sourceId == null || patient == null || patient.getId() == null) {
            throw new IllegalArgumentException("Red-flag source and patient must be persisted");
        }
        return patient;
    }

    private record SerializedMatch(RedFlagRuleMatch match, String snapshot) { }
}
