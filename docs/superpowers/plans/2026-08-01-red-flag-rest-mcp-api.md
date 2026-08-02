# Red-Flag REST and MCP API Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (- [ ]) syntax for tracking.

**Goal:** Expose authorized current and historical red flags through patient and clinical REST APIs and patient MCP tools, including exact red-flag outcomes from MCP symptom and laboratory writes.

**Architecture:** Keep RedFlagEventQueryService as the authorization boundary, query trigger events directly with cursor pagination, and map one internal event read model into restricted patient/MCP or clinically relevant responses. Extend synchronous evaluation to return the outcome it persists so MCP writes can disclose flags without re-evaluation or a follow-up query, while ordinary REST write contracts remain unchanged.

**Tech Stack:** Java 25, Spring Boot 4.0.6, Spring Web MVC, Spring Security, Spring Data JPA, Spring AI MCP 2.0.0, Jackson, PostgreSQL/Testcontainers, JUnit 5, Mockito, AssertJ, Gradle.

## Global Constraints

- Base behavior on docs/superpowers/specs/2026-07-29-red-flag-detection-foundation-design.md and docs/superpowers/specs/2026-08-01-red-flag-rest-mcp-api-design.md.
- Preserve session authentication and CSRF behavior for ordinary REST; preserve bearer resource, expiry, revocation, and localhost restrictions for MCP.
- Patient and MCP responses contain no rule version or matched inputs; clinical responses add only ruleVersion and structured matchedInputs.
- No public response contains evaluationRunId, sourceOperation, or matchedGroupKey.
- Do not expose successful no-match evaluation runs.
- Do not add safety guidance, notifications, acknowledgement/resolution state, rule management, frontend behavior, dependencies, database tables, or columns.
- Keep ordinary symptom and laboratory REST write response schemas unchanged.
- Use patient:red-flags:read only for MCP read tools; triggering MCP writes disclose their simplified outcome under their existing write scopes.
- History accepts optional inclusive patient-local from/to dates, optional severity, opaque cursor, and size default 25/max 100.
- Never log red-flag facts, snapshots, symptom answers, laboratory values, tokens, credentials, or session identifiers.
- Preserve unrelated worktree changes.

---

## File Responsibility Map

New query contracts live in src/main/java/com/metabion/dto/redflag/. MCP-only write envelopes live in src/main/java/com/metabion/dto/mcp/. Cursor decoding, internal read models, projections, and query orchestration stay in service/redflag/. REST controllers remain thin; PatientAppFacade remains the MCP-facing application boundary.

REST and MCP belong in one plan because they use the same event query model and patient projection. MCP write disclosure also depends on the same synchronous evaluation transaction.

### Task 1: Return the persisted evaluation outcome

**Files:**
- Create: src/main/java/com/metabion/service/redflag/RedFlagEvaluationOutcome.java
- Modify: src/main/java/com/metabion/repository/RedFlagTriggerEventRepository.java:6-9
- Modify: src/main/java/com/metabion/service/redflag/RedFlagEvaluationService.java:68-128
- Test: src/test/java/com/metabion/service/redflag/RedFlagEvaluationServiceTest.java

**Interfaces:**
- Consumes: existing RedFlagEvaluationResult, RedFlagRuleMatch, and source-record supersession.
- Produces:
  - RedFlagEvaluationOutcome evaluateSymptom(SymptomCheckIn checkIn)
  - RedFlagEvaluationOutcome evaluateLab(LabResultSet resultSet)
  - RedFlagEvaluationOutcome evaluateLabRemoval(LabResultSet resultSet)
  - List<String> findRuleKeysByEvaluationRunId(Long runId)

- [ ] **Step 1: Write failing outcome tests**

Add tests proving returned flags use persisted event IDs, continuing rule keys are not cleared, cleared keys are sorted, and no-match/removal returns an explicit result.

~~~java
@Test
void returnsPersistedFlagsAndOnlyGenuinelyClearedRules() {
    ReflectionTestUtils.setField(preceding, "id", 500L);
    when(runs.findCurrentForUpdate(
            RedFlagSourceType.SYMPTOM_CHECK_IN, SYMPTOM_ID))
            .thenReturn(Optional.of(preceding));
    when(events.findRuleKeysByEvaluationRunId(500L))
            .thenReturn(List.of("RULE_100", "RULE_101"));
    var nextId = new AtomicLong(700L);
    when(events.saveAndFlush(any())).thenAnswer(invocation -> {
        var event = invocation.getArgument(0, RedFlagTriggerEvent.class);
        ReflectionTestUtils.setField(event, "id", nextId.incrementAndGet());
        return event;
    });

    var outcome = service.evaluateSymptom(symptom);

    assertThat(outcome.highestSeverity()).isEqualTo(RedFlagSeverity.EMERGENCY);
    assertThat(outcome.currentFlags())
            .extracting(RedFlagEvaluationOutcome.Flag::eventId,
                    RedFlagEvaluationOutcome.Flag::ruleKey)
            .containsExactly(
                    tuple(701L, "RULE_101"),
                    tuple(702L, "RULE_102"));
    assertThat(outcome.clearedRuleKeys()).containsExactly("RULE_100");
}

@Test
void removalReturnsPrecedingRulesAsClearedAndNoCurrentFlags() {
    when(events.findRuleKeysByEvaluationRunId(500L))
            .thenReturn(List.of("LAB_CRP_HIGH", "LAB_CRP_ELEVATED"));

    var outcome = service.evaluateLabRemoval(lab);

    assertThat(outcome.highestSeverity()).isNull();
    assertThat(outcome.currentFlags()).isEmpty();
    assertThat(outcome.clearedRuleKeys())
            .containsExactly("LAB_CRP_ELEVATED", "LAB_CRP_HIGH");
}
~~~

- [ ] **Step 2: Run the focused test and verify failure**

Run:

~~~bash
./gradlew test --tests 'com.metabion.service.redflag.RedFlagEvaluationServiceTest'
~~~

Expected: compilation fails because the evaluation methods return void and RedFlagEvaluationOutcome is absent.

- [ ] **Step 3: Add the immutable evaluation outcome**

~~~java
package com.metabion.service.redflag;

import com.metabion.domain.RedFlagSeverity;
import com.metabion.domain.RedFlagSourceType;

import java.time.Instant;
import java.util.List;

public record RedFlagEvaluationOutcome(
        RedFlagSeverity highestSeverity,
        List<Flag> currentFlags,
        List<String> clearedRuleKeys) {

    public RedFlagEvaluationOutcome {
        currentFlags = List.copyOf(currentFlags);
        clearedRuleKeys = List.copyOf(clearedRuleKeys);
    }

    public record Flag(
            Long eventId,
            String ruleKey,
            RedFlagSeverity severity,
            Instant detectedAt,
            RedFlagSourceType sourceType,
            Long sourceId) {
    }
}
~~~

- [ ] **Step 4: Read preceding keys under the existing source lock**

Add:

~~~java
@Query("""
       select event.ruleVersion.rule.stableKey
       from RedFlagTriggerEvent event
       where event.evaluationRun.id = :runId
       order by event.ruleVersion.rule.stableKey
       """)
List<String> findRuleKeysByEvaluationRunId(@Param("runId") Long runId);
~~~

After findCurrentForUpdate succeeds, query the preceding keys before superseding that run. Persist each new event, capture its assigned ID, and build outcome flags from the matching definition. Compute cleared keys without another evaluation:

~~~java
var currentKeys = persistedFlags.stream()
        .map(RedFlagEvaluationOutcome.Flag::ruleKey)
        .collect(Collectors.toSet());
var clearedKeys = precedingKeys.stream()
        .filter(key -> !currentKeys.contains(key))
        .distinct()
        .sorted()
        .toList();
return new RedFlagEvaluationOutcome(
        result.overallSeverity(), persistedFlags, clearedKeys);
~~~

Keep removal's forced empty engine result.

- [ ] **Step 5: Run evaluation and source integration tests**

~~~bash
./gradlew test --tests 'com.metabion.service.redflag.RedFlagEvaluationServiceTest' --tests 'com.metabion.service.redflag.SymptomRedFlagIntegrationTest' --tests 'com.metabion.service.redflag.LabRedFlagIntegrationTest'
~~~

Expected: all selected tests pass. Existing callers compile because Java permits ignoring a return value.

- [ ] **Step 6: Commit**

~~~bash
git add src/main/java/com/metabion/service/redflag/RedFlagEvaluationOutcome.java src/main/java/com/metabion/repository/RedFlagTriggerEventRepository.java src/main/java/com/metabion/service/redflag/RedFlagEvaluationService.java src/test/java/com/metabion/service/redflag/RedFlagEvaluationServiceTest.java
git commit -m "Return persisted red-flag evaluation outcomes"
~~~

### Task 2: Add stable trigger-event queries and cursors

**Files:**
- Create: src/main/java/com/metabion/dto/redflag/RedFlagHistoryQuery.java
- Create: src/main/java/com/metabion/service/redflag/RedFlagHistoryCursorCodec.java
- Modify: src/main/java/com/metabion/repository/RedFlagTriggerEventRepository.java
- Create: src/test/java/com/metabion/service/redflag/RedFlagHistoryCursorCodecTest.java
- Create: src/test/java/com/metabion/repository/RedFlagTriggerEventQueryRepositoryTest.java

**Interfaces:**
- Consumes: RedFlagTriggerEvent relationships and RedFlagSeverity.
- Produces query record, URL-safe cursor codec, and bounded current/history repository methods.

- [ ] **Step 1: Write failing cursor tests**

~~~java
@Test
void roundTripsTimestampAndEventId() {
    var at = Instant.parse("2026-08-01T10:15:30.123456Z");
    var encoded = codec.encode(at, 701L);

    assertThat(codec.decode(encoded))
            .contains(new RedFlagHistoryCursorCodec.Cursor(at, 701L));
}

@Test
void rejectsMalformedCursorWithoutEchoingIt() {
    assertThatThrownBy(() -> codec.decode("patient-value"))
            .isInstanceOfSatisfying(ResponseStatusException.class, error -> {
                assertThat(error.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                assertThat(error.getReason()).isEqualTo("invalid cursor");
            });
}
~~~

- [ ] **Step 2: Run cursor tests and verify failure**

~~~bash
./gradlew test --tests 'com.metabion.service.redflag.RedFlagHistoryCursorCodecTest'
~~~

Expected: compilation fails because the codec is absent.

- [ ] **Step 3: Implement query record and cursor**

~~~java
public record RedFlagHistoryQuery(
        LocalDate from,
        LocalDate to,
        RedFlagSeverity severity,
        String cursor,
        Integer size) {
}
~~~

Encode UTF-8 text triggeredAt + "|" + eventId using the URL encoder without padding. Decode, split at the final pipe, parse with Instant.parse and Long.parseLong, require a positive event ID, and map every failure to HTTP 400 with reason "invalid cursor".

- [ ] **Step 4: Run cursor tests**

~~~bash
./gradlew test --tests 'com.metabion.service.redflag.RedFlagHistoryCursorCodecTest'
~~~

Expected: PASS.

- [ ] **Step 5: Write failing PostgreSQL repository tests**

Seed two patients, a superseded matching run, its no-match successor, current matching runs, equal timestamps, and multiple severities. Assert current isolation, no-match exclusion, severity/time filters, and gap-free keyset pages:

~~~java
var firstPage = events.findHistoryPage(
        patient.getId(), null, null, null, null, null,
        PageRequest.of(0, 2));
assertThat(firstPage).extracting(RedFlagTriggerEvent::getId)
        .containsExactly(currentEmergency.getId(), currentRoutine.getId());

var secondPage = events.findHistoryPage(
        patient.getId(), null, null, null,
        firstPage.getLast().getTriggeredAt(),
        firstPage.getLast().getId(),
        PageRequest.of(0, 2));
assertThat(secondPage).doesNotContainAnyElementsOf(firstPage);
~~~

Also assert findRuleKeysByEvaluationRunId returns sorted stable keys.

- [ ] **Step 6: Run repository tests and verify failure**

~~~bash
./gradlew test --tests 'com.metabion.repository.RedFlagTriggerEventQueryRepositoryTest'
~~~

Expected: compilation fails because query methods are absent.

- [ ] **Step 7: Implement bounded queries**

Use an entity graph for evaluationRun, evaluationRun.supersededByRun, ruleVersion, and ruleVersion.rule.

~~~java
@Query("""
       select event from RedFlagTriggerEvent event
       where event.evaluationRun.patientProfile.id = :patientId
         and event.evaluationRun.current = true
       order by event.triggeredAt desc, event.id desc
       """)
List<RedFlagTriggerEvent> findCurrentForPatient(
        @Param("patientId") Long patientId);

@Query("""
       select event from RedFlagTriggerEvent event
       where event.evaluationRun.patientProfile.id = :patientId
         and (:severity is null or event.severity = :severity)
         and (:fromInclusive is null or event.triggeredAt >= :fromInclusive)
         and (:toExclusive is null or event.triggeredAt < :toExclusive)
         and (:cursorAt is null
              or event.triggeredAt < :cursorAt
              or (event.triggeredAt = :cursorAt and event.id < :cursorId))
       order by event.triggeredAt desc, event.id desc
       """)
List<RedFlagTriggerEvent> findHistoryPage(
        @Param("patientId") Long patientId,
        @Param("severity") RedFlagSeverity severity,
        @Param("fromInclusive") Instant fromInclusive,
        @Param("toExclusive") Instant toExclusive,
        @Param("cursorAt") Instant cursorAt,
        @Param("cursorId") Long cursorId,
        Pageable pageable);
~~~

Return List with Pageable so Spring Data does not execute a count query.

- [ ] **Step 8: Run query persistence tests**

~~~bash
./gradlew test --tests 'com.metabion.service.redflag.RedFlagHistoryCursorCodecTest' --tests 'com.metabion.repository.RedFlagTriggerEventQueryRepositoryTest' --tests 'com.metabion.repository.RedFlagEvaluationRepositoryTest'
~~~

Expected: PASS.

- [ ] **Step 9: Commit**

~~~bash
git add src/main/java/com/metabion/dto/redflag/RedFlagHistoryQuery.java src/main/java/com/metabion/service/redflag/RedFlagHistoryCursorCodec.java src/main/java/com/metabion/repository/RedFlagTriggerEventRepository.java src/test/java/com/metabion/service/redflag/RedFlagHistoryCursorCodecTest.java src/test/java/com/metabion/repository/RedFlagTriggerEventQueryRepositoryTest.java
git commit -m "Add paginated red-flag event queries"
~~~

### Task 3: Build patient and clinical query projections

**Files:**
- Create: src/main/java/com/metabion/dto/redflag/PatientRedFlagEventResponse.java
- Create: src/main/java/com/metabion/dto/redflag/ClinicalRedFlagEventResponse.java
- Create: src/main/java/com/metabion/dto/redflag/RedFlagMatchedInputsResponse.java
- Create: src/main/java/com/metabion/dto/redflag/PatientRedFlagSnapshotResponse.java
- Create: src/main/java/com/metabion/dto/redflag/ClinicalRedFlagSnapshotResponse.java
- Create: src/main/java/com/metabion/dto/redflag/PatientRedFlagHistoryResponse.java
- Create: src/main/java/com/metabion/dto/redflag/ClinicalRedFlagHistoryResponse.java
- Create: src/main/java/com/metabion/dto/redflag/RedFlagWriteOutcomeResponse.java
- Create: src/main/java/com/metabion/exception/RedFlagSnapshotException.java
- Create: src/main/java/com/metabion/service/redflag/RedFlagEventReadModel.java
- Create: src/main/java/com/metabion/service/redflag/PatientRedFlagResponseAssembler.java
- Create: src/main/java/com/metabion/service/redflag/ClinicalRedFlagResponseAssembler.java
- Modify: src/main/java/com/metabion/service/redflag/RedFlagSnapshotSerializer.java:17-59
- Modify: src/main/java/com/metabion/service/redflag/RedFlagEventQueryService.java:26-156
- Delete: src/main/java/com/metabion/dto/redflag/RedFlagEvaluationRunView.java
- Delete: src/main/java/com/metabion/dto/redflag/RedFlagTriggerEventView.java
- Test: src/test/java/com/metabion/service/redflag/RedFlagSnapshotSerializerTest.java
- Test: src/test/java/com/metabion/service/redflag/RedFlagEventQueryServiceTest.java

**Interfaces:**
- Consumes: Tasks 1-2.
- Produces current/history methods for current patient and clinical patient, plus RedFlagWriteOutcomeResponse outcome(RedFlagEvaluationOutcome outcome).

- [ ] **Step 1: Write failing deserialization tests**

~~~java
@Test
void deserializesTypedMatchedInputs() {
    var snapshot = serializer().deserialize(
            "{\"facts\":[{\"sourceType\":\"LAB_RESULT_SET\",\"sourceId\":91,"
                    + "\"factKey\":\"lab.CRP\",\"observedOn\":\"2026-07-28\","
                    + "\"decimalValue\":\"312\",\"textValue\":null,\"unit\":\"mg/L\"}]}");

    assertThat(snapshot.facts()).singleElement().satisfies(fact -> {
        assertThat(fact.factKey()).isEqualTo("lab.CRP");
        assertThat(fact.observedOn()).isEqualTo(LocalDate.of(2026, 7, 28));
    });
}

@Test
void corruptSnapshotRaisesOnlySanitizedMessage() {
    assertThatThrownBy(() -> serializer().deserialize("{patient-value"))
            .isInstanceOfSatisfying(RedFlagSnapshotException.class,
                    error -> assertThat(error.getMessage())
                            .isEqualTo("Red-flag snapshot processing failed"));
}
~~~

- [ ] **Step 2: Run serializer tests and verify failure**

~~~bash
./gradlew test --tests 'com.metabion.service.redflag.RedFlagSnapshotSerializerTest'
~~~

Expected: compilation fails because deserialize and RedFlagSnapshotException are absent.

- [ ] **Step 3: Add sanitized bidirectional snapshot handling**

Make RedFlagSnapshotException extend IllegalStateException with constant public text. Add a LocalDate deserializer to the existing module. Deserialize with:

~~~java
public RedFlagMatchedInputSnapshot deserialize(String snapshot) {
    try {
        return objectMapper.readValue(
                snapshot, RedFlagMatchedInputSnapshot.class);
    } catch (JsonProcessingException exception) {
        throw new RedFlagSnapshotException(exception);
    }
}
~~~

Throw the same exception from serialize.

- [ ] **Step 4: Write failing projection/query tests**

Replace run-centric expectations with event-centric tests for ownership, assignment roles, admin, coordinator denial, highest severity, empty results, filters, pagination, patient timezone, and clinical snapshot parsing.

~~~java
@Test
void patientCurrentUsesOneEventSetAndRestrictedProjection() {
    when(events.findCurrentForPatient(PATIENT_ID))
            .thenReturn(List.of(emergencyEvent(), routineEvent()));

    var result = service.currentForCurrentPatient(
            authentication("patient@example.com"));

    assertThat(result.highestSeverity())
            .isEqualTo(RedFlagSeverity.EMERGENCY);
    assertThat(result.flags())
            .extracting(PatientRedFlagEventResponse::ruleKey)
            .containsExactly(
                    "SYM_SEVERE_ABDOMINAL_PAIN",
                    "SYM_SUSPECTED_FLARE");
    verify(events).findCurrentForPatient(PATIENT_ID);
}

@Test
void clinicalHistoryAddsOnlyRuleVersionAndMatchedInputs() {
    when(accessControl.canViewPatientClinicalData(
            authentication, PATIENT_ID)).thenReturn(true);
    when(events.findHistoryPage(eq(PATIENT_ID), isNull(),
            any(), any(), isNull(), isNull(), any()))
            .thenReturn(List.of(clinicalEvent()));

    var result = service.historyForClinicalPatient(
            authentication, PATIENT_ID,
            new RedFlagHistoryQuery(null, null, null, null, 25));

    assertThat(result.items()).singleElement().satisfies(flag -> {
        assertThat(flag.ruleVersion()).isEqualTo(1);
        assertThat(flag.matchedInputs().facts()).singleElement()
                .extracting(RedFlagMatchedInputsResponse.Fact::factKey)
                .isEqualTo("lab.CRP");
    });
}
~~~

Use record-component reflection to prove public records have no evaluationRunId, sourceOperation, or matchedGroupKey.

- [ ] **Step 5: Run query-service tests and verify failure**

~~~bash
./gradlew test --tests 'com.metabion.service.redflag.RedFlagEventQueryServiceTest'
~~~

Expected: compilation fails because new records and methods are absent.

- [ ] **Step 6: Add immutable response records and assemblers**

Use these patient fields:

~~~java
public record PatientRedFlagEventResponse(
        Long eventId,
        String ruleKey,
        RedFlagSeverity severity,
        Instant detectedAt,
        RedFlagSourceType sourceType,
        Long sourceId,
        boolean current,
        Instant supersededAt) {
}
~~~

The clinical record repeats these fields and adds only int ruleVersion and RedFlagMatchedInputsResponse matchedInputs. Defensively copy every list. Derive supersededAt from the successor evaluation timestamp. Map Task 1 write flags with current=true and supersededAt=null.

Use these exact remaining contracts:

~~~java
public record ClinicalRedFlagEventResponse(
        Long eventId,
        String ruleKey,
        RedFlagSeverity severity,
        Instant detectedAt,
        RedFlagSourceType sourceType,
        Long sourceId,
        boolean current,
        Instant supersededAt,
        int ruleVersion,
        RedFlagMatchedInputsResponse matchedInputs) {
}

public record RedFlagMatchedInputsResponse(List<Fact> facts) {
    public RedFlagMatchedInputsResponse {
        facts = List.copyOf(facts);
    }

    public record Fact(
            RedFlagSourceType sourceType,
            Long sourceId,
            String factKey,
            LocalDate observedOn,
            String decimalValue,
            String textValue,
            String unit) {
    }
}

public record PatientRedFlagSnapshotResponse(
        RedFlagSeverity highestSeverity,
        List<PatientRedFlagEventResponse> flags) {
}

public record ClinicalRedFlagSnapshotResponse(
        RedFlagSeverity highestSeverity,
        List<ClinicalRedFlagEventResponse> flags) {
}

public record PatientRedFlagHistoryResponse(
        List<PatientRedFlagEventResponse> items,
        String nextCursor) {
}

public record ClinicalRedFlagHistoryResponse(
        List<ClinicalRedFlagEventResponse> items,
        String nextCursor) {
}

public record RedFlagWriteOutcomeResponse(
        RedFlagSeverity highestSeverity,
        List<PatientRedFlagEventResponse> currentFlags,
        List<String> clearedRuleKeys) {
}
~~~

Use one package-private internal read model:

~~~java
record RedFlagEventReadModel(
        Long eventId,
        String ruleKey,
        int ruleVersion,
        RedFlagSeverity severity,
        Instant detectedAt,
        RedFlagSourceType sourceType,
        Long sourceId,
        boolean current,
        Instant supersededAt,
        String matchedInputs) {
}
~~~

- [ ] **Step 7: Implement event-centric query service**

Use signatures:

~~~java
public PatientRedFlagSnapshotResponse currentForCurrentPatient(
        Authentication authentication)
public PatientRedFlagHistoryResponse historyForCurrentPatient(
        Authentication authentication, RedFlagHistoryQuery query)
public ClinicalRedFlagSnapshotResponse currentForClinicalPatient(
        Authentication authentication, Long patientProfileId)
public ClinicalRedFlagHistoryResponse historyForClinicalPatient(
        Authentication authentication, Long patientProfileId,
        RedFlagHistoryQuery query)
~~~

Normalize size to 25 and reject outside 1-100. Interpret optional dates in the target patient's timezone with UTC fallback. Use inclusive start-of-day and exclusive start of the day after to. Reject from > to, a range above 370 days when both exist, and date overflow. Fetch size + 1, return size, and encode nextCursor only when an extra row exists.

For clinical requests, validate role first, assignment second for non-admins, then load the profile. This preserves 403 existence hiding for unassigned callers and 404 for an authorized missing patient.

Delete obsolete run-centric DTOs after references are gone.

- [ ] **Step 8: Run projection/query tests**

~~~bash
./gradlew test --tests 'com.metabion.service.redflag.RedFlagSnapshotSerializerTest' --tests 'com.metabion.service.redflag.RedFlagEventQueryServiceTest'
~~~

Expected: PASS.

- [ ] **Step 9: Commit**

~~~bash
git add src/main/java/com/metabion/dto/redflag src/main/java/com/metabion/exception/RedFlagSnapshotException.java src/main/java/com/metabion/service/redflag src/test/java/com/metabion/service/redflag/RedFlagSnapshotSerializerTest.java src/test/java/com/metabion/service/redflag/RedFlagEventQueryServiceTest.java
git commit -m "Build red-flag API projections"
~~~

### Task 4: Expose patient and clinical REST reads

**Files:**
- Create: src/main/java/com/metabion/controller/api/PatientRedFlagController.java
- Create: src/main/java/com/metabion/controller/api/ClinicalRedFlagController.java
- Modify: src/main/java/com/metabion/controller/api/GlobalExceptionHandler.java:95-123
- Create: src/test/java/com/metabion/controller/api/PatientRedFlagControllerTest.java
- Create: src/test/java/com/metabion/controller/api/ClinicalRedFlagControllerTest.java
- Modify: src/test/java/com/metabion/controller/api/GlobalExceptionHandlerTest.java

**Interfaces:**
- Consumes: Task 3 query-service methods.
- Produces four approved GET routes under /api/red-flags and /api/clinical/patients/{patientProfileId}/red-flags.

- [ ] **Step 1: Write failing MockMvc contract tests**

~~~java
mvc.perform(get("/api/red-flags/current")
                .with(user("patient@example.com").roles("PATIENT")))
        .andExpect(status().isOk())
        .andExpect(header().string(
                "Cache-Control", containsString("no-store")))
        .andExpect(jsonPath("$.highestSeverity")
                .value("URGENT_REVIEW"))
        .andExpect(jsonPath("$.flags[0].ruleKey")
                .value("LAB_CRP_HIGH"))
        .andExpect(jsonPath("$.flags[0].ruleVersion")
                .doesNotExist());

mvc.perform(get("/api/clinical/patients/41/red-flags/history")
                .with(user("doctor@example.com").roles("PHYSICIAN"))
                .param("severity", "EMERGENCY")
                .param("size", "20"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].ruleVersion").value(1))
        .andExpect(jsonPath(
                "$.items[0].matchedInputs.facts[0].factKey")
                .value("lab.CRP"))
        .andExpect(jsonPath("$.items[0].evaluationRunId")
                .doesNotExist())
        .andExpect(jsonPath("$.items[0].sourceOperation")
                .doesNotExist())
        .andExpect(jsonPath("$.items[0].matchedGroupKey")
                .doesNotExist());
~~~

Add unauthenticated 401, sanitized invalid-enum 400, filter forwarding, and empty response tests.

- [ ] **Step 2: Run controller tests and verify failure**

~~~bash
./gradlew test --tests 'com.metabion.controller.api.PatientRedFlagControllerTest' --tests 'com.metabion.controller.api.ClinicalRedFlagControllerTest'
~~~

Expected: compilation fails because controllers are absent.

- [ ] **Step 3: Implement thin no-store controllers**

Return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(response). Each history method accepts:

~~~java
@RequestParam(required = false) LocalDate from,
@RequestParam(required = false) LocalDate to,
@RequestParam(required = false) RedFlagSeverity severity,
@RequestParam(required = false) String cursor,
@RequestParam(required = false) Integer size
~~~

Construct one RedFlagHistoryQuery and delegate. Do not add SecurityConfig matchers; /api/** already requires authentication and the service enforces roles/assignment.

- [ ] **Step 4: Map snapshot corruption to sanitized 500**

~~~java
@ExceptionHandler(RedFlagSnapshotException.class)
public ResponseEntity<Map<String, String>> redFlagSnapshot(
        RedFlagSnapshotException exception) {
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(Map.of("error", "request_failed"));
}
~~~

Add a handler test proving the response omits the exception and cause messages.

- [ ] **Step 5: Run REST and handler tests**

~~~bash
./gradlew test --tests 'com.metabion.controller.api.PatientRedFlagControllerTest' --tests 'com.metabion.controller.api.ClinicalRedFlagControllerTest' --tests 'com.metabion.controller.api.GlobalExceptionHandlerTest' --tests 'com.metabion.controller.api.SymptomTrackingControllerTest' --tests 'com.metabion.controller.api.LabResultControllerTest'
~~~

Expected: PASS and existing write JSON remains unchanged.

- [ ] **Step 6: Commit**

~~~bash
git add src/main/java/com/metabion/controller/api/PatientRedFlagController.java src/main/java/com/metabion/controller/api/ClinicalRedFlagController.java src/main/java/com/metabion/controller/api/GlobalExceptionHandler.java src/test/java/com/metabion/controller/api/PatientRedFlagControllerTest.java src/test/java/com/metabion/controller/api/ClinicalRedFlagControllerTest.java src/test/java/com/metabion/controller/api/GlobalExceptionHandlerTest.java
git commit -m "Expose red flags through REST"
~~~

### Task 5: Add enriched patient mutation operations

**Files:**
- Create: src/main/java/com/metabion/dto/mcp/McpSymptomCheckInWriteResponse.java
- Create: src/main/java/com/metabion/dto/mcp/McpLabResultSetWriteResponse.java
- Create: src/main/java/com/metabion/dto/mcp/McpLabResultRemovalWriteResponse.java
- Modify: src/main/java/com/metabion/service/SymptomTrackingService.java:70-122
- Modify: src/main/java/com/metabion/service/LabResultService.java:52-148
- Modify: src/test/java/com/metabion/service/SymptomTrackingServiceTest.java
- Modify: src/test/java/com/metabion/service/LabResultServiceTest.java
- Modify: src/test/java/com/metabion/controller/api/SymptomTrackingControllerTest.java
- Modify: src/test/java/com/metabion/controller/api/LabResultControllerTest.java

**Interfaces:**
- Consumes: Task 1 outcome and Task 3 PatientRedFlagResponseAssembler.outcome.
- Produces enriched current-patient symptom save, lab save/update, and lab removal methods while preserving old service methods.

- [ ] **Step 1: Write failing enriched-service tests**

~~~java
@Test
void enrichedSymptomSaveReturnsCheckInAndEvaluationOutcome() {
    when(redFlags.evaluateSymptom(any()))
            .thenReturn(evaluationOutcome);
    when(redFlagResponses.outcome(evaluationOutcome))
            .thenReturn(outcomeResponse);

    var response = service.saveForCurrentPatientWithRedFlags(
            authentication, request);

    assertThat(response.result().id()).isEqualTo(savedCheckInId);
    assertThat(response.redFlagOutcome())
            .isSameAs(outcomeResponse);
    verify(redFlags, times(1)).evaluateSymptom(any());
}

@Test
void existingSymptomSaveStillReturnsOnlyCheckInResponse() {
    var response = service.saveForCurrentPatient(
            authentication, request);

    assertThat(response).isInstanceOf(SymptomCheckInResponse.class);
    verify(redFlags, times(1)).evaluateSymptom(any());
}
~~~

Add laboratory create/update equivalents and a removal test asserting result.status() is "removed" and cleared keys come from the same evaluation.

- [ ] **Step 2: Run source-service tests and verify failure**

~~~bash
./gradlew test --tests 'com.metabion.service.SymptomTrackingServiceTest' --tests 'com.metabion.service.LabResultServiceTest'
~~~

Expected: compilation fails because enriched methods, wrappers, and assembler dependency are absent.

- [ ] **Step 3: Add explicit MCP-only write envelopes**

~~~java
public record McpSymptomCheckInWriteResponse(
        SymptomCheckInResponse result,
        RedFlagWriteOutcomeResponse redFlagOutcome) {
}

public record McpLabResultSetWriteResponse(
        LabResultSetResponse result,
        RedFlagWriteOutcomeResponse redFlagOutcome) {
}

public record McpLabResultRemovalWriteResponse(
        Result result,
        RedFlagWriteOutcomeResponse redFlagOutcome) {
    public record Result(String status) {
    }
}
~~~

- [ ] **Step 4: Refactor each source mutation into one internal path**

For symptoms, both public methods call one private method:

~~~java
private record SavedSymptomCheckIn(
        SymptomCheckInResponse response,
        RedFlagEvaluationOutcome redFlagOutcome) {
}
~~~

Keep @Transactional on both public methods. The REST method returns response; the enriched method maps the outcome and returns its wrapper.

For laboratories, make private create/update return an internal result containing LabResultSetResponse and RedFlagEvaluationOutcome. Make private removal return RedFlagEvaluationOutcome. Existing patient/clinical methods select the old response or ignore the returned outcome; enriched patient methods wrap it. Verify each audit, source mutation, and evaluation executes exactly once.

- [ ] **Step 5: Run source and REST regression tests**

~~~bash
./gradlew test --tests 'com.metabion.service.SymptomTrackingServiceTest' --tests 'com.metabion.service.LabResultServiceTest' --tests 'com.metabion.controller.api.SymptomTrackingControllerTest' --tests 'com.metabion.controller.api.LabResultControllerTest' --tests 'com.metabion.controller.api.ClinicalLabResultControllerTest'
~~~

Expected: PASS; ordinary REST responses remain unchanged.

- [ ] **Step 6: Commit**

~~~bash
git add src/main/java/com/metabion/dto/mcp/McpSymptomCheckInWriteResponse.java src/main/java/com/metabion/dto/mcp/McpLabResultSetWriteResponse.java src/main/java/com/metabion/dto/mcp/McpLabResultRemovalWriteResponse.java src/main/java/com/metabion/service/SymptomTrackingService.java src/main/java/com/metabion/service/LabResultService.java src/test/java/com/metabion/service/SymptomTrackingServiceTest.java src/test/java/com/metabion/service/LabResultServiceTest.java src/test/java/com/metabion/controller/api/SymptomTrackingControllerTest.java src/test/java/com/metabion/controller/api/LabResultControllerTest.java
git commit -m "Add red-flag-aware patient mutations"
~~~

### Task 6: Add the MCP red-flag read scope

**Files:**
- Modify: src/main/java/com/metabion/domain/PatientAccessTokenScope.java:3-18
- Modify: src/test/java/com/metabion/domain/PatientAccessTokenScopeTest.java
- Modify: src/test/java/com/metabion/controller/api/OAuthMetadataControllerTest.java:78-112
- Modify: src/test/java/com/metabion/service/oauth/OAuthClientRegistrationServiceTest.java
- Modify: src/test/java/com/metabion/service/oauth/OAuthAuthorizationServiceTest.java
- Modify: src/test/java/com/metabion/service/oauth/OAuthRefreshTokenServiceTest.java
- Modify: src/test/java/com/metabion/service/PatientAccessTokenServiceTest.java

**Interfaces:**
- Produces PatientAccessTokenScope.PATIENT_RED_FLAG_READ with authority patient:red-flags:read.
- Requires no migration because persisted scope columns are unconstrained VARCHAR(80).

- [ ] **Step 1: Write failing scope and OAuth assertions**

~~~java
@Test
void redFlagReadScopeRoundTripsThroughProtocolValue() {
    assertThat(PatientAccessTokenScope.fromAuthority(
            "patient:red-flags:read"))
            .isEqualTo(
                    PatientAccessTokenScope.PATIENT_RED_FLAG_READ);
    assertThat(PatientAccessTokenScope.PATIENT_RED_FLAG_READ
            .authority())
            .isEqualTo("patient:red-flags:read");
}
~~~

Add patient:red-flags:read to both metadata expectations. In registration tests, request it and assert it is persisted and returned. In authorization and refresh tests, include it in the consented scope set and assert issued and rotated tokens preserve exactly the granted set.

In PatientAccessTokenServiceTest, issue a personal token with
Set.of(PATIENT_RED_FLAG_READ), reload it, and assert its stored scope set
contains exactly PATIENT_RED_FLAG_READ. Keep the existing assertion that no
unrequested scope is added.

- [ ] **Step 2: Run focused OAuth tests and verify failure**

~~~bash
./gradlew test --tests 'com.metabion.domain.PatientAccessTokenScopeTest' --tests 'com.metabion.controller.api.OAuthMetadataControllerTest' --tests 'com.metabion.service.PatientAccessTokenServiceTest' --tests 'com.metabion.service.oauth.OAuthClientRegistrationServiceTest' --tests 'com.metabion.service.oauth.OAuthAuthorizationServiceTest' --tests 'com.metabion.service.oauth.OAuthRefreshTokenServiceTest'
~~~

Expected: compilation/assertion failure because the scope is unsupported.

- [ ] **Step 3: Add the enum value**

~~~java
PATIENT_LAB_READ("patient:lab:read"),
PATIENT_LAB_WRITE("patient:lab:write"),
PATIENT_RED_FLAG_READ("patient:red-flags:read"),
PATIENT_TREND_READ("patient:trend:read");
~~~

Do not mutate existing registered clients or tokens. Metadata and dynamic registration already enumerate the enum.

- [ ] **Step 4: Run OAuth/token tests**

~~~bash
./gradlew test --tests 'com.metabion.domain.PatientAccessTokenScopeTest' --tests 'com.metabion.controller.api.OAuthMetadataControllerTest' --tests 'com.metabion.service.PatientAccessTokenServiceTest' --tests 'com.metabion.service.oauth.OAuthClientRegistrationServiceTest' --tests 'com.metabion.service.oauth.OAuthAuthorizationServiceTest' --tests 'com.metabion.service.oauth.OAuthRefreshTokenServiceTest'
~~~

Expected: PASS.

- [ ] **Step 5: Commit**

~~~bash
git add src/main/java/com/metabion/domain/PatientAccessTokenScope.java src/test/java/com/metabion/domain/PatientAccessTokenScopeTest.java src/test/java/com/metabion/controller/api/OAuthMetadataControllerTest.java src/test/java/com/metabion/service/PatientAccessTokenServiceTest.java src/test/java/com/metabion/service/oauth/OAuthClientRegistrationServiceTest.java src/test/java/com/metabion/service/oauth/OAuthAuthorizationServiceTest.java src/test/java/com/metabion/service/oauth/OAuthRefreshTokenServiceTest.java
git commit -m "Add patient red-flag read scope"
~~~

### Task 7: Expose red flags through PatientAppFacade and MCP

**Files:**
- Modify: src/main/java/com/metabion/service/PatientAppFacade.java:35-188
- Modify: src/main/java/com/metabion/mcp/PatientMcpTools.java:154-300
- Modify: src/test/java/com/metabion/service/PatientAppFacadeTest.java:47-200
- Modify: src/test/java/com/metabion/mcp/PatientMcpToolsTest.java:140-264

**Interfaces:**
- Consumes: Task 3 patient responses, Task 5 enriched mutations, and Task 6 scope.
- Produces metabion_get_current_red_flags, metabion_list_red_flag_history, and enriched existing symptom/lab write results.

- [ ] **Step 1: Write failing facade tests**

Inject RedFlagEventQueryService and assert:

~~~java
@Test
void delegatesRedFlagReadsAndEnrichedWrites() {
    when(redFlags.currentForCurrentPatient(authentication))
            .thenReturn(snapshot);
    when(redFlags.historyForCurrentPatient(authentication, query))
            .thenReturn(history);
    when(symptoms.saveForCurrentPatientWithRedFlags(
            authentication, symptomRequest))
            .thenReturn(symptomWrite);
    when(labResults.saveForCurrentPatientWithRedFlags(
            authentication, labRequest))
            .thenReturn(labWrite);

    assertThat(facade.currentRedFlags(authentication))
            .isSameAs(snapshot);
    assertThat(facade.redFlagHistory(authentication, query))
            .isSameAs(history);
    assertThat(facade.saveSymptomCheckIn(
            authentication, symptomRequest))
            .isSameAs(symptomWrite);
    assertThat(facade.saveLabResultSet(authentication, labRequest))
            .isSameAs(labWrite);
}
~~~

Also assert removal returns removeForCurrentPatientWithRedFlags.

- [ ] **Step 2: Write failing MCP scope, schema, and audit tests**

~~~java
@Test
void currentRedFlagsRequireDedicatedScopeAndAuditSuccess() {
    authenticate(PatientAccessTokenScope.PATIENT_RED_FLAG_READ);
    when(patientApp.currentRedFlags(any())).thenReturn(snapshot);

    assertThat(tools.metabionGetCurrentRedFlags())
            .isSameAs(snapshot);
    verify(audit).recordToolSuccess(
            any(), eq("metabion_get_current_red_flags"));
}

@Test
void historyRejectsTokenWithoutDedicatedScope() {
    authenticate(PatientAccessTokenScope.PATIENT_LAB_READ);

    assertThatThrownBy(() -> tools.metabionListRedFlagHistory(
            null, null, null, null, 25))
            .isInstanceOf(InsufficientScopeException.class);
    verifyNoInteractions(patientApp);
    verify(audit).recordToolFailure(
            any(), eq("metabion_list_red_flag_history"),
            eq("missing_scope"));
}

@Test
void labWriteReturnsOutcomeUnderExistingWriteScope() {
    authenticate(PatientAccessTokenScope.PATIENT_LAB_WRITE);
    when(patientApp.saveLabResultSet(any(), same(request)))
            .thenReturn(writeResponse);

    assertThat(tools.metabionSaveLabResultSet(request))
            .isSameAs(writeResponse);
}
~~~

Use reflection to assert both new tool names, optional history @McpToolParam(required=false) annotations, and affected write descriptions containing "disclose returned red flags immediately" plus "do not invent medical guidance".

- [ ] **Step 3: Run facade/MCP tests and verify failure**

~~~bash
./gradlew test --tests 'com.metabion.service.PatientAppFacadeTest' --tests 'com.metabion.mcp.PatientMcpToolsTest'
~~~

Expected: compilation fails because facade and tools are not updated.

- [ ] **Step 4: Add facade methods**

Add RedFlagEventQueryService to the constructor:

~~~java
public PatientRedFlagSnapshotResponse currentRedFlags(
        Authentication auth) {
    return redFlags.currentForCurrentPatient(auth);
}

public PatientRedFlagHistoryResponse redFlagHistory(
        Authentication auth, RedFlagHistoryQuery query) {
    return redFlags.historyForCurrentPatient(auth, query);
}
~~~

Change the three MCP-facing facade mutation methods to call Task 5 enriched methods and return explicit wrapper types. REST controllers do not use these facade methods.

- [ ] **Step 5: Add MCP tools**

Annotate every optional history parameter:

~~~java
@McpTool(
        name = "metabion_list_red_flag_history",
        description = "List the current patient's red-flag history. "
                + "Disclose returned red flags immediately and "
                + "do not invent medical guidance.")
public PatientRedFlagHistoryResponse metabionListRedFlagHistory(
        @McpToolParam(required = false) LocalDate from,
        @McpToolParam(required = false) LocalDate to,
        @McpToolParam(required = false) RedFlagSeverity severity,
        @McpToolParam(required = false) String cursor,
        @McpToolParam(required = false) Integer size) {
    var auth = patientAuth();
    require(auth, PatientAccessTokenScope.PATIENT_RED_FLAG_READ,
            "metabion_list_red_flag_history");
    return audited(auth, "metabion_list_red_flag_history",
            () -> patientApp.redFlagHistory(auth,
                    new RedFlagHistoryQuery(
                            from, to, severity, cursor, size)));
}
~~~

Add the current tool with the same scope. Generalize auditedLab to a private generic audited helper used by these reads and affected writes; it records only operation and request_failed. Change symptom-save, lab-save, and lab-removal return types to Task 5 wrappers, retain their existing write scope checks, and update descriptions with the immediate-disclosure/no-invented-guidance instruction.

- [ ] **Step 6: Run facade, MCP, and metadata tests**

~~~bash
./gradlew test --tests 'com.metabion.service.PatientAppFacadeTest' --tests 'com.metabion.mcp.PatientMcpToolsTest' --tests 'com.metabion.controller.api.OAuthMetadataControllerTest'
~~~

Expected: PASS.

- [ ] **Step 7: Commit**

~~~bash
git add src/main/java/com/metabion/service/PatientAppFacade.java src/main/java/com/metabion/mcp/PatientMcpTools.java src/test/java/com/metabion/service/PatientAppFacadeTest.java src/test/java/com/metabion/mcp/PatientMcpToolsTest.java
git commit -m "Expose red flags through patient MCP tools"
~~~

### Task 8: Run complete regression verification

**Files:**
- Verify only; no planned source changes.

**Interfaces:**
- Consumes: Tasks 1-7.
- Produces: full-suite and clean-diff evidence.

- [ ] **Step 1: Run the complete test suite**

~~~bash
./gradlew test
~~~

Expected: BUILD SUCCESSFUL and Jacoco finalization completes.

- [ ] **Step 2: Inspect status, whitespace, and commits**

Run each command:

~~~bash
git status --short
git diff --check
git log --oneline -8
~~~

Expected: no whitespace errors; seven focused feature commits; pre-existing unrelated files remain untouched.

- [ ] **Step 3: Verify the contract checklist**

~~~text
REST current: patient + clinical, Cache-Control no-store
REST history: optional dates/severity/cursor/size, keyset pagination
Patient/MCP: no ruleVersion or matchedInputs
Clinical: ruleVersion + matchedInputs only
No public evaluationRunId/sourceOperation/matchedGroupKey
MCP reads: patient:red-flags:read
MCP writes: existing write scopes + exact redFlagOutcome
Existing REST writes: unchanged
No migration, dependency, guidance, notification, or resolution lifecycle
~~~
