# Patient MCP Server Design

## Overview

Metabion should expose a patient-focused MCP server so Codex, Claude, and similar clients can help a patient operate the app. The MCP server is the first consumer of a reusable patient token authentication layer that can later support mobile clients. Existing browser/session authentication stays in place.

The first implementation should expose all currently available patient-owned functionality through MCP tools, while preserving the existing REST and MVC behavior.

## Goals

- Add a patient MCP surface for Claude/Codex over a localhost HTTP MCP endpoint first.
- Keep the MCP tool implementation transport-neutral enough to add stdio later.
- Add patient bearer-token authentication as a shared foundation for MCP now and mobile later.
- Reuse existing patient business logic and DTOs instead of reimplementing app behavior.
- Support all existing patient-facing functionality: profile, diet logs, measurements, photos, symptoms, trends, onboarding, and education.
- Keep existing session-based API and MVC authentication working unchanged.

## Non-Goals

- Replace existing session-based authentication.
- Expose staff, admin, or clinical review functionality through patient MCP tools.
- Add arbitrary user impersonation.
- Expose secrets, token values, password hashes, session ids, or raw internal stack traces.
- Build a complete mobile login UX in this phase.

## Recommended Approach

Use an incremental shared facade approach:

1. Add the reusable patient token/authentication layer.
2. Add MCP patient tools that delegate to existing services through a small patient app facade where helpful.
3. Keep current REST controllers intact.
4. Let future REST/mobile work reuse the same token-auth layer and, where useful, the same facade.

This keeps the first MCP implementation small enough to build safely, but avoids a throwaway auth model.

## Architecture

```text
Claude/Codex MCP client
  -> localhost Streamable HTTP MCP endpoint
  -> patient bearer token auth
  -> MCP patient tools
  -> patient app facade / existing services
  -> repositories + current domain rules
```

The MCP server should run inside the Spring Boot application for the first version. This allows MCP tools to use the same service layer, validation, transactions, persistence, localization-ready DTOs, and access-control conventions already used by the REST controllers.

HTTP is the first transport because it fits Spring Boot and can evolve toward staging. Tool implementations should not depend on HTTP-specific details so stdio can be added later if Codex or Claude Desktop workflows need it.

## Patient Token Authentication

Add a reusable patient access-token model. Token records should include:

- owning `User`
- client type/name, such as `MCP_CLAUDE`, `MCP_CODEX`, or later `MOBILE_IOS`
- hashed token value only
- granted scopes
- display label, such as `Claude on Petr's Mac`
- creation timestamp
- expiry timestamp
- last-used timestamp
- revoked timestamp and optional revocation reason

Token creation returns the plaintext token once. After that, only the hash is stored. Tokens must be revocable and expirable. Verification must not log plaintext token values.

Bearer-token authentication should resolve the token to a Spring `Authentication` for the owning patient. The authentication should include normal role authorities, such as `ROLE_PATIENT`, plus scope authorities derived from the token.

Token auth should be stateless and should not require browser CSRF cookies. It must not weaken existing browser/session authentication.

## Scopes

Use explicit patient-owned scopes:

- `patient:profile:read`
- `patient:profile:write`
- `patient:diet-log:read`
- `patient:diet-log:write`
- `patient:diet-photo:read`
- `patient:diet-photo:write`
- `patient:symptom:read`
- `patient:symptom:write`
- `patient:onboarding:read`
- `patient:onboarding:write`
- `patient:education:read`
- `patient:education:write`
- `patient:trend:read`

MCP tools should check the narrowest scope that covers the requested operation. Missing, invalid, revoked, or expired tokens return unauthorized. Missing scopes, non-patient users, disabled users, locked users, and unverified users return forbidden.

## MCP Tool Surface

Tool names should be stable, explicit, and grouped around patient tasks.

### Identity

- `metabion_patient_me`

Returns email, patient profile id, roles, token client label, and granted scopes.

### Profile

- `metabion_get_patient_profile`
- `metabion_update_patient_profile`

These mirror `AccountController` behavior and `PatientProfileForm`.

### Diet Logs And Measurements

- `metabion_save_diet_log`
- `metabion_get_diet_log`
- `metabion_list_diet_logs`
- `metabion_add_diet_measurement`

These mirror the patient-owned diet log endpoints and existing DTOs such as `DailyDietLogRequest`, `DailyDietLogResponse`, `DailyDietLogSummaryResponse`, `DailyMeasurementEntryRequest`, and `DailyMeasurementEntryResponse`.

### Diet Photos

- `metabion_upload_diet_photo`
- `metabion_get_diet_photo_content`

Photo upload should be transport-neutral. The tool can accept base64 content with filename and content type. A local file path mode may be useful for local development, but base64 should be the production-shaped contract.

Photo tools must preserve existing ownership checks and file validation. Responses should include metadata and content only for photos owned by the authenticated patient.

### Symptoms And Trends

- `metabion_get_active_symptom_questionnaire`
- `metabion_save_symptom_check_in`
- `metabion_get_symptom_check_in`
- `metabion_list_symptom_check_ins`
- `metabion_get_daily_trends`

These mirror the current symptom questionnaire, symptom check-in, and daily trend endpoints.

### Onboarding

- `metabion_submit_onboarding`
- `metabion_get_latest_onboarding`
- `metabion_list_onboarding_history`

These mirror current patient onboarding submission behavior. Clinical review tools are out of scope for the patient MCP surface.

### Education

- `metabion_list_education_modules`
- `metabion_get_education_module`
- `metabion_complete_education_lesson`
- `metabion_uncomplete_education_lesson`

These mirror current published education module and lesson-completion behavior. Education content management tools are out of scope for the patient MCP surface.

## DTO And Output Rules

Inputs should closely follow existing request DTOs so REST, MCP, and future mobile behavior stay aligned.

Outputs should reuse existing response DTOs where they are already appropriate, with small MCP-specific wrappers only when useful for agent clarity. Responses should avoid:

- plaintext tokens
- password hashes
- session ids
- internal storage keys unless already safe and necessary
- stack traces
- unrelated staff/admin data
- data for other patients

## Error Handling

Errors should be agent-friendly and safe:

- Validation errors include field-level messages where available.
- Authentication errors stay generic.
- Missing scopes return a stable forbidden error.
- Domain errors map to stable error codes/messages.
- Internal exceptions are logged server-side but not exposed as stack traces.
- Cross-patient access should not reveal other patient data.

## Audit

Record token-mediated patient activity separately from browser sessions. Audit records should include:

- patient user id/email
- token id or client label, never the token value
- operation/tool name
- timestamp
- success or failure
- coarse failure reason
- transport/client metadata when available

Audit records must not include sensitive request bodies, photo contents, plaintext tokens, credentials, password hashes, or session ids.

## Security And Configuration

- Existing session-based MVC/API authentication remains the default browser path.
- Patient token auth is added alongside it.
- MCP HTTP endpoints should bind to localhost for v1.
- MCP should be disabled unless explicitly configured.
- MCP endpoints should have CSRF behavior appropriate for stateless bearer-token calls, without changing browser CSRF protections.
- Token authentication should have its own rate limiting where practical.
- Patient token scopes should be visible when listing connected clients.

## Testing Strategy

Add focused coverage for:

- token persistence, hashing, expiry, revocation, and last-used updates
- valid, invalid, expired, revoked, and missing-scope token requests
- disabled, locked, unverified, and non-patient user rejection
- representative MCP tools from each group
- existing service behavior reuse for diet logs, symptoms, onboarding, and education
- photo upload size/type/content ownership behavior
- existing session-auth and CSRF tests continuing to pass

Run `./gradlew test` after implementation changes.

## Phasing

### Phase 1

- Add token domain model, repository, migration, service, and security filter.
- Add a minimal token issuance path for development and internal testing, such as an authenticated patient endpoint or admin/dev-only command, while keeping the full connected-client UI out of scope.
- Add MCP configuration and localhost HTTP endpoint.
- Add patient MCP tools for all current patient-owned app functionality.
- Add tests for token auth and representative tools.

### Phase 2

- Add patient-facing UI or API for listing, creating, and revoking connected clients.
- Improve audit visibility.
- Add stdio transport if local client workflows need it.

### Phase 3

- Reuse the token layer for mobile authentication.
- Expand token lifecycle UX for mobile device management.
- Evaluate stronger OAuth-style consent if third-party clients become part of the product.
