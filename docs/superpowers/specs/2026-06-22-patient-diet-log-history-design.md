# Patient Diet Log History Design

## Context

Patients can already create or update a daily diet log from `/app/diet-logs` by choosing a date. The backend also exposes patient-owned diet-log listing through `DietLogService.listCurrentPatientLogs(...)` and `GET /api/diet-logs?from=&to=`.

The missing capability is in the patient web experience: a patient has no page where they can review the diet logs they have already created. Clinical staff have a list/detail workflow, but patients only have the selected-date edit form.

## Goals

- Add a separate patient history page for diet logs.
- Let patients view their own diet logs without exposing other patients' data.
- Keep `/app/diet-logs` focused on creating or updating one selected date.
- Let patients navigate from the edit page to history and from history back to the edit page.
- Limit the default history view so daily logging does not create an overly long initial page.
- Reuse the existing patient diet-log listing path where practical, extending the rendered history row data only where needed.

## Non-Goals

- Do not add staff review or staff editing changes.
- Do not add pagination unless the implementation finds the existing range filter insufficient.
- Do not add a new persistence model or Flyway migration.
- Do not change the existing diet-log create/update behavior.
- Do not redesign the application shell or broader patient menu.

## User Experience

Add `GET /app/diet-logs/history` as a separate patient page.

The page shows a date-range filter and a newest-first list of the authenticated patient's own diet logs. By default, the page loads the last 30 days based on the patient's timezone. For daily loggers, this keeps the initial list short while still showing recent activity.

Each row should show:

- Date
- Adherence
- Appetite
- Glucose
- Ketones
- `Open/Edit` action

Glucose and ketones should display compact measurement values for the log. If a log has no measurement of that type, show the existing `Not provided` text. If a log has more than one measurement of the same type, show the latest measurement by measured time for that type.

The `Open/Edit` action links to `/app/diet-logs?date=YYYY-MM-DD`, reusing the existing selected-date edit form.

If no logs match the selected range, show the existing empty state text: `No diet logs found`.

## Navigation

The existing `/app/diet-logs` page should add a secondary header action labelled `View history` that links to `/app/diet-logs/history`.

The new history page should add a secondary header action labelled `New log` that links to `/app/diet-logs`.

The sidebar does not need a new menu item in this design. The patient menu already reaches the diet-log area, and the form and history pages cross-link directly.

## Controller And Data Flow

Add a patient history handler to `WebDietLogController`.

Inputs:

- Optional `from` query parameter.
- Optional `to` query parameter.

Defaults:

- Resolve the patient timezone with the same current-patient timezone path used by the edit form.
- If `to` is absent, use the current date in the patient timezone.
- If `from` is absent, use `to.minusDays(29)` for a 30-day inclusive range.

Data:

- Reuse the current patient-owned diet-log range query and ownership checks.
- Render patient history row data containing the log date, adherence, appetite, glucose display value, ketone display value, and edit link target.
- Prefer a small web-focused patient-history response/assembler if the existing `DailyDietLogSummaryResponse` is not a good fit.
- Preserve the existing `GET /api/diet-logs?from=&to=` response shape unless the implementation deliberately updates and tests that API contract.

This should not require repository, entity, or database changes because the current service and repository already support patient-owned range listing.

## Error Handling

Invalid request parameters should use the application's existing web error behavior.

The service already validates date ranges for listing, including:

- `from` and `to` are required after controller defaults are applied.
- `from` must be on or before `to`.
- The range must not exceed 370 days.

The history page should not duplicate those business rules in the controller unless needed to compute defaults.

## Security

The history page must use the current-patient service path, not the clinical list path. Ownership must come from the authenticated user and their patient profile.

Patients should not be able to pass a patient profile ID or otherwise select another patient's logs.

Diet-log history rows are health-related data. Do not log full row contents, notes, session IDs, tokens, or other sensitive request state.

## Localization

Add English and Czech message bundle entries for new patient-facing labels:

- `View history`
- `New log`
- `Diet log history`
- `Glucose`
- `Ketones`

Reuse existing diet-log labels for table columns and the empty state where possible.

## Testing

Add or update web controller tests for:

- `/app/diet-logs/history` renders for a patient with the default 30-day range in the patient's timezone.
- Submitted `from` and `to` query parameters are passed to the patient-owned history listing service method.
- History rows render date, adherence, appetite, glucose, ketones, and `Open/Edit` links to `/app/diet-logs?date=...`.
- History rows render `Not provided` when glucose or ketone measurements are absent.
- The edit page contains a `View history` link.
- The history page contains a `New log` link.
- Invalid range errors continue through the existing web error path.

Existing service and API tests should remain valid. No new persistence test is expected unless implementation reveals a repository gap.

## Acceptance Criteria

- A patient can open `/app/diet-logs/history` and see only their own diet-log history rows.
- The default history view shows the last 30 days, newest first.
- A patient can filter history by `from` and `to`.
- History rows show date, adherence, appetite, glucose, ketones, and an open/edit action.
- A patient can open a history row in the existing edit form.
- A patient can navigate from the edit form to history and from history back to a new/current log form.
- No schema migration is required.
