# Laboratory and Biomarker Tracking Design

## Purpose

Implement [MET-14](https://nutritionintervention.atlassian.net/browse/MET-14): manual laboratory result entry, biomarker trends, and a clean future boundary for lab-report extraction.

This delivery completes FR-037 and FR-038. It prepares the result-ingestion model for FR-039 and FR-040 without adding report storage, upload endpoints, extraction, or confirmation UI. Patient-facing MCP tools are included because MCP became an application surface after MET-14 was originally written.

## Current Context

Metabion already has:

- Patient and assigned-clinical access rules in `AccessControlService`.
- Session-authenticated MVC and REST application surfaces.
- A patient-only MCP server using resource-bound bearer tokens and explicit scopes.
- `PatientMcpTools` delegating through `PatientAppFacade` to the same application services used by the rest of the product.
- Daily glucose and ketone entries in `DailyMeasurementEntry`.
- Server-rendered SVG daily trend charts.
- A planned laboratory-trends patient menu item.
- Flyway-owned persistence, with live migrations currently ending at `V18`.

Laboratory observations differ from daily glucose and ketone measurements. They are sparse, use a broad catalog of analytes and units, commonly arrive as a multi-result panel, carry report-specific reference ranges, and require correction history. Extending `DailyMeasurementEntry` would couple these semantics to diet-log measurement rules and enum-based units.

## Product Scope

In scope:

- A database-backed, migration-seeded laboratory test catalog.
- Manual entry of multiple biomarker results for one collection date.
- Patient entry and management of patient-created result sets.
- Entry and correction by assigned clinical staff and administrators.
- Optional reported lower and upper reference bounds per result.
- Preservation of reported values and units.
- Server-side conversion to a canonical value and unit for coherent trends.
- A focused, one-biomarker-at-a-time trend chart and result history.
- Patient and clinical MVC and REST workflows.
- Patient-facing MCP catalog, result-set, and trend tools.
- Immutable mutation audit history.
- Provenance and confirmation fields that future upload/extraction workflows can reuse.

Out of scope:

- Lab-report upload, file storage, or content serving.
- OCR or structured extraction.
- Extraction-review or confirmation UI.
- Clinical or staff MCP tools.
- Automated abnormal-result flags, thresholds, alerts, diagnoses, or medical guidance.
- Administrative catalog-management UI.
- External laboratory integrations.
- Combining laboratory trends with daily symptom, glucose, or ketone charts.

## Architecture

Add a dedicated laboratory subsystem alongside the existing daily-measurement subsystem.

Primary components:

- `LabCatalogService`: reads active definitions and validates supported units.
- `LabUnitConversionService`: performs definition-owned, server-controlled conversions.
- `LabResultService`: owns patient and clinical result-set reads and mutations, authorization, canonicalization, soft removal, and domain auditing.
- `LabTrendService`: validates ranges and builds one biomarker's canonicalized observations and history.
- Thin REST and MVC controllers: resolve request data and delegate to the same services.
- `PatientAppFacade`: exposes patient-owned lab operations to MCP by delegating to the exact same `LabCatalogService`, `LabResultService`, and `LabTrendService` used by REST and MVC.
- `PatientMcpTools`: performs bearer-token and scope checks, calls `PatientAppFacade`, and records coarse tool activity.
- A dedicated lab chart-model builder and SVG renderer: reuse existing accessibility, localization, theme, and responsive conventions without depending on `DailyTrendService` or its chart model.

There is one set of laboratory business rules. MVC, REST, and MCP do not implement separate validation, authorization, transactions, conversion, audit, or persistence behavior.

## Data Model

### Laboratory test definitions

`LabTestDefinition` represents a seeded catalog entry:

- Stable unique code.
- Localized label message key.
- Category.
- Canonical unit.
- Display precision.
- Active status.

Allowed input units and conversions are stored as definition-owned unit records. Each record identifies the input unit and a server-recognized conversion rule. Clients never submit conversion formulas or factors. A definition always supports its canonical unit. Alternative units are enabled only when their conversion is unambiguous and verified; unsupported alternatives are rejected rather than guessed.

The initial catalog includes:

- CRP.
- Fecal calprotectin.
- Hemoglobin.
- Ferritin.
- 25-hydroxyvitamin D.
- Vitamin B12.
- Albumin.
- Sodium.
- Potassium.
- Chloride.
- Magnesium.
- Calcium.
- ALT.
- AST.
- ALP.
- GGT.
- Total bilirubin.
- Creatinine.
- eGFR.
- Urea.

New definitions and verified unit conversions can be added through later migrations. No runtime catalog editor is added.

### Result sets

`LabResultSet` groups results from one laboratory visit or manual submission:

- Patient profile.
- Collection date.
- Optional notes.
- Provenance, initially `MANUAL`.
- Confirmation status, initially `CONFIRMED`.
- Creating user.
- Created and updated timestamps.
- Optimistic-lock version.
- Optional removal actor, timestamp, and reason.

Manual submissions are saved as `CONFIRMED`. A future extraction workflow can use the same application boundary with another provenance and `PENDING_CONFIRMATION` before a human accepts the values.

### Results

`LabResult` belongs to one result set and contains:

- Laboratory test definition.
- Reported value and unit.
- Server-derived canonical value and unit.
- Optional reported lower reference bound.
- Optional reported upper reference bound.

A result set may contain at most one active result for a given definition. Reference bounds use the reported unit. They are displayed as report context but are not interpreted as universal clinical thresholds.

### Audit history

Every create, correction, and removal writes an immutable laboratory audit event in the same transaction as the mutation. An event stores:

- Result-set identifier.
- Patient identifier.
- Action.
- Actor.
- Timestamp.
- Stable before and after snapshots produced from dedicated audit DTOs.

Snapshots are stored as application data, not written to operational logs or MCP tool-audit metadata. There is no hard-delete application path for result sets in this phase. Removed sets are excluded from ordinary reads while their audit history remains intact.

## Authorization

Patients:

- May read every non-removed result set belonging to their patient profile, including sets entered by clinical staff.
- May create manual result sets for themselves.
- May correct or remove only result sets they created.

Clinical staff:

- Nutrition specialists, physicians, and coordinators may read, create, correct, and remove results only when `AccessControlService` grants access to the patient through an active direct or cohort assignment.

Administrators:

- May read and manage results for any patient.

REST and MVC remain session authenticated with existing CSRF policy. MCP remains patient-only, bearer authenticated, resource bound, expiry/revocation checked, and scope authorized. MCP tools never accept a patient identifier.

## Patient Workflow

Activate the existing laboratory menu item and route it to `/app/labs`.

The page contains:

- A date-range filter, defaulting to the latest twelve months and limited to the existing maximum span of 370 days.
- A biomarker selector, defaulting to the most recently recorded biomarker.
- One full-size chart in the biomarker's canonical unit.
- A result-history table with collection date, reported value/unit, optional reference bounds, result-set context, provenance, and permitted actions.
- Empty states for no laboratory data and no data for the selected biomarker.

The create/edit form accepts one collection date, optional result-set notes, and one or more biomarker rows. Each row selects an active definition, one of its allowed units, a value, and optional lower and upper reference bounds. A lightweight browser enhancement updates unit choices after a biomarker selection, but the server independently validates every row and the form remains server-rendered.

## Clinical Workflow

Add `/app/clinical/labs` using the established assigned-patient selection pattern. The page presents the same focused trend and history for the selected accessible patient, plus create and correction actions.

Extract the existing patient-option behavior from `DietLogService` into a neutral `ClinicalPatientDirectoryService`. Diet logs, daily check-in review, daily trends, and laboratory workflows use this service without changing current access semantics. Laboratory code does not depend on a diet-log service merely to list clinical patients.

## REST Surface

Provide these thin patient endpoints:

- `GET /api/lab-tests`: list active definitions and their allowed units.
- `POST /api/lab-result-sets`: create a current-patient result set.
- `GET /api/lab-result-sets/{id}`: get one current-patient result set.
- `GET /api/lab-result-sets?from=&to=`: list current-patient result sets.
- `PUT /api/lab-result-sets/{id}`: correct a current-patient result set using its optimistic version.
- `POST /api/lab-result-sets/{id}/removal`: record an audited removal using the expected version and an optional reason.
- `GET /api/lab-trends/{testCode}?from=&to=`: get one current-patient biomarker trend.

Provide patient-scoped clinical equivalents below `/api/clinical/patients/{patientProfileId}/labs`. The clinical routes support the same list, create, get, correct, remove, and trend operations after patient-access authorization. Catalog lookup remains shared because definitions contain no patient data.

Patient endpoints infer the patient from the authenticated session. Clinical endpoints may identify a patient in the path but must authorize that identifier through `AccessControlService` before accessing data. Request DTOs use Jakarta Bean Validation at the untrusted boundary; services repeat domain-critical checks.

## MCP Surface

Add explicit patient laboratory scopes:

- `patient:lab:read`.
- `patient:lab:write`.

Read scope covers the catalog, result sets, and trends. Write scope covers create, correction, and audited removal.

Add patient MCP tools:

- `metabion_list_lab_tests`.
- `metabion_save_lab_result_set`.
- `metabion_get_lab_result_set`.
- `metabion_list_lab_result_sets`.
- `metabion_remove_lab_result_set`.
- `metabion_get_lab_trend`.

`metabion_save_lab_result_set` creates when no result-set identifier is supplied and corrects when an identifier and optimistic version are supplied. `metabion_remove_lab_result_set` accepts the identifier, expected version, and optional reason. List and trend tools require explicit `from` and `to` dates; the trend tool also requires a catalog test code.

Tool contracts reuse REST request and response records where those records are agent-readable. Small MCP-specific wrappers are allowed only when they improve parameter clarity. Every tool:

1. Resolves `PatientAccessTokenAuthentication`.
2. Requires the narrow lab scope.
3. Delegates through `PatientAppFacade` to the same lab services as MVC and REST.
4. Records success or coarse failure through `PatientAccessAuditService` without recording values or request bodies.

The new scopes must appear consistently in supported-scope enumeration, OAuth metadata and consent, dynamic client registration validation, authorization-code grants, access tokens, refresh-token rotation, and insufficient-scope challenges. Existing clients do not receive the new scopes automatically; patients must explicitly authorize them.

## Trend Presentation

Render one biomarker at a time because laboratory measurements use incompatible native units and scales. Do not overlay or normalize unrelated biomarkers.

The chart:

- Uses canonical values and the definition's canonical unit.
- Orders observations by collection date.
- Shows exact date, canonical value, and unit in accessible point details.
- Keeps isolated values visible.
- Handles identical values with a nonzero y-axis span.
- Displays a localized no-data state.
- Uses a responsive SVG wrapper and theme-aware styles consistent with existing charts.

The associated history preserves and displays the reported value and unit. Reported reference bounds remain in the history rather than forming a continuous chart band because they may differ by laboratory and observation.

## Validation and Conversion

- Collection date is required and cannot be in the future.
- A result set requires at least one result.
- Result definitions must be active and unique within the set.
- Values and reference bounds use bounded `BigDecimal` precision.
- Current seeded definitions accept zero or positive numeric values within the configured precision; they do not encode clinical normal ranges.
- Units must be explicitly allowed by the selected definition.
- Canonical value and unit are always derived on the server.
- Clients cannot submit or override canonical fields or conversion rules.
- When both reference bounds are present, the lower bound cannot exceed the upper bound.
- Corrections revalidate and recalculate the complete submitted result set.
- Optimistic locking prevents silent lost updates.

Every enabled alternative-unit conversion requires focused tests for direction, precision, and rounding. A conversion is not enabled until its factor and expected precision have been verified from an authoritative source.

## Error Handling

- Invalid dates, duplicate biomarkers, unsupported units, invalid bounds, and invalid values return `400 Bad Request`.
- Unauthenticated requests follow the existing session or bearer authentication behavior.
- Missing role, assignment, ownership, or MCP scope returns `403 Forbidden` without exposing another patient's data.
- Missing or ordinarily hidden records return `404 Not Found`.
- Stale optimistic-lock versions return `409 Conflict` and require the caller to reload before retrying.
- Internal exceptions are logged without laboratory payloads and are not exposed as stack traces.
- MCP failures use stable, agent-readable errors and the existing insufficient-scope challenge behavior.

Mutations, canonical conversion, result replacement, soft removal, and audit-event creation share one transaction. A failed audit write rolls back the laboratory mutation.

## Persistence and Migration

Use `V19__laboratory_biomarker_tracking.sql`, based on current live repository state. The migration creates:

- Laboratory test definitions.
- Allowed-unit and conversion records.
- Laboratory result sets.
- Laboratory results.
- Laboratory audit events.
- Foreign keys, uniqueness constraints, lookup indexes, and seeded definitions.

Flyway remains the schema owner and Hibernate remains validate-only. Existing measurement data is not migrated or reinterpreted.

## Localization and Accessibility

- Catalog labels use stable localized message keys rather than database-stored English display text.
- All new UI, validation, chart, unit, provenance, and empty-state text is added to both English and Czech bundles with aligned keys.
- The chart has a localized accessible name and description.
- Every plotted point exposes its date, biomarker, value, and unit without relying on color.
- Forms use explicit labels, error associations, and keyboard-accessible controls.
- Result tables retain semantic headings and expose correction/removal actions with clear accessible names.

## Testing Strategy

### Persistence tests

- Flyway schema and seeded catalog load successfully.
- Entity mappings, foreign keys, uniqueness, lookup indexes, and optimistic versioning behave as designed.
- Soft removal excludes ordinary repository reads.
- Audit snapshots persist atomically with mutations.

### Service tests

- Catalog reads return only active definitions and allowed units.
- Every enabled conversion produces the expected canonical value and rounding.
- Unsupported units and duplicate definitions are rejected.
- Optional one-sided and two-sided reference bounds are accepted; inverted bounds are rejected.
- Future collection dates are rejected.
- Patients can create and manage their own result sets but cannot modify clinical-created or other-patient sets.
- Assigned clinical staff can manage accessible patient results; unassigned staff cannot.
- Administrators can manage all patient results.
- Corrections and removals create accurate immutable before/after audit snapshots.
- Mutation and audit failures roll back atomically.
- Concurrent corrections produce an optimistic-lock conflict rather than a lost update.
- Trend reads use canonical values, preserve reported history, and validate the range.

### MVC and REST tests

- Patient and clinical routes enforce roles, ownership, assignments, CSRF, and patient isolation.
- Multi-result create, correction, and removal flows render expected redirects and errors.
- Catalog selection, unit selection, date filtering, default biomarker selection, chart output, history, and empty states render correctly.
- English and Czech message keys remain aligned.
- The planned laboratory menu item becomes active only for intended roles.

### Chart tests

- Multiple observations are ordered and positioned by collection date.
- One observation, identical values, sparse dates, and empty data produce valid accessible SVG.
- Axis labels and point details use the canonical unit and clinically useful precision.
- Theme and narrow-layout behavior follows existing chart conventions.

### MCP and OAuth tests

- Each tool delegates through `PatientAppFacade` to the shared laboratory services.
- Read and write tools require their respective scopes.
- Tools are limited to the bearer token's patient and never accept patient identifiers.
- Responses omit internal audit snapshots and unrelated patient or staff data.
- Tool audit metadata contains the operation and token identity but no laboratory values.
- OAuth metadata and consent expose the new scopes.
- Dynamic client registration accepts supported lab scopes and rejects unknown ones.
- Authorization-code and refresh-token flows preserve only authorized scope subsets.
- Missing lab scopes return the existing stable insufficient-scope response.

Run focused repository, service, controller, chart, MCP, and OAuth tests during implementation, followed by `./gradlew test`.

## Delivery Notes

This is one implementation scope because the catalog, result model, shared services, web/API surfaces, MCP facade additions, chart, and scope propagation are coupled around the same laboratory rules. Report upload and extraction remain separate later specifications that consume the provenance-aware result-set boundary.
