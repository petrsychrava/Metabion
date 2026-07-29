# Red-Flag Detection Foundation Implementation Plan

> **For Codex:** REQUIRED SUB-SKILL: Use `superpowers:executing-plans` task by task, `superpowers:test-driven-development` for each behavior change, and `superpowers:verification-before-completion` before claiming completion.

**Goal:** Add the MET-12 backend foundation that synchronously evaluates versioned symptom and laboratory red-flag rules, atomically records deterministic audit evidence, preserves superseded history, and exposes an authorized internal query boundary.

**Architecture:** A relational, migration-controlled rule catalogue maps to immutable Java definitions evaluated by a pure DNF engine. `SymptomTrackingService` and `LabResultService` call one transactional `RedFlagEvaluationService` after assembling the final persisted source state. Runs supersede earlier runs for the same source record; events retain rule version, selected group, severity, timestamp, and minimal deterministic JSON evidence. This phase adds no public endpoint, frontend, MCP tool, queue, retry worker, guidance, or notification.

**Tech Stack:** Java 25, Spring Boot 4.0.6, Spring Data JPA/Hibernate, PostgreSQL, Flyway, Jackson, JUnit 5, AssertJ, Mockito, Testcontainers, Gradle.

**Approved design:** `docs/superpowers/specs/2026-07-29-red-flag-detection-foundation-design.md`

## Global Constraints

- Preserve session authentication, CSRF, bearer/MCP scopes, and existing REST/MVC response contracts.
- Evaluate only structured facts. Never inspect notes or other free text.
- Use canonical lab values and stable questionnaire question/option keys.
- Treat version 1 as clinically approved for the application lifecycle; replace it only with higher numbered versions.
- Use exactly `DRAFT -> ACTIVE -> RETIRED`, forward-only, with no provisional/shadow state or automatic backfill.
- Evaluate inside the source write transaction. Resolution, evaluation, serialization, or persistence failure rolls back the source write.
- Never log facts, snapshots, lab values, symptom answers, patient identity, notes, session IDs, or health-bearing exception details.
- Persist only facts needed by the first matching group in configured sort order.
- Preserve unrelated worktree changes and add no dependencies.

## File Map

```text
src/main/resources/db/migration/
└── V21__red_flag_detection_foundation.sql                       # new

src/main/java/com/metabion/
├── domain/
│   ├── RedFlagSeverity.java, RedFlagRuleStatus.java              # new
│   ├── RedFlagSourceType.java, RedFlagSourceOperation.java       # new
│   ├── RedFlagComparisonOperator.java                            # new
│   ├── RedFlagRule.java, RedFlagRuleVersion.java                 # new
│   ├── RedFlagRuleConditionGroup.java, RedFlagRuleCondition.java # new
│   ├── RedFlagRuleTransition.java                                # new
│   └── RedFlagEvaluationRun.java, RedFlagTriggerEvent.java       # new
├── repository/
│   ├── RedFlagRuleVersionRepository.java                         # new
│   ├── RedFlagRuleTransitionRepository.java                      # new
│   ├── RedFlagEvaluationRunRepository.java                       # new
│   ├── RedFlagTriggerEventRepository.java                        # new
│   └── SymptomCheckInRepository.java                             # modify
├── dto/redflag/
│   ├── RedFlagEvaluationRunView.java                             # new
│   └── RedFlagTriggerEventView.java                              # new
└── service/
    ├── SymptomTrackingService.java, LabResultService.java        # modify
    └── redflag/
        ├── RedFlagFact.java, RedFlagFactSet.java                  # new
        ├── RedFlagEvaluationInput.java, RedFlagEvaluationResult.java
        ├── RedFlagRuleDefinition.java, RedFlagRuleMatch.java      # new
        ├── RedFlagFactRegistry.java, RedFlagRuleEngine.java       # new
        ├── RedFlagRuleCatalog.java, RedFlagFactResolver.java      # new
        ├── RedFlagMatchedInputSnapshot.java                       # new
        ├── RedFlagSnapshotSerializer.java                         # new
        ├── RedFlagEvaluationService.java                          # new
        └── RedFlagEventQueryService.java                          # new

src/test/java/com/metabion/
├── repository/
│   ├── RedFlagRuleRepositoryTest.java                            # new
│   └── RedFlagEvaluationRepositoryTest.java                      # new
└── service/
    ├── SymptomTrackingServiceTest.java                            # modify
    ├── SymptomTrackingServicePersistenceTest.java                 # modify
    ├── LabResultServiceTest.java                                  # modify
    ├── LabResultServicePersistenceTest.java                       # modify
    └── redflag/
        ├── RedFlagRuleEngineTest.java, RedFlagRuleCatalogTest.java
        ├── RedFlagFactResolverTest.java, RedFlagSnapshotSerializerTest.java
        ├── RedFlagEvaluationServiceTest.java
        ├── SymptomRedFlagIntegrationTest.java
        ├── LabRedFlagIntegrationTest.java
        └── RedFlagEventQueryServiceTest.java
```

---

### Task 1: Add the Versioned Rule Catalogue and Approved Seed

**Files:** create `V21__red_flag_detection_foundation.sql`; the five rule enums and five rule-configuration entities above; `RedFlagRuleVersionRepository.java`, `RedFlagRuleTransitionRepository.java`, and `RedFlagRuleRepositoryTest.java`.

`RedFlagSeverity` uses explicit precedence:

```java
public enum RedFlagSeverity {
    ROUTINE_REVIEW(1), URGENT_REVIEW(2), EMERGENCY(3);
    private final int priority;
    RedFlagSeverity(int priority) { this.priority = priority; }
    public int priority() { return priority; }
}
```

Persist these exact values:

```java
public enum RedFlagRuleStatus { DRAFT, ACTIVE, RETIRED }
public enum RedFlagSourceType { SYMPTOM_CHECK_IN, LAB_RESULT_SET, PATIENT_PROFILE }
public enum RedFlagSourceOperation { UPSERT, REMOVE }
public enum RedFlagComparisonOperator { EQ, GT, GTE, LT, LTE }
```

V21 creates `red_flag_rules`; `red_flag_rule_versions` with evidence, rationale, author/change summary, approval reference/time, and activation/retirement times; ordered `red_flag_rule_condition_groups` and `red_flag_rule_conditions`; append-only `red_flag_rule_transitions`; plus the run/event tables in Task 2. Add checks for every enum, ACTIVE approval metadata, non-negative lookback, unique version/group/condition ordering, one ACTIVE version per rule, run sources limited to SYMPTOM_CHECK_IN/LAB_RESULT_SET, and exactly one operand:

```sql
CHECK (
    (decimal_operand IS NOT NULL AND text_operand IS NULL)
 OR (decimal_operand IS NULL AND text_operand IS NOT NULL)
)
```

Map collections with `@OrderBy("sortOrder ASC, id ASC")` and expose no publication API. Repository contract:

```java
@EntityGraph(attributePaths = {"rule", "conditionGroups"})
@Query("""
       select distinct version from RedFlagRuleVersion version
       where version.status=:status and version.triggerSource=:source
       order by version.rule.stableKey, version.versionNumber
       """)
List<RedFlagRuleVersion> findByStatusAndTriggerSource(
        RedFlagRuleStatus status, RedFlagSourceType source);
```

Seed exactly 24 version-1 rules. Each receives `NULL -> DRAFT` and `DRAFT -> ACTIVE` transitions, `author_reference='MET-12'`, `approval_reference='MET-12 initial clinical baseline approved 2026-07-29'`, non-null approval/activation timestamps, rationale, change summary, and the relevant design evidence URL.

Conditions in one listed group are AND; semicolon-separated groups are OR:

| Rule / severity | Ordered groups |
|---|---|
| `SYM_SEVERE_ABDOMINAL_PAIN` / EMERGENCY | `symptom.abdominal_pain EQ severe` |
| `SYM_SIGNIFICANT_BLEEDING` / EMERGENCY | `symptom.blood_in_stool EQ significant` |
| `SYM_ACTIVE_FLARE` / URGENT_REVIEW | `symptom.flare_state EQ ACTIVE_FLARE` |
| `SYM_HIGH_STOOL_FREQUENCY` / URGENT_REVIEW | `symptom.stool_frequency GT 8` |
| `SYM_COMBINED_SEVERE_ACTIVITY` / URGENT_REVIEW | `stool_frequency GTE 6 + blood_in_stool EQ visible`; `stool_frequency GTE 6 + abdominal_pain EQ moderate`; `stool_frequency GTE 6 + general_wellbeing EQ very-unwell` |
| `SYM_SUSPECTED_FLARE` / ROUTINE_REVIEW | `symptom.flare_state EQ SUSPECTED_FLARE` |
| `SYM_MODERATE_DETERIORATION` / ROUTINE_REVIEW | `stool_frequency GTE 4 + stool_frequency LTE 5`; `blood_in_stool EQ visible`; `abdominal_pain EQ moderate`; `general_wellbeing EQ very-unwell` |
| `LAB_SODIUM_CRITICAL` / EMERGENCY | `lab.SODIUM LTE 120`; `lab.SODIUM GTE 160` |
| `LAB_POTASSIUM_CRITICAL` / EMERGENCY | `lab.POTASSIUM LTE 2.5`; `lab.POTASSIUM GTE 6.5` |
| `LAB_CRP_CRITICAL` / EMERGENCY | `lab.CRP GTE 300` |
| `LAB_CRP_HIGH` / URGENT_REVIEW | `lab.CRP GTE 100 + lab.CRP LT 300` |
| `LAB_CRP_SYMPTOM_CONTEXT` / URGENT_REVIEW | every group starts `lab.CRP GT 45 + lab.CRP LT 100`, then one 7-day symptom pattern: active flare; stool `GT 8`; stool `GTE 6` + visible blood; stool `GTE 6` + moderate pain; stool `GTE 6` + very-unwell; severe pain; significant blood |
| `LAB_HEMOGLOBIN_CRITICAL_LOW` / URGENT_REVIEW | `lab.HEMOGLOBIN LTE 70` |
| `LAB_MAGNESIUM_CRITICAL_LOW` / URGENT_REVIEW | `lab.MAGNESIUM LTE 0.40` |
| `LAB_UREA_CRITICAL_HIGH` / URGENT_REVIEW | `lab.UREA GTE 30` |
| `LAB_CREATININE_CRITICAL_HIGH` / URGENT_REVIEW | `lab.CREATININE GTE 354` |
| `LAB_TRANSAMINASE_CRITICAL_HIGH` / URGENT_REVIEW | `lab.ALT GTE 500`; `lab.AST GTE 500` |
| `LAB_ALBUMIN_CRITICAL_LOW` / URGENT_REVIEW | `lab.ALBUMIN LTE 10` |
| `LAB_CALPROTECTIN_HIGH` / URGENT_REVIEW | `lab.FECAL_CALPROTECTIN GT 250` |
| `LAB_CRP_ELEVATED` / ROUTINE_REVIEW | `lab.CRP GT 45 + lab.CRP LT 100` |
| `LAB_ALBUMIN_LOW` / ROUTINE_REVIEW | `lab.ALBUMIN GT 10 + lab.ALBUMIN LT 30` |
| `LAB_HEMOGLOBIN_LOW_MALE` / ROUTINE_REVIEW | `lab.HEMOGLOBIN GT 70 + lab.HEMOGLOBIN LTE 130 + patient.sex EQ MALE` |
| `LAB_HEMOGLOBIN_LOW_FEMALE` / ROUTINE_REVIEW | `lab.HEMOGLOBIN GT 70 + lab.HEMOGLOBIN LTE 120 + patient.sex EQ FEMALE` |
| `LAB_CALPROTECTIN_BORDERLINE` / ROUTINE_REVIEW | `lab.FECAL_CALPROTECTIN GTE 100 + lab.FECAL_CALPROTECTIN LTE 250` |

All CRP-context symptom conditions use source `SYMPTOM_CHECK_IN` and `lookback_days=7`. Profile and direct-trigger conditions use zero lookback.

- [ ] Write failing PostgreSQL tests asserting 24 keys, one approved ACTIVE version per key, 48 transitions, exact triggers/severities, and compound boundaries. Prove duplicate active versions/order, negative lookback, and both/neither operands violate constraints.
- [ ] Run `./gradlew test --tests 'com.metabion.repository.RedFlagRuleRepositoryTest'`; expect compile/migration failure.
- [ ] Implement V21, enums, entities, repositories, and seed; keep configuration immutable and migration-controlled.
- [ ] Rerun the focused test; expect PASS against PostgreSQL 16 with schema validation.
- [ ] Commit:

```bash
git add src/main/resources/db/migration/V21__red_flag_detection_foundation.sql \
  src/main/java/com/metabion/domain/RedFlag*.java \
  src/main/java/com/metabion/repository/RedFlagRuleVersionRepository.java \
  src/main/java/com/metabion/repository/RedFlagRuleTransitionRepository.java \
  src/test/java/com/metabion/repository/RedFlagRuleRepositoryTest.java
git commit -m "Add versioned red-flag rule catalog"
```

---

### Task 2: Persist Current and Superseded Evaluation Audit

**Files:** create `RedFlagEvaluationRun.java`, `RedFlagTriggerEvent.java`, `RedFlagEvaluationRunRepository.java`, `RedFlagTriggerEventRepository.java`, and `RedFlagEvaluationRepositoryTest.java`.

Runs store patient, source type/ID, operation, time, nullable highest severity, `is_current`, and nullable `superseded_by_run_id`. Events store run, immutable rule version, selected group, severity/time, and `matched_inputs TEXT`. Add `UNIQUE (evaluation_run_id, rule_version_id)` and a partial unique index on `(source_type, source_id) WHERE is_current`.

Use an initially non-current run so replacement does not collide with the current row:

```java
public static RedFlagEvaluationRun pending(
        PatientProfile patient, RedFlagSourceType sourceType, Long sourceId,
        RedFlagSourceOperation operation, Instant evaluatedAt,
        RedFlagSeverity overallSeverity) {
    var run = new RedFlagEvaluationRun();
    run.patientProfile = Objects.requireNonNull(patient);
    run.sourceType = Objects.requireNonNull(sourceType);
    run.sourceId = Objects.requireNonNull(sourceId);
    run.sourceOperation = Objects.requireNonNull(operation);
    run.evaluatedAt = Objects.requireNonNull(evaluatedAt);
    run.overallSeverity = overallSeverity;
    run.current = false;
    return run;
}

public void supersedeWith(RedFlagEvaluationRun successor) {
    if (!current || successor == this) throw new IllegalStateException("Invalid red-flag run supersession");
    current = false;
    supersededByRun = successor;
}

public void markCurrent() {
    if (supersededByRun != null) throw new IllegalStateException("Superseded run cannot become current");
    current = true;
}
```

Repository contracts:

```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("""
       select run from RedFlagEvaluationRun run
       where run.sourceType=:sourceType and run.sourceId=:sourceId and run.current=true
       """)
Optional<RedFlagEvaluationRun> findCurrentForUpdate(
        RedFlagSourceType sourceType, Long sourceId);

@EntityGraph(attributePaths = {
        "events", "events.ruleVersion", "events.ruleVersion.rule", "events.matchedGroup"
})
List<RedFlagEvaluationRun> findByPatientProfileIdAndCurrentTrueOrderByEvaluatedAtDescIdDesc(Long patientId);

@EntityGraph(attributePaths = {
        "events", "events.ruleVersion", "events.ruleVersion.rule", "events.matchedGroup"
})
List<RedFlagEvaluationRun> findByPatientProfileIdOrderByEvaluatedAtDescIdDesc(Long patientId);
```

- [ ] Write failing persistence tests for matching UPSERT followed by no-match REMOVE. Assert successor linkage, one current run, null no-match severity, full history, and immutable event references. Prove two current rows for one source are rejected.
- [ ] Run `./gradlew test --tests 'com.metabion.repository.RedFlagEvaluationRepositoryTest'`; expect failure.
- [ ] Implement ordered mappings/repositories with no cascading delete or delete API.
- [ ] Rerun the test; expect PASS, including partial uniqueness on PostgreSQL.
- [ ] Commit:

```bash
git add src/main/java/com/metabion/domain/RedFlagEvaluationRun.java \
  src/main/java/com/metabion/domain/RedFlagTriggerEvent.java \
  src/main/java/com/metabion/repository/RedFlagEvaluationRunRepository.java \
  src/main/java/com/metabion/repository/RedFlagTriggerEventRepository.java \
  src/test/java/com/metabion/repository/RedFlagEvaluationRepositoryTest.java
git commit -m "Persist red-flag evaluation history"
```

---

### Task 3: Implement the Pure Typed Rule Engine

**Files:** create `RedFlagFact.java`, `RedFlagFactSet.java`, `RedFlagEvaluationInput.java`, `RedFlagRuleDefinition.java`, `RedFlagRuleMatch.java`, `RedFlagEvaluationResult.java`, `RedFlagRuleEngine.java`, and `RedFlagRuleEngineTest.java` under `service/redflag`.

Core model:

```java
public record RedFlagFact(
        String key, BigDecimal decimalValue, String textValue, String unit) {
    public RedFlagFact {
        if ((decimalValue == null) == (textValue == null)) {
            throw new IllegalArgumentException("A red-flag fact must have exactly one value");
        }
    }
}

public record RedFlagFactSet(
        RedFlagSourceType sourceType, Long sourceId, LocalDate observedOn,
        List<RedFlagFact> facts) { }

public record RedFlagEvaluationInput(
        RedFlagFactSet trigger, RedFlagFactSet patientProfile,
        List<RedFlagFactSet> lookback) { }
```

Use these exact immutable definition/result shapes:

```java
public record RedFlagRuleDefinition(
        Long versionId, String ruleKey, int versionNumber,
        RedFlagSourceType triggerSource, RedFlagSeverity severity,
        List<Group> groups) {
    public record Group(
            Long id, String stableKey, int sortOrder,
            List<Condition> conditions) { }
    public record Condition(
            Long id, RedFlagSourceType sourceType, String factKey,
            RedFlagComparisonOperator operator, BigDecimal decimalOperand,
            String textOperand, int lookbackDays, int sortOrder) { }
}

public record RedFlagRuleMatch(
        RedFlagRuleDefinition rule, Long matchedGroupId,
        String matchedGroupKey, List<MatchedFact> matchedFacts) {
    public record MatchedFact(
            RedFlagSourceType sourceType, Long sourceId,
            LocalDate observedOn, RedFlagFact fact) { }
}

public record RedFlagEvaluationResult(
        List<RedFlagRuleMatch> matches,
        RedFlagSeverity overallSeverity) { }

public RedFlagEvaluationResult evaluate(
        List<RedFlagRuleDefinition> rules,
        RedFlagEvaluationInput input);
```

Algorithm:

1. Evaluate only rules matching the trigger source.
2. Inspect groups by sort order then ID and keep the first matching group.
3. Partition its conditions by `(sourceType, lookbackDays)`.
4. Trigger-source/zero-lookback conditions use the trigger fact set; PATIENT_PROFILE/zero-lookback uses the profile fact set.
5. Positive lookback candidates must fall in the inclusive `triggerDate-days` through `triggerDate` interval. Order candidates by observed date descending then source ID descending and use the first fact set satisfying the partition, so two qualifying historical check-ins produce deterministic evidence.
6. Every condition in a partition must match the same fact set, preventing stool frequency from one check-in combining with blood/pain/wellbeing from another.
7. Every partition must match for the group to match.
8. Deduplicate a fact used by two bounds such as `CRP >45` and `CRP <100`.
9. Return every rule match and use `RedFlagSeverity.priority()` for the highest severity. No match means an empty list and null severity.

- [ ] Write failing pure tests for text/decimal EQ, GT/GTE/LT/LTE, missing facts, OR/AND, first-group determinism, most-recent qualifying lookback selection, severity precedence, inclusive seven days/excluded eighth day, same-record correlation, bound deduplication, and no-match.
- [ ] Run `./gradlew test --tests 'com.metabion.service.redflag.RedFlagRuleEngineTest'`; expect compile failure.
- [ ] Implement the smallest evaluator with no Spring, repositories, Jackson, logging, or clock. Compare decimals with `BigDecimal.compareTo`.
- [ ] Rerun the focused test; expect PASS.
- [ ] Commit:

```bash
git add src/main/java/com/metabion/service/redflag/RedFlagFact.java \
  src/main/java/com/metabion/service/redflag/RedFlagFactSet.java \
  src/main/java/com/metabion/service/redflag/RedFlagEvaluationInput.java \
  src/main/java/com/metabion/service/redflag/RedFlagRuleDefinition.java \
  src/main/java/com/metabion/service/redflag/RedFlagRuleMatch.java \
  src/main/java/com/metabion/service/redflag/RedFlagEvaluationResult.java \
  src/main/java/com/metabion/service/redflag/RedFlagRuleEngine.java \
  src/test/java/com/metabion/service/redflag/RedFlagRuleEngineTest.java
git commit -m "Implement typed red-flag rule engine"
```

---

### Task 4: Load, Validate, Resolve, and Serialize Inputs

**Files:** create `RedFlagFactRegistry.java`, `RedFlagRuleCatalog.java`, `RedFlagFactResolver.java`, `RedFlagMatchedInputSnapshot.java`, `RedFlagSnapshotSerializer.java`; modify `SymptomCheckInRepository.java`; create their three matching tests.

Register only these facts:

| Source | Facts | Type / canonical unit |
|---|---|---|
| SYMPTOM_CHECK_IN | `symptom.flare_state`, `symptom.abdominal_pain`, `symptom.blood_in_stool`, `symptom.general_wellbeing` | text |
| SYMPTOM_CHECK_IN | `symptom.stool_frequency` | decimal / `count/day` |
| LAB_RESULT_SET | `lab.CRP`, `lab.HEMOGLOBIN`, `lab.ALBUMIN` | decimal / `mg/L`, `g/L`, `g/L` |
| LAB_RESULT_SET | `lab.SODIUM`, `lab.POTASSIUM`, `lab.MAGNESIUM`, `lab.UREA` | decimal / `mmol/L` |
| LAB_RESULT_SET | `lab.CREATININE` | decimal / `umol/L` |
| LAB_RESULT_SET | `lab.ALT`, `lab.AST` | decimal / `U/L` |
| LAB_RESULT_SET | `lab.FECAL_CALPROTECTIN` | decimal / `ug/g` |
| PATIENT_PROFILE | `patient.sex` | text |

Only symptom facts allow positive lookback. Profile facts never trigger and never allow lookback.

`RedFlagRuleCatalog.activeFor(source)` is `@Transactional(readOnly=true)` so it can map ordered conditions after the version/group entity graph is loaded. It maps entities to immutable definitions and rejects incomplete approval, missing/empty groups, empty conditions, unknown source/key, wrong operand type, non-EQ operators on text, unsupported/negative lookback, ambiguous order, or a missing/duplicate required seeded key. Positive lookback is valid only for a LAB_RESULT_SET-triggered rule inspecting SYMPTOM_CHECK_IN facts. Throw only `IllegalStateException("Active red-flag catalogue is invalid")`.

Add this eager context query:

```java
@EntityGraph(attributePaths = {"answers", "answers.question", "answers.option"})
@Query("""
       select distinct checkIn from SymptomCheckIn checkIn
       where checkIn.patientProfile.id=:patientId
         and checkIn.checkInDate between :from and :to
       order by checkIn.checkInDate desc, checkIn.id desc
       """)
List<SymptomCheckIn> findForRedFlagContext(
        Long patientId, LocalDate from, LocalDate to);
```

Resolver API:

```java
public RedFlagEvaluationInput forSymptom(SymptomCheckIn checkIn);
public RedFlagEvaluationInput forLab(LabResultSet resultSet);
public RedFlagEvaluationInput forLabRemoval(LabResultSet resultSet);
```

`forSymptom` maps flare and numeric/choice answers, never notes. `forLab` maps canonical values/units and loads symptoms from `collectionDate.minusDays(7)` through `collectionDate`. `forLabRemoval` returns an empty LAB_RESULT_SET trigger, profile context, and no lookback. Emit profile sex only for MALE/FEMALE.

Snapshot:

```java
public record RedFlagMatchedInputSnapshot(List<Fact> facts) {
    public record Fact(
            RedFlagSourceType sourceType, Long sourceId, String factKey,
            LocalDate observedOn, String decimalValue, String textValue,
            String unit) { }
}
```

`RedFlagSnapshotSerializer.serialize(List<RedFlagRuleMatch.MatchedFact>)` sorts by source type name, fact key, observed date, and source ID. It uses `stripTrailingZeros().toPlainString()` and an isolated Jackson mapper copied through `ObjectProvider<ObjectMapper>`, following `LabAuditService`. Exact single-fact output:

```json
{"facts":[{"sourceType":"LAB_RESULT_SET","sourceId":91,"factKey":"lab.CRP","observedOn":"2026-07-28","decimalValue":"312","textValue":null,"unit":"mg/L"}]}
```

- [ ] Write failing catalogue tests for every validation and stable mapping order; resolver tests for stable keys, canonical values, exact query bounds, missing/non-binary sex, and excluded notes; serializer tests for exact sorted JSON.
- [ ] Run:

```bash
./gradlew test \
  --tests 'com.metabion.service.redflag.RedFlagRuleCatalogTest' \
  --tests 'com.metabion.service.redflag.RedFlagFactResolverTest' \
  --tests 'com.metabion.service.redflag.RedFlagSnapshotSerializerTest'
```

Expect failure.

- [ ] Implement registry, catalog, query, resolver, and serializer without hand-built JSON or new dependencies.
- [ ] Rerun all three tests; expect PASS and no free-text/user-data leakage.
- [ ] Commit:

```bash
git add src/main/java/com/metabion/repository/SymptomCheckInRepository.java \
  src/main/java/com/metabion/service/redflag/RedFlagFactRegistry.java \
  src/main/java/com/metabion/service/redflag/RedFlagRuleCatalog.java \
  src/main/java/com/metabion/service/redflag/RedFlagFactResolver.java \
  src/main/java/com/metabion/service/redflag/RedFlagMatchedInputSnapshot.java \
  src/main/java/com/metabion/service/redflag/RedFlagSnapshotSerializer.java \
  src/test/java/com/metabion/service/redflag/RedFlagRuleCatalogTest.java \
  src/test/java/com/metabion/service/redflag/RedFlagFactResolverTest.java \
  src/test/java/com/metabion/service/redflag/RedFlagSnapshotSerializerTest.java
git commit -m "Resolve and validate red-flag facts"
```

---

### Task 5: Orchestrate Atomic Evaluation and Supersession

**Files:** create `src/main/java/com/metabion/service/redflag/RedFlagEvaluationService.java` and `RedFlagEvaluationServiceTest.java`.

Public API:

```java
public void evaluateSymptom(SymptomCheckIn checkIn);
public void evaluateLab(LabResultSet resultSet);
public void evaluateLabRemoval(LabResultSet resultSet);
```

All methods delegate to a private operation taking `RedFlagSourceOperation` and a resolved input:

1. Require persisted source and patient IDs.
2. Load and validate active definitions for the trigger source.
3. Resolve and evaluate facts.
4. Serialize each match before changing current-run state.
5. Save the new run as `current=false` and flush for its ID.
6. Lock the preceding current run for `(sourceType, sourceId)`.
7. If present, supersede it with the new run and flush to free the partial-index slot.
8. Mark the new run current.
9. Save one event per match using `entityManager.getReference(RedFlagRuleVersion.class, match.rule().versionId())` and `entityManager.getReference(RedFlagRuleConditionGroup.class, match.matchedGroupId())`. This preserves the exact evaluated version/group without re-querying whichever version is currently active.
10. Flush runs/events so persistence failure occurs in the source transaction.

Use one `Instant.now(clock)` for the run and all events. REMOVE evaluates an empty trigger, creates a successful no-match run, and supersedes the previous run.

- [ ] Write failing mock-based tests for call ordering, two matches/highest severity, no-match/null severity, supersession, exact version/group linkage, one timestamp, REMOVE, and propagation of resolver/engine/serializer/repository failures.
- [ ] Run `./gradlew test --tests 'com.metabion.service.redflag.RedFlagEvaluationServiceTest'`; expect compile failure.
- [ ] Implement as `@Transactional`. Do not catch/downgrade failures, enqueue retries, or log facts.
- [ ] Rerun the focused test; expect PASS.
- [ ] Commit:

```bash
git add src/main/java/com/metabion/service/redflag/RedFlagEvaluationService.java \
  src/test/java/com/metabion/service/redflag/RedFlagEvaluationServiceTest.java
git commit -m "Orchestrate red-flag evaluations"
```

---

### Task 6: Integrate Symptom Check-In Writes

**Files:** modify `SymptomTrackingService.java`, `SymptomTrackingServiceTest.java`, `SymptomTrackingServicePersistenceTest.java`; create `service/redflag/SymptomRedFlagIntegrationTest.java`.

Inject `RedFlagEvaluationService`. At the end of `saveForCurrentPatient`, after answer replacement and total-score calculation:

```java
var saved = checkIns.saveAndFlush(checkIn);
redFlags.evaluateSymptom(saved);
return assembler.checkIn(saved);
```

Create and same-day update use the same call. Do not change `SymptomCheckInResponse`.

- [ ] In `SymptomTrackingServiceTest`, verify evaluation once on create/update and never for invalid requests.
- [ ] In PostgreSQL `SymptomRedFlagIntegrationTest`, use the real seed/resolver/engine/persistence and cover:
  - severe pain and significant blood individually produce EMERGENCY;
  - ACTIVE_FLARE produces URGENT_REVIEW;
  - stool 9 is urgent while 8 does not match high-frequency;
  - stool 6 plus visible blood, moderate pain, or very-unwell is urgent;
  - SUSPECTED_FLARE and each moderate-deterioration alternative are routine;
  - stool 4 and 5 match routine, while 3 and 6 do not match that rule;
  - multiple matches produce multiple events and highest run severity;
  - snapshot contains only the selected group's matched facts;
  - same-day update supersedes the run and preserves old events.
- [ ] In `SymptomTrackingServicePersistenceTest`, use a Spring-managed service proxy, make `RedFlagEvaluationService` throw `IllegalStateException("Red-flag evaluation failed")`, and assert check-in and answers roll back.
- [ ] Run:

```bash
./gradlew test \
  --tests 'com.metabion.service.SymptomTrackingServiceTest' \
  --tests 'com.metabion.service.SymptomTrackingServicePersistenceTest' \
  --tests 'com.metabion.service.redflag.SymptomRedFlagIntegrationTest'
```

Expect failure before integration, then PASS after injecting/calling the evaluator and updating direct constructors/imports.

- [ ] Commit:

```bash
git add src/main/java/com/metabion/service/SymptomTrackingService.java \
  src/test/java/com/metabion/service/SymptomTrackingServiceTest.java \
  src/test/java/com/metabion/service/SymptomTrackingServicePersistenceTest.java \
  src/test/java/com/metabion/service/redflag/SymptomRedFlagIntegrationTest.java
git commit -m "Evaluate red flags on symptom writes"
```

---

### Task 7: Integrate Laboratory Create, Update, and Removal

**Files:** modify `LabResultService.java`, `LabResultServiceTest.java`, `LabResultServicePersistenceTest.java`; create `service/redflag/LabRedFlagIntegrationTest.java`.

Inject `RedFlagEvaluationService` and call it after final canonical results are flushed and existing lab audit persistence is requested:

```java
// create
var saved = resultSets.saveAndFlush(set);
audit.recordCreate(saved, actor, now);
redFlags.evaluateLab(saved);

// update, after flush and audit.recordUpdate(set, before, actor, now)
redFlags.evaluateLab(set);

// remove, after flush and audit.recordRemoval(set, before, actor, now)
redFlags.evaluateLabRemoval(set);
```

Do not evaluate reported values, removed results, reference ranges, or notes.

- [ ] In `LabResultServiceTest`, verify the correct evaluation method once for create/update/remove and never for rejected writes.
- [ ] In PostgreSQL `LabRedFlagIntegrationTest`, cover these exact canonical boundaries:

| Fact | Assertions |
|---|---|
| Sodium | 120 emergency; 120.01 none; 159.99 none; 160 emergency |
| Potassium | 2.50 emergency; 2.51 none; 6.49 none; 6.50 emergency |
| CRP | 45 none; 45.01 routine without context; 99.99 routine; 100 urgent; 299.99 urgent; 300 emergency |
| Haemoglobin | 70 urgent for every sex; male 70.01-130 routine/130.01 none; female 70.01-120 routine/120.01 none; missing/intersex/prefer-not skips only routine sex rules |
| Magnesium | 0.40 urgent; 0.41 none |
| Urea | 29.99 none; 30 urgent |
| Creatinine | 353.99 none; 354 urgent |
| ALT/AST | 499.99 none; either at 500 urgent |
| Albumin | 10 urgent; 10.01-29.99 routine; 30 none |
| Calprotectin | 99.99 none; 100-250 routine; 250.01 urgent |

- [ ] Also prove `10 mg/dL` CRP evaluates as canonical `100 mg/L`; CRP `45.01-99.99` plus qualifying symptoms exactly seven days earlier is urgent and retains the routine event; eight days does not qualify; active flare, stool >8, each correlated stool>=6 compound, severe pain, and significant bleeding qualify; stool from one lookback check-in cannot combine with blood/pain/wellbeing from another; later symptom writes do not touch lab-source runs; update supersedes; removal produces current no-match REMOVE history.
- [ ] Extend `LabResultServicePersistenceTest` so red-flag event/evaluation failure rolls back set/results and the existing lab audit. Retain the current lab-audit rollback test.
- [ ] Run:

```bash
./gradlew test \
  --tests 'com.metabion.service.LabResultServiceTest' \
  --tests 'com.metabion.service.LabResultServicePersistenceTest' \
  --tests 'com.metabion.service.redflag.LabRedFlagIntegrationTest'
```

Expect failure before integration, then PASS after adding the calls and updating constructors/imports.

- [ ] Commit:

```bash
git add src/main/java/com/metabion/service/LabResultService.java \
  src/test/java/com/metabion/service/LabResultServiceTest.java \
  src/test/java/com/metabion/service/LabResultServicePersistenceTest.java \
  src/test/java/com/metabion/service/redflag/LabRedFlagIntegrationTest.java
git commit -m "Evaluate red flags on laboratory writes"
```

---

### Task 8: Add the Authorized Internal Query Boundary

**Files:** create `dto/redflag/RedFlagEvaluationRunView.java`, `RedFlagTriggerEventView.java`, `service/redflag/RedFlagEventQueryService.java`, and `RedFlagEventQueryServiceTest.java`.

DTOs:

```java
public record RedFlagTriggerEventView(
        Long id, String ruleKey, int ruleVersion, String matchedGroupKey,
        RedFlagSeverity severity, Instant triggeredAt, String matchedInputs) { }

public record RedFlagEvaluationRunView(
        Long id, RedFlagSourceType sourceType, Long sourceId,
        RedFlagSourceOperation sourceOperation, Instant evaluatedAt,
        RedFlagSeverity overallSeverity, boolean current,
        Long supersededByRunId, List<RedFlagTriggerEventView> events) { }
```

Service API:

```java
public List<RedFlagEvaluationRunView> currentForCurrentPatient(Authentication authentication);
public List<RedFlagEvaluationRunView> historyForCurrentPatient(Authentication authentication);
public Optional<RedFlagSeverity> currentHighestForCurrentPatient(Authentication authentication);

public List<RedFlagEvaluationRunView> currentForClinicalPatient(
        Authentication authentication, Long patientProfileId);
public List<RedFlagEvaluationRunView> historyForClinicalPatient(
        Authentication authentication, Long patientProfileId);
public Optional<RedFlagSeverity> currentHighestForClinicalPatient(
        Authentication authentication, Long patientProfileId);
```

Patient methods require a PATIENT and resolve the profile from the authenticated user. Clinical methods require NUTRITION_SPECIALIST, PHYSICIAN, or ADMIN; non-admin staff must pass `accessControl.canViewPatientClinicalData(authentication, patientProfileId)`. Coordinators and unassigned staff are denied. Use existing UNAUTHORIZED/FORBIDDEN `ResponseStatusException` patterns.

Map snapshots as opaque JSON text; do not parse, enrich, or log them. Add no controller, route, menu, SPA component, MCP tool, or scope.

- [ ] Write failing tests for own-patient current/history/highest; assigned specialist/physician; admin; unassigned/coordinator/unauthenticated denial; patient methods deriving identity without accepting a patient ID; current excluding superseded; history including it; stable event order and exact version/group; null highest for no current match.
- [ ] Run `./gradlew test --tests 'com.metabion.service.redflag.RedFlagEventQueryServiceTest'`; expect compile failure.
- [ ] Implement as `@Transactional(readOnly=true)` and reuse `AccessControlService` rather than assignment repositories.
- [ ] Rerun the focused test; expect PASS.
- [ ] Commit:

```bash
git add src/main/java/com/metabion/dto/redflag/RedFlagEvaluationRunView.java \
  src/main/java/com/metabion/dto/redflag/RedFlagTriggerEventView.java \
  src/main/java/com/metabion/service/redflag/RedFlagEventQueryService.java \
  src/test/java/com/metabion/service/redflag/RedFlagEventQueryServiceTest.java
git commit -m "Add red-flag event query boundary"
```

---

### Task 9: Verify MET-12 Foundation Scope and Regression Safety

**Files:** verify everything from Tasks 1-8, the approved design, and that `SecurityConfig.java` remains unchanged.

- [ ] Run the focused suite:

```bash
./gradlew test \
  --tests 'com.metabion.repository.RedFlagRuleRepositoryTest' \
  --tests 'com.metabion.repository.RedFlagEvaluationRepositoryTest' \
  --tests 'com.metabion.service.redflag.*' \
  --tests 'com.metabion.service.SymptomTrackingServiceTest' \
  --tests 'com.metabion.service.SymptomTrackingServicePersistenceTest' \
  --tests 'com.metabion.service.LabResultServiceTest' \
  --tests 'com.metabion.service.LabResultServicePersistenceTest'
```

Expected: PASS with PostgreSQL Testcontainers tests executed rather than skipped.

- [ ] Run existing API/security regressions:

```bash
./gradlew test \
  --tests 'com.metabion.controller.api.SymptomTrackingControllerTest' \
  --tests 'com.metabion.controller.api.ClinicalSymptomTrackingControllerTest' \
  --tests 'com.metabion.controller.api.LabResultControllerTest' \
  --tests 'com.metabion.controller.api.ClinicalLabResultControllerTest' \
  --tests 'com.metabion.controller.api.GlobalExceptionHandlerTest'
```

Expected: PASS with unchanged DTOs, role rules, and generic errors.

- [ ] Run `./gradlew test`; expect `BUILD SUCCESSFUL` with Jacoco finalization.
- [ ] Run `./gradlew build`; expect `BUILD SUCCESSFUL` including packaging.
- [ ] Inspect hygiene:

```bash
git diff --check
git status --short
git diff -- src/main/resources/db/migration/V21__red_flag_detection_foundation.sql \
  src/main/java/com/metabion/service/redflag \
  src/main/java/com/metabion/service/SymptomTrackingService.java \
  src/main/java/com/metabion/service/LabResultService.java \
  src/main/java/com/metabion/config/SecurityConfig.java
```

Confirm V21 is next and non-destructive; exactly 24 active rules and all boundaries exist; no notes/free text/reported values/localized labels are evaluated; snapshots contain only stable matched facts/canonical units; logs/errors expose no health facts; source/audit rollback is atomic; one current run and immutable history are enforced; no endpoint, guidance, notification, messaging, retry/outbox, provisional/shadow state, backfill, or security change appeared; unrelated worktree changes remain untouched.

- [ ] Correct findings test-first. Add a focused regression, make the smallest fix, rerun affected checks and all steps above, then commit `Harden red-flag detection foundation`. Skip this commit if no correction is needed.

## Completion Checklist

- [ ] V21 creates constrained versioned rule configuration and immutable evaluation audit storage.
- [ ] The approved version-1 catalogue contains exactly seven symptom and seventeen laboratory rules.
- [ ] The pure evaluator implements deterministic OR-group/AND-condition semantics and same-record lookback correlation.
- [ ] Symptom/lab create and update evaluate synchronously; lab removal creates a no-match current run.
- [ ] Every event retains rule identity/version, selected group, severity, timestamp, and minimal deterministic facts.
- [ ] Updates/removal preserve history and expose only the latest run as current.
- [ ] CRP boundaries at 45, 100, and 300 mg/L and all agreed thresholds are covered exactly.
- [ ] The seven-day CRP symptom window is inclusive and later symptom writes do not retrigger lab rules.
- [ ] Evaluation failure rolls back the symptom/lab write and existing lab audit.
- [ ] The internal query boundary enforces patient ownership and existing clinical assignments.
- [ ] Public response contracts and security configuration remain unchanged.
- [ ] Focused tests, full tests, full build, and diff checks pass.
