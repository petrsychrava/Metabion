# Red-Flag REST and MCP API Design

## Context

The red-flag detection foundation implemented by
`2026-07-29-red-flag-detection-foundation-design.md` synchronously evaluates
symptom check-ins and laboratory result sets, persists versioned trigger events,
preserves superseded evaluation history, and provides an internal
`RedFlagEventQueryService` authorization boundary.

That foundation deliberately added no REST or MCP read API and left existing
write response contracts unchanged. As a result, the SPA has no supported way
to load red flags, clinical users cannot review them through REST, and an MCP
client can save data that triggers a red flag without receiving the outcome.

## Goals

- Expose current red flags and filtered, paginated red-flag history to
  authenticated patients.
- Expose the same capabilities with full audit evidence to assigned clinical
  staff and administrators.
- Return the highest severity alongside the current flag snapshot.
- Add patient MCP tools for current red flags and history.
- Return the exact red-flag evaluation outcome from MCP symptom and laboratory
  writes so an MCP client does not need to discover that it should perform a
  follow-up read.
- Add a dedicated `patient:red-flags:read` token scope for MCP reads.
- Reuse the existing evaluation, access-control, token, OAuth, and audit
  boundaries.
- Preserve ordinary REST symptom and laboratory write response contracts.

## Non-Goals

- Safety or medical guidance for patients.
- Expert notifications, in-app alerts, email, or push delivery.
- Acknowledgement, assignment, resolution, or escalation lifecycle for a
  red-flag event.
- Frontend implementation.
- Rule authoring, activation, or administration APIs.
- New clinical inputs or red-flag rules.
- Retrospective evaluation or backfill.
- MCP access to clinical audit fields.

## Selected Approach

Use a snapshot-and-history API with separate patient and clinical routes.

The current endpoint returns one cohesive snapshot containing both current
triggered flags and the highest severity. The history endpoint returns only
actual trigger events, using stable cursor pagination. This avoids separate
current/highest requests that could observe different database states.

Rejected alternatives:

- Three separate current, highest-severity, and history resources add a request
  and can return inconsistent current and highest results across calls.
- One flexible collection endpoint controlled by mode flags has a less explicit
  contract and variable response shape.
- Embedding red flags in existing REST write responses couples unrelated
  contracts and breaks the foundation's compatibility guarantee.

MCP is an intentional exception to the last point. An MCP host is not assumed
to issue a follow-up REST read after a write, so red-flag-triggering MCP writes
return a composite result containing the exact evaluation outcome.

## API Entry Points

### Patient REST

- `GET /api/red-flags/current`
- `GET /api/red-flags/history`

The authenticated principal determines the patient profile. No patient
identifier is accepted from the caller.

### Clinical REST

- `GET /api/clinical/patients/{patientProfileId}/red-flags/current`
- `GET /api/clinical/patients/{patientProfileId}/red-flags/history`

Nutrition specialists and physicians require an active direct or cohort
assignment. Administrators retain broad read access. Coordinators are not
clinical red-flag readers.

### Patient MCP

Add:

- `metabion_get_current_red_flags`
- `metabion_list_red_flag_history`

Enrich:

- `metabion_save_symptom_check_in`
- `metabion_save_lab_result_set`
- `metabion_remove_lab_result_set`

The existing save tool handles both laboratory creation and update, so both
operations receive the enriched result.

## Component Boundaries

Add thin `PatientRedFlagController` and `ClinicalRedFlagController`
controllers. They delegate to `RedFlagEventQueryService`; controllers do not
query repositories or reproduce access checks.

`RedFlagEventQueryService` remains the central read and authorization
boundary. Extend it with event-centric current and history operations for the
current patient and for a clinical patient. It resolves patient-local date
bounds, applies pagination and filters, and returns internal read models.

Use separate patient and clinical response assemblers:

- the patient assembler produces the restricted patient response used by both
  patient REST and MCP;
- the clinical assembler adds immutable rule and matched-input audit evidence.

`PatientAppFacade` exposes the patient current/history queries and enriched
write operations to `PatientMcpTools`. `PatientMcpTools` performs scope
checks and metadata-only tool auditing but contains no red-flag query or
comparison logic.

## REST Response Contracts

### Current snapshot

The patient current response has this shape:

```json
{
  "highestSeverity": "URGENT_REVIEW",
  "flags": [
    {
      "eventId": 701,
      "ruleKey": "LAB_CRP_HIGH",
      "severity": "URGENT_REVIEW",
      "detectedAt": "2026-08-01T10:15:30Z",
      "sourceType": "LAB_RESULT_SET",
      "sourceId": 91,
      "current": true,
      "supersededAt": null
    }
  ]
}
```

When there are no current flags, `highestSeverity` is `null` and `flags`
is empty.

The clinical current response uses the same envelope. Each clinical flag adds:

- `evaluationRunId`;
- `sourceOperation`;
- `ruleVersion`;
- `matchedGroupKey`;
- `matchedInputs` as a structured list of facts.

`matchedInputs` must be JSON objects and arrays in the wire response, not an
escaped JSON string. Each fact retains the foundation's source type, source ID,
fact key, observation date, decimal or text value, and unit.

The patient and MCP projections never include rule version, matched group, or
matched-input facts. The stable `ruleKey` is retained as a machine-readable
code for client-side labeling.

### History

History returns:

```json
{
  "items": [],
  "nextCursor": null
}
```

Supported query parameters are:

- optional inclusive `from` and `to` ISO local dates;
- optional `severity`;
- optional opaque `cursor`;
- optional `size`, default 25 and maximum 100.

Dates are interpreted in the target patient's configured timezone. The
repository receives an inclusive start instant and exclusive end instant so
the complete local `to` date is included.

Results use the stable descending order
`triggeredAt DESC, eventId DESC`. The cursor represents that pair and is
opaque to clients. Repositories fetch `size + 1` rows to determine
`nextCursor`; no total-count query is required.

History contains current and superseded trigger events but never successful
no-match evaluation runs. `supersededAt` is derived from the successor
evaluation time.

## Meaning of Current and Superseded

`current` preserves the foundation's source-record semantics: an event is
current when it belongs to the latest evaluation run for that symptom check-in
or laboratory result set. An update or removal of that same source record may
supersede it.

`current` does not mean that a clinician has acknowledged the event or that
the patient's clinical condition has resolved. This delivery adds no
acknowledgement or resolution state and must not label supersession as clinical
resolution.

## MCP Contracts

### Read tools

`metabion_get_current_red_flags` returns the patient current snapshot.

`metabion_list_red_flag_history` accepts the same date, severity, cursor, and
size inputs as patient REST and returns the same patient history page.

Both tools require `patient:red-flags:read`. Their descriptions state that
results are health-related data and must not be expanded into invented medical
guidance.

### Triggering write tools

Each red-flag-triggering MCP write returns a composite response:

```json
{
  "result": {},
  "redFlagOutcome": {
    "highestSeverity": "URGENT_REVIEW",
    "currentFlags": [
      {
        "eventId": 701,
        "ruleKey": "LAB_CRP_HIGH",
        "severity": "URGENT_REVIEW",
        "detectedAt": "2026-08-01T10:15:30Z",
        "sourceType": "LAB_RESULT_SET",
        "sourceId": 91,
        "current": true,
        "supersededAt": null
      }
    ],
    "clearedRuleKeys": []
  }
}
```

`result` contains the existing symptom check-in or laboratory result
response. Laboratory removal returns its explicit removal status in
`result`.

`redFlagOutcome` always appears, including after a successful no-match
evaluation. `currentFlags` contains flags matched by the exact new evaluation.
`clearedRuleKeys` contains stable rule keys that matched the preceding
evaluation for the same source record but do not match the new evaluation. A
rule that matches both evaluations remains in `currentFlags` and is not
reported as cleared.

The write tools retain their existing symptom-write or laboratory-write scope.
Returning the simplified safety outcome is part of the authorized write
operation. It does not require the separate read scope.

Tool descriptions instruct MCP clients to disclose any returned red flags
immediately and not invent safety guidance. The server can guarantee that the
outcome is present in tool output but cannot force an external MCP host to
render it.

Changing these MCP result schemas is intentional. MCP clients rediscover tool
schemas when connecting; ordinary REST response schemas remain unchanged.

## Evaluation Outcome Flow

`RedFlagEvaluationService` continues to evaluate and persist within the
clinical source transaction. Its symptom and laboratory evaluation operations
will additionally return a domain-level outcome containing:

- the current evaluation's matched rules and highest severity;
- the preceding evaluation's matched stable rule keys;
- the set difference used for `clearedRuleKeys`.

This outcome is built from the evaluation being persisted. It is not produced
by re-running rule evaluation and does not depend on a follow-up query.

`SymptomTrackingService` and `LabResultService` retain their existing
public response methods for REST. Each service adds an enriched operation for
`PatientAppFacade`, with both paths sharing one internal mutation flow. The
same source write, evaluation run, trigger events, and returned outcome commit
atomically.

The browser flow remains command followed by query:

1. The SPA submits a symptom or laboratory write.
2. The backend commits the source and red-flag evaluation atomically.
3. After success, the SPA requests `GET /api/red-flags/current`.
4. A later frontend delivery renders any warning.

The additional read is safe because the evaluation commits before the write
response is returned.

## Query and Persistence Flow

Current and history repositories query trigger events joined to their
evaluation run, rule version, stable rule, matched group, and successor run.
They do not load all evaluation runs and filter them in memory.

Current queries require `evaluationRun.current = true`. History queries
include current and superseded events and apply optional severity and
patient-local time bounds. Keyset predicates use the same timestamp and event
ID ordering as the response cursor.

The current service loads the event set once and derives
`highestSeverity` from it. It does not issue a separate highest-severity
query, which keeps the envelope internally consistent.

Clinical matched-input JSON is deserialized through the existing typed
`RedFlagMatchedInputSnapshot` contract. Patient and MCP responses do not
deserialize or expose it.

No new persistence tables or columns are required.

## MCP Scope and OAuth Compatibility

Add `PATIENT_RED_FLAG_READ("patient:red-flags:read")` to
`PatientAccessTokenScope`.

The existing OAuth metadata endpoints automatically advertise it because they
enumerate `PatientAccessTokenScope`. Scope collection tables use unconstrained
string columns, so no Flyway migration is required. Token issuance, refresh
rotation, resource binding, expiry, and revocation remain unchanged.

No existing token gains the new scope automatically. Personal access tokens
must be reissued with it.

Dynamically registered OAuth clients persist their allowed scope set and the
application has no client-metadata update endpoint. An existing dynamic client
that needs the red-flag read tools must register again with
`patient:red-flags:read`, then run a new authorization flow and obtain patient
consent. Existing clients can still receive enriched results from authorized
write tools without the new read scope.

## Authorization and Privacy

- Patient REST derives the patient profile from the session principal.
- Clinical REST permits assigned nutrition specialists and physicians and
  permits administrators without an assignment.
- Coordinators, unassigned clinical staff, and unrelated patients are denied.
- Controllers never bypass `RedFlagEventQueryService`.
- MCP retains the existing bearer resource, expiry, revocation, scope, and
  localhost exposure checks.
- Patient and MCP payloads exclude normalized input values and audit-only rule
  details.
- Clinical matched inputs are returned only after clinical access succeeds.
- REST responses set `Cache-Control: no-store`.
- Red-flag facts, snapshots, input values, session identifiers, and credentials
  are never added to logs or MCP audit records.
- Existing CSRF exclusions and MCP scopes are not broadened beyond the new
  explicit read scope.

## Validation and Error Handling

- No flags returns HTTP 200 with a null highest severity and an empty
  `flags` list for current snapshots, or an empty `items` list for history.
- Invalid date bounds, unsupported severity, malformed cursor, or size outside
  1 through 100 returns the existing sanitized HTTP 400 response.
- Missing session or bearer authentication returns HTTP 401.
- Wrong role, missing assignment, or missing MCP scope returns HTTP 403.
  Missing MCP scope uses the existing `insufficient_scope` challenge.
- An unassigned non-admin clinical caller receives HTTP 403 without learning
  whether a patient identifier exists. An administrator requesting a
  nonexistent patient receives HTTP 404.
- Persisted snapshot corruption or repository failure returns a generic HTTP
  500 response. Snapshot contents and parsing details are not sent to clients
  or included in logs.
- MCP success and failure operations use the existing metadata-only
  `PatientAccessAuditService`.

## Testing

### Repository tests

- Current queries return only events on current runs.
- History includes current and superseded trigger events.
- Successful no-match runs never appear.
- Severity and patient-local date filters use exact inclusive boundaries.
- Cursor pagination has no gaps or duplicates, including equal timestamps.
- Event order is `triggeredAt DESC, eventId DESC`.
- Successor evaluation time maps to `supersededAt`.

### Query and assembler tests

- A patient can read only their own flags.
- Assigned nutrition specialists and physicians can read an accessible
  patient.
- Administrators can read any existing patient.
- Coordinators and unassigned staff are denied before event data is read.
- Highest severity is derived from the current event set.
- Empty current and history responses are stable.
- Patient and MCP projections omit audit-only fields.
- Clinical projection parses structured matched inputs.
- Corrupt matched-input JSON fails generically without including the JSON in an
  exception message.
- Date ranges use the target patient's timezone.

### REST controller tests

- Patient and clinical routes delegate with the correct identity and filters.
- Current and history JSON contracts match the approved shapes.
- Invalid filters and cursors return sanitized errors.
- Authentication and role failures retain existing JSON error behavior.
- Responses include `Cache-Control: no-store`.
- Existing symptom and laboratory write responses are unchanged.

### Evaluation and source-service tests

- Create and update outcomes contain the exact newly persisted matches.
- A rule present before and after an update is current, not cleared.
- A preceding rule absent from the new evaluation appears in
  `clearedRuleKeys`.
- A successful no-match evaluation returns an explicit empty outcome.
- Laboratory removal returns an empty current set and the genuinely cleared
  rule keys.
- Evaluation or outcome persistence failure still rolls back the source write.
- REST and enriched MCP service paths share the same mutation behavior.

### MCP tests

- Read tools require `patient:red-flags:read`.
- Current and history tools validate and forward filters.
- Tool successes and failures are audited without health values.
- Symptom and laboratory write tools return the composite result.
- Removal returns an explicit result and outcome.
- Write outcomes require only the existing write scope.
- MCP responses never contain matched-input audit data.
- Tool descriptions require immediate red-flag disclosure and forbid invented
  guidance.

### OAuth and token regression tests

- The new scope parses and is advertised in both metadata documents.
- Personal and OAuth token issuance can grant it explicitly.
- Refresh rotation preserves exactly the granted scope set.
- Dynamic registration accepts the new scope and continues to reject unknown
  scopes.
- Existing tokens do not gain the new scope.

Final verification:

```bash
./gradlew test
```

## Completion Criteria

- Patient REST returns a cohesive current snapshot and filtered, paginated
  trigger-event history.
- Authorized clinical REST returns the same capabilities with structured audit
  evidence.
- Public APIs never return successful no-match evaluation runs.
- MCP exposes scoped current and history reads.
- MCP symptom and laboratory writes return the exact evaluation outcome without
  a second evaluation or follow-up query.
- Existing REST write contracts remain unchanged.
- Patient ownership, clinical assignment, administrator access, MCP scope, and
  privacy rules are covered by tests.
- No safety guidance, expert notification, acknowledgement lifecycle, rule
  management, or frontend implementation is implied complete.
