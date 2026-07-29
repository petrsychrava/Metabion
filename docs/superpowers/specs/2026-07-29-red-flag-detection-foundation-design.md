# Red-Flag Detection Foundation Design

## Context

Jira issue MET-12 covers FR-041, FR-042, FR-043, FR-044, FR-054, and FR-069. This design scopes the first delivery to a backend foundation for deterministic red-flag detection over data Metabion already captures: structured symptom check-ins and laboratory result sets.

The current product already has:

- a versioned IBD symptom questionnaire with stool frequency, abdominal pain, blood in stool, urgency, general wellbeing, and an explicit flare state;
- canonical laboratory values for inflammation, haematology, nutrition, electrolytes, liver, and kidney markers;
- patient and cohort expert assignments plus centralized clinical visibility checks;
- laboratory before/after audit snapshots serialized as JSON text;
- patient REST and MCP entry paths that delegate to shared application services.

The product does not yet capture weight, hydration, or fever, and it has no patient-to-expert messaging, notification preference model, expert alert delivery, or red-flag administration UI.

## Goals

- Detect configured symptom and laboratory red-flag patterns when existing data is created or updated.
- Assign one of three severities: `EMERGENCY`, `URGENT_REVIEW`, or `ROUTINE_REVIEW`.
- Store immutable rule identities and numbered versions with controlled `DRAFT -> ACTIVE -> RETIRED` lifecycle transitions.
- Audit the exact rule version, matched normalized inputs, severity, and evaluation time for every trigger.
- Preserve complete evaluation history while distinguishing current results from superseded results.
- Evaluate synchronously and atomically with the clinical source write.
- Seed an approved, active version-1 rule catalogue so the foundation is immediately testable.
- Keep rule evaluation out of controllers and reuse the symptom, laboratory, assignment, and access-control boundaries already present.

## Non-Goals

- Patient-facing safety guidance or changes to patient API responses.
- In-app, email, push, or other expert alert delivery.
- Patient-to-expert messaging or free-text red-flag detection.
- Notification preferences or mandatory-notification policy.
- New weight, hydration, fever, pulse, or other clinical input fields.
- Rule authoring or publication REST APIs and user interfaces.
- Automatic historical backfill when a rule version changes.
- Asynchronous evaluation, retry queues, replay jobs, or background workers.
- Medical-device-grade decision support or diagnosis.

MET-12 remains open after this foundation unless the work is represented by a separate scoped subtask. FR-042, FR-043, FR-054, and FR-069 require later deliveries.

## Selected Approach

Use typed relational rule definitions and a constrained deterministic Java evaluator.

Rejected alternatives:

- JSON expression rules are flexible but move validation to runtime, are harder to inspect, and weaken database constraints around safety-sensitive configuration.
- Java-coded rules provide compile-time safety but are not meaningfully configurable and make the persisted rule version secondary to deployed code.
- A durable evaluation queue is unnecessary for this phase. Activation-time rule validation and a pure evaluator make runtime failure exceptional; an outbox can be introduced later if real operational requirements justify it.

## Architecture

Add a focused red-flag module within the existing layered packages:

- `RedFlagFactResolver` converts persisted symptom and laboratory data into stable typed facts.
- `RedFlagRuleCatalog` loads and defensively validates active rule versions and their condition graphs.
- `RedFlagRuleEngine` evaluates in-memory facts against rule definitions without database, HTTP, or security concerns.
- `RedFlagEvaluationService` coordinates fact resolution, rule loading, evaluation, run supersession, event persistence, and overall severity.
- `RedFlagEventQueryService` returns current results or full history while enforcing patient ownership and existing clinical assignment rules.

`SymptomTrackingService` and `LabResultService` invoke `RedFlagEvaluationService` after their final persisted state has been assembled. Controllers, Thymeleaf coordinators, SPA code, MCP tools, and `PatientAppFacade` do not duplicate threshold logic.

### Trigger sources

Each rule version declares one trigger source:

- `SYMPTOM_CHECK_IN` rules run when a symptom check-in is created or updated.
- `LAB_RESULT_SET` rules run when a laboratory result set is created or updated.

A triggered rule may inspect typed context from another source. The combined CRP rule triggers only from a CRP-containing laboratory write and may inspect symptom facts from the inclusive interval `collectionDate - 7 days` through `collectionDate`. A later symptom write does not independently retrigger that laboratory rule. This prevents duplicate cross-source events and lets laboratory removal cleanly supersede events created by that result set.

## Rule Representation

Rules have stable identities and numbered versions. A version's trigger, severity, evidence, groups, and conditions are immutable; only its lifecycle metadata changes as it moves from draft to active to retired. A rule version has one configured severity and matches when any of its condition groups matches. A condition group matches when all of its conditions match. This is a two-level disjunctive-normal-form model:

- rule = `group 1 OR group 2 OR ...`;
- group = `condition 1 AND condition 2 AND ...`.

Conditions contain:

- source type;
- stable fact key;
- comparison operator;
- exactly one typed operand, decimal or stable text;
- optional lookback days.

Trigger sources are `SYMPTOM_CHECK_IN` and `LAB_RESULT_SET`. Conditions may additionally use `PATIENT_PROFILE` for stable contextual facts such as sex; profile facts never trigger evaluation and do not support lookback.

Initial operators are `EQ`, `GT`, `GTE`, `LT`, and `LTE`. Alternative text values are represented by alternative groups instead of an `IN` expression. Missing facts do not match. Unsupported operators, fact keys, operand types, or lookback combinations make an active catalogue invalid.

Example: stool frequency at least 6 plus visible blood, moderate pain, or very poor wellbeing is represented as three groups:

1. frequency `GTE 6` AND blood `EQ visible`;
2. frequency `GTE 6` AND pain `EQ moderate`;
3. frequency `GTE 6` AND wellbeing `EQ very-unwell`.

Stable questionnaire question and option keys are used rather than localized labels or questionnaire numeric scores.

## Rule Lifecycle

The lifecycle is deliberately small:

1. Insert a new immutable version as `DRAFT` with evidence, rationale, author, and change summary.
2. Record clinical approval metadata.
3. Activate the approved version transactionally while retiring the preceding `ACTIVE` version for the same stable rule.
4. Preserve retired definitions and all historical evaluation references permanently.

An active version must have approval metadata. Phase 1 uses reviewed Flyway migrations as the controlled publication mechanism and writes matching lifecycle transition rows, including draft-to-active transitions for the initial seed. The seeded version-1 catalogue is treated as approved for the application lifecycle by design assumption. Later formally reviewed replacements receive new version numbers; existing clinical configuration is never edited in place.

Activation is forward-only. Existing evaluation runs are not recalculated. New or edited source records use the rule versions active during their transaction. Explicit historical replay is outside this phase.

## Persistence Model

The live migration sequence currently ends at `V20`, so the foundation uses `V21__red_flag_detection_foundation.sql`.

### Rule configuration

`red_flag_rules`

- stable key;
- display name;
- creation timestamp.

`red_flag_rule_versions`

- rule reference and version number;
- lifecycle status;
- trigger source;
- severity;
- evidence references and rationale;
- author and change summary;
- approval actor/reference and approval timestamp;
- activation and retirement timestamps.

`red_flag_rule_condition_groups`

- rule-version reference;
- stable group key;
- deterministic sort order.

`red_flag_rule_conditions`

- group reference;
- source type and stable fact key;
- comparison operator;
- decimal operand or text operand;
- lookback days;
- deterministic sort order.

`red_flag_rule_transitions`

- rule-version reference;
- previous and new status;
- actor/reference;
- timestamp and change note.

### Evaluation audit

`red_flag_evaluation_runs`

- patient reference;
- source type, source ID, and source operation (`UPSERT` or `REMOVE`);
- evaluation timestamp;
- overall highest severity, nullable for a successful no-match run;
- current/superseded state and optional successor-run reference.

`red_flag_trigger_events`

- evaluation-run reference;
- immutable rule-version reference;
- deterministically selected matched-group reference;
- severity and trigger timestamp;
- minimal matched-input snapshot as JSON text.

Important constraints:

- unique rule stable keys and unique `(rule_id, version_number)`;
- at most one active version per stable rule;
- active versions require complete approval metadata;
- conditions have exactly one correctly typed operand;
- database checks constrain lifecycle statuses, severities, sources, operators, and source operations;
- one current evaluation run per source record, enforced in PostgreSQL;
- rule identities, versions, transitions, runs, and events are never deleted.

A successful no-match update or removal run supersedes the preceding matching run. A new run is persisted and prior-current state is changed in the same source transaction. PostgreSQL-specific uniqueness and concurrency behavior receive Testcontainers coverage.

## Matched-Input Snapshots

The existing laboratory audit trail provides the local pattern:

- `LabAuditSnapshot` defines a typed record;
- `LabAuditService` serializes before/after records with Jackson;
- `LabResultAuditEvent` stores the serialized values in `TEXT` columns.

The red-flag snapshot narrows that pattern to the facts that made one condition group match. When multiple alternative groups match, the engine records the first matching group by configured sort order. This provides one deterministic, sufficient explanation without copying redundant facts or the complete check-in or laboratory result set.

`RedFlagMatchedInputSnapshot` contains a deterministically sorted fact list. Facts are ordered by source type, stable fact key, observation date/time, and source ID. Stable keys and canonical units are used. Decimals use a consistent plain representation and dates use ISO-8601. The snapshot excludes patient names, email addresses, notes, localized labels, and unrelated results.

Example:

```json
{
  "facts": [
    {
      "sourceType": "LAB_RESULT",
      "sourceId": 91,
      "factKey": "lab.CRP",
      "observedAt": "2026-07-28",
      "decimalValue": "312.00",
      "unit": "mg/L"
    }
  ]
}
```

Patient, rule version, severity, and trigger time remain searchable relational columns. Snapshot JSON remains `TEXT` because the application does not query inside it, the exact serialized evidence should be preserved, and Jackson is already available through the existing Spring stack.

## Runtime Data Flow

For symptom or laboratory create/update:

1. The existing service validates and assembles the final source state.
2. The source is saved and flushed as required by its existing persistence rules.
3. `RedFlagEvaluationService` loads applicable active rule versions.
4. `RedFlagFactResolver` resolves current-source facts and permitted lookback context.
5. `RedFlagRuleEngine` evaluates every applicable rule in memory.
6. The service persists one evaluation run and one event per matched rule.
7. The run's overall severity is the highest matched severity.
8. The preceding current run for that source is marked superseded.
9. The source, run, events, and supersession commit together.

Laboratory comparisons always use existing canonical values after unit conversion.

For laboratory removal, the service records a successful no-match `REMOVE` run and supersedes the prior run for that result set.

If fact resolution, rule evaluation, snapshot serialization, or event persistence fails, the entire clinical source transaction rolls back and the existing API error path returns a generic server error. No partial source write without an evaluation is permitted. The patient-facing form retains its local submitted state for retry. No health facts or raw exception context are added to logs.

## Initial Approved Rule Catalogue

### Symptom rules

| Stable rule | Severity | Condition |
|---|---|---|
| `SYM_SEVERE_ABDOMINAL_PAIN` | `EMERGENCY` | Abdominal pain = `severe` |
| `SYM_SIGNIFICANT_BLEEDING` | `EMERGENCY` | Blood in stool = `significant` |
| `SYM_ACTIVE_FLARE` | `URGENT_REVIEW` | Flare state = `ACTIVE_FLARE` |
| `SYM_HIGH_STOOL_FREQUENCY` | `URGENT_REVIEW` | Stool frequency >8 |
| `SYM_COMBINED_SEVERE_ACTIVITY` | `URGENT_REVIEW` | Stool frequency >=6 plus visible blood, moderate pain, or `very-unwell` |
| `SYM_SUSPECTED_FLARE` | `ROUTINE_REVIEW` | Flare state = `SUSPECTED_FLARE` |
| `SYM_MODERATE_DETERIORATION` | `ROUTINE_REVIEW` | Stool frequency 4-5, visible blood, moderate pain, or `very-unwell` |

If several symptom rules match, all matches remain auditable and the run uses the highest severity.

### Laboratory rules

| Stable rule | Severity | Condition |
|---|---|---|
| `LAB_SODIUM_CRITICAL` | `EMERGENCY` | Sodium <=120 or >=160 mmol/L |
| `LAB_POTASSIUM_CRITICAL` | `EMERGENCY` | Potassium <=2.5 or >=6.5 mmol/L |
| `LAB_CRP_CRITICAL` | `EMERGENCY` | CRP >=300 mg/L |
| `LAB_CRP_HIGH` | `URGENT_REVIEW` | CRP >=100 and <300 mg/L |
| `LAB_CRP_SYMPTOM_CONTEXT` | `URGENT_REVIEW` | CRP >45 and <100 mg/L plus an active flare or an urgent-or-higher symptom pattern in the inclusive preceding seven-day window |
| `LAB_HEMOGLOBIN_CRITICAL_LOW` | `URGENT_REVIEW` | Haemoglobin <=70 g/L |
| `LAB_MAGNESIUM_CRITICAL_LOW` | `URGENT_REVIEW` | Magnesium <=0.40 mmol/L |
| `LAB_UREA_CRITICAL_HIGH` | `URGENT_REVIEW` | Urea >=30 mmol/L |
| `LAB_CREATININE_CRITICAL_HIGH` | `URGENT_REVIEW` | Creatinine >=354 umol/L |
| `LAB_TRANSAMINASE_CRITICAL_HIGH` | `URGENT_REVIEW` | ALT or AST >=500 U/L |
| `LAB_ALBUMIN_CRITICAL_LOW` | `URGENT_REVIEW` | Albumin <=10 g/L |
| `LAB_CALPROTECTIN_HIGH` | `URGENT_REVIEW` | Faecal calprotectin >250 ug/g |
| `LAB_CRP_ELEVATED` | `ROUTINE_REVIEW` | CRP >45 and <100 mg/L |
| `LAB_ALBUMIN_LOW` | `ROUTINE_REVIEW` | Albumin >10 and <30 g/L |
| `LAB_HEMOGLOBIN_LOW_MALE` | `ROUTINE_REVIEW` | Male profile and haemoglobin >70 and <=130 g/L |
| `LAB_HEMOGLOBIN_LOW_FEMALE` | `ROUTINE_REVIEW` | Female profile and haemoglobin >70 and <=120 g/L |
| `LAB_CALPROTECTIN_BORDERLINE` | `ROUTINE_REVIEW` | Faecal calprotectin 100-250 ug/g |

When CRP between 45 and 100 also matches the symptom-context rule, both events remain auditable and the run's effective severity is `URGENT_REVIEW`.

The CRP context rule expands the approved symptom thresholds into raw fact conditions. It does not depend on a previously persisted red-flag event. Qualifying context includes active flare, stool frequency above 8, the combined stool-frequency pattern, severe abdominal pain, or significant bleeding.

The sex-specific routine haemoglobin rules do not match when the profile lacks a male/female value. The <=70 g/L rule remains applicable.

Calcium is excluded because the current catalog does not distinguish adjusted from total calcium. Ferritin, vitamins, chloride, bilirubin, ALP, GGT, and eGFR are excluded because isolated fixed values require additional clinical context.

### Evidence references

The seeded rule versions retain their relevant evidence URLs and rationale. Primary references include:

- NHS IBD emergency guidance: <https://www.nhs.uk/conditions/inflammatory-bowel-disease/>
- NICE ulcerative-colitis severity and CRP guidance: <https://www.nice.org.uk/guidance/ng130/chapter/recommendations>
- Manchester University NHS critical laboratory limits: <https://mft.nhs.uk/the-trust/other-departments/laboratory-medicine/information-for-gps/laboratory-medicines-newsletter-for-gps/the-communication-of-critical-biochemistry-results/>
- Oxford University Hospitals faecal calprotectin interpretation: <https://www.ouh.nhs.uk/biochemistry/tests/tests-catalogue/calprotectin-faecal/>
- Gloucestershire Hospitals haemoglobin reference ranges: <https://www.gloshospitals.nhs.uk/our-services/services-we-offer/pathology/haematology/haematology-reference-ranges/>
- NICE CRP action threshold context: <https://www.nice.org.uk/advice/mib81/chapter/the-technology>
- Sheffield NHS CRP interpretation: <https://sheffieldlaboratorymedicine.nhs.uk/search-test.php?search=3079>

The version-1 catalogue is an application baseline, not a diagnosis. It is expected to be replaced by newly versioned rules when the study's formal clinical protocol is approved.

## Query Boundary and Authorization

Phase 1 exposes no new REST or MVC endpoint. `RedFlagEventQueryService` is the internal application boundary for later consumers.

It supports:

- current results for a patient;
- current highest severity;
- full run/event history including superseded entries;
- filtering by date and severity where needed by future consumers.

Patient queries resolve the profile from the authenticated principal. Clinical queries reuse `AccessControlService`; assigned nutrition specialists and physicians can read accessible patients, and administrators retain broader access. No controller may bypass this boundary when endpoints are added later.

## Security and Privacy

- Treat rules, facts, events, and snapshots as health-related data.
- Keep browser writes CSRF-protected through the existing security configuration.
- Never log normalized facts, matched snapshots, notes, laboratory values, symptom answers, session identifiers, or credentials.
- Persist only fact keys and values needed to explain a match.
- Do not include user names or email addresses in snapshots.
- Keep rule activation controlled and auditable.
- Preserve existing session authentication for ordinary REST and web flows.
- Do not broaden MCP scopes, bearer access, CSRF exclusions, or clinical visibility rules.

## Validation and Error Handling

Rule catalog validation rejects:

- an active version without approval metadata;
- no active version or multiple active versions for a required stable rule;
- empty rules or empty condition groups;
- unknown fact keys or source types;
- operand types incompatible with a fact;
- unsupported operators;
- negative lookback values or lookback on unsupported facts;
- duplicate stable group/condition ordering that would make serialization ambiguous.

Missing patient facts do not cause errors; the affected condition simply does not match. This includes sex-specific haemoglobin rules when sex is unavailable.

Evaluation failures use sanitized exception types with the existing generic API/MVC exception handling. Health values and rule inputs are not included in exception messages, application logs, or client-visible error details.

## Testing

### Pure engine tests

- decimal and stable-text comparison operators;
- `ANY` group and `ALL` condition semantics;
- missing facts;
- deterministic fact ordering and snapshot serialization;
- severity precedence and successful no-match evaluations;
- exact lower/upper boundary behavior for every seeded rule.

### Catalog and repository tests

- rule/version/group/condition persistence;
- unique stable keys and version numbers;
- one active version per rule;
- active-version approval requirements;
- valid lifecycle transitions and append-only transition history;
- typed operand constraints;
- current versus superseded evaluation runs;
- immutable event-to-rule-version references.

Use Testcontainers PostgreSQL for partial uniqueness, transactional supersession, and concurrency-sensitive constraints.

### Symptom service tests

- create and update trigger evaluation;
- severe pain and significant bleeding produce `EMERGENCY`;
- `ACTIVE_FLARE` produces `URGENT_REVIEW`;
- suspected flare and moderate deterioration produce `ROUTINE_REVIEW`;
- combined activity groups and stool-frequency boundaries;
- an update supersedes the preceding run;
- only matched facts appear in event snapshots;
- evaluator/event persistence failure rolls back the symptom write.

### Laboratory service tests

- create and update evaluate canonical values after conversion;
- all sodium, potassium, CRP, haemoglobin, magnesium, kidney, liver, albumin, and calprotectin boundaries;
- CRP severity at 45, immediately above 45, 100, 300, and adjacent values;
- seven-day symptom context is inclusive at exactly seven days and excluded outside it;
- missing sex skips only the sex-specific routine haemoglobin rules;
- removal creates a no-match run and supersedes previous events;
- evaluator/event persistence failure rolls back the laboratory write.

### Authorization and regression tests

- patients can query only their own data through the internal query boundary;
- assigned clinical staff can read accessible patients;
- unassigned staff are denied and admins can read all patients;
- current queries exclude superseded events while audit queries include them;
- existing symptom and laboratory REST response contracts remain unchanged;
- existing CSRF and role rules remain unchanged.

Final backend verification:

```bash
./gradlew test
```

## Completion Criteria

- Versioned, approved, active relational rules are present after Flyway migration.
- Symptom and laboratory writes synchronously evaluate the applicable active rules.
- All agreed severity thresholds and cross-source CRP context behave at exact boundaries.
- Each matched event records rule ID/version, minimal normalized inputs, severity, and timestamp.
- Updates and removals preserve history and expose only the latest run as current.
- Rule activation affects future writes only.
- Any evaluation persistence failure rolls back the associated clinical source write.
- No patient guidance, expert notification, messaging interception, notification-policy behavior, or rule-management UI/API is implied complete.
