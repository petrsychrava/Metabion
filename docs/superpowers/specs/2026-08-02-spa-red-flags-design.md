# SPA Red-Flag Display Design

## Context

The red-flag detection foundation
(`2026-07-29-red-flag-detection-foundation-design.md`) evaluates symptom
check-ins and laboratory result sets synchronously, and the REST/MCP API
delivery (`2026-08-01-red-flag-rest-mcp-api-design.md`) exposes patient
endpoints:

- `GET /api/red-flags/current` — cohesive snapshot: `highestSeverity` plus the
  current trigger events;
- `GET /api/red-flags/history` — cursor-paginated trigger-event history with
  optional inclusive `from`/`to` local dates, `severity`, opaque `cursor`, and
  `size` (default 25, max 100).

Both endpoints are session-authenticated patient GETs; the principal determines
the patient. The API spec defines the browser flow as: the SPA submits a
symptom or laboratory write, the backend commits the source and the red-flag
evaluation atomically, and the SPA then requests the current snapshot and
renders any warning. This delivery implements that frontend half.

The patient SPA (`frontend/`, Vue 3 + TypeScript + Vite) already has
established patterns for API modules, Pinia stores, list views with date
filters, i18n with feature-scoped enum labels, and Vitest + MSW tests. Cursor
pagination, a severity select filter, and severity badge styling have no
existing precedent in the SPA.

## Goals

- Warn the patient immediately after a symptom or laboratory save that leaves
  active red flags, via a global banner for urgent and emergency severities.
- Show current flags of all severities on the dashboard.
- Provide a dedicated `/red-flags` view with the current snapshot and a
  filterable, cursor-paginated history.
- Refresh the current snapshot after the SPA's own symptom check-in and
  laboratory writes.
- Render neutral, localized rule names, severity labels, and source-type
  labels.
- Follow existing SPA conventions for API modules, stores, views, i18n, and
  tests.

## Non-Goals

- Safety or medical guidance of any kind (per both backend specs).
- Backend changes of any kind; the REST contracts are consumed as-is.
- Acknowledgement, resolution, or escalation UI. `current` reflects the latest
  evaluation run for a source record, never clinical resolution, and
  superseded events must not be labeled as resolved.
- Expert/clinical views (staff use the Thymeleaf workspace, not this SPA).
- Polling, push, or other live-update machinery.
- MCP or OAuth changes.

## Selected Approach

One `/red-flags` view plus a global banner rendered by `AppShell`, with a
Pinia store holding the current snapshot.

Rejected alternatives:

- Two separate current/history views without a store splits one cohesive
  concept, duplicates severity and label rendering, and cannot support
  refresh-after-save.
- Banner-only with history folded into an existing view buries the history
  under an unrelated page and does not match the backend's two-endpoint
  design.
- A post-save modal or an inline notice confined to the edit views warns
  immediately but either introduces a brand-new UI pattern or leaves the
  warning invisible everywhere else.

## Snapshot Freshness Semantics

The store is a cache of the last known server state, not a live subscription.
Red flags can change out of band (MCP writes, other sessions). The agreed
semantics:

- `AppShell` calls `refreshCurrent()` once when the authenticated layout
  mounts (app load); `DashboardView` and `RedFlagsView` also call it on
  mount, so ordinary navigation to those pages self-heals staleness.
- Symptom and laboratory save flows call `refreshCurrent()` after a
  successful write, so a flag triggered by the patient's own submission
  appears immediately.
- No polling and no focus listeners; a user navigating pages other than the
  dashboard and red-flags view may see a stale banner until the next
  refresh, which is accepted.

The REST current snapshot cannot attribute flags to a specific write (only
the MCP composite response can, and REST write contracts deliberately exclude
it). Immediate post-save notices therefore always present the overall current
state, never "this submission triggered X".

## Architecture

### Types (`frontend/src/types/api.ts`)

Mirror the backend patient projection records exactly:

```ts
export type RedFlagSeverity = 'ROUTINE_REVIEW' | 'URGENT_REVIEW' | 'EMERGENCY'
export type RedFlagSourceType = 'SYMPTOM_CHECK_IN' | 'LAB_RESULT_SET'

export interface PatientRedFlagEvent {
  eventId: number
  ruleKey: string
  severity: RedFlagSeverity
  detectedAt: string
  sourceType: RedFlagSourceType
  sourceId: number
  current: boolean
  supersededAt: string | null
}

export interface PatientRedFlagSnapshot {
  highestSeverity: RedFlagSeverity | null
  flags: PatientRedFlagEvent[]
}

export interface PatientRedFlagHistoryPage {
  items: PatientRedFlagEvent[]
  nextCursor: string | null
}
```

The patient projection never includes rule versions, matched-input facts,
evaluation-run IDs, source operations, or matched-group keys; these types
must not grow those fields.

### API module (`frontend/src/api/redFlags.ts`)

Plain exported object following `api/symptoms.ts`:

- `getCurrent(): Promise<PatientRedFlagSnapshot>` → `GET /api/red-flags/current`
- `getHistory(params): Promise<PatientRedFlagHistoryPage>` →
  `GET /api/red-flags/history` with `from`, `to`, `severity`, `cursor`, and
  `size` appended only when set (simple conditional string building; no new
  query-string helper).

Both are GET-only, so no CSRF handling is needed; `apiFetch` provides session
auth, 401 handling, and `ApiError` mapping as usual.

### Store (`frontend/src/stores/redFlags.ts`)

Pinia store modeled on `stores/auth.ts`:

- state: `snapshot: PatientRedFlagSnapshot | null`, `loading: boolean`,
  `loadFailed: boolean`;
- `refreshCurrent(): Promise<void>` — fetches and replaces `snapshot`; on
  failure it sets `loadFailed` and never throws, so a banner failure cannot
  break a save flow;
- `clear()` — resets state, called on logout alongside the auth store reset.

History is deliberately not in the store; it is view-local state in
`RedFlagsView` (accumulated items plus cursor), the only consumer.

## UI

### Banner (`components/RedFlagBanner.vue`)

One reusable severity-colored strip: red for `EMERGENCY`, amber for
`URGENT_REVIEW`, neutral blue/gray for `ROUTINE_REVIEW`, each with `dark:`
twins, following the existing status-color precedent in `DashboardView`.
Content: localized severity label, count of current flags, and a router-link
to `/red-flags`. No rule details, no guidance text. The component accepts a
`severities: RedFlagSeverity[]` prop and renders only when the snapshot's
`highestSeverity` is included in it and `loadFailed` is false. There is no
dismiss or acknowledgement state (the API has none); the banner disappears
when a later refresh returns no matching current flags.

Placement and severity policy:

- `AppShell` renders the banner below the header with
  `severities = ['URGENT_REVIEW', 'EMERGENCY']` on every authenticated page
  except `/` and `/red-flags`. An urgent or emergency flag therefore
  interrupts the patient immediately after a triggering save — both edit
  views stay on the same page after saving — and stays visible app-wide until
  the underlying data changes and a refresh observes it.
- `DashboardView` renders the banner with all three severities, so routine
  flags are visible without persistent app-wide noise.
- `RedFlagsView` renders no banner; the view itself is the detail.

### Red-flags view (`views/RedFlagsView.vue`)

Registered as `red-flags` under the `AppShell` children with
`meta: { requiresAuth: true }`; a nav entry is added to `AppShell`'s `links`
computed array. The view follows the existing list-view skeleton
(`useApiError`, `loading` ref, `onMounted(load)`).

Two sections:

1. **Current flags** — reads the store snapshot (refreshed on mount). Table:
   localized rule name, severity badge, detected date/time, source type.
   Empty state shows a "no current flags" message.
2. **History** — standard `from`/`to` date inputs defaulting to the last 30
   days with `dateRangeError` client-side validation and the existing
   `errors.date_range_*` keys, plus a severity `<select>` (All / Routine /
   Urgent / Emergency). Rows show rule name, severity, detected time, source,
   and status: `current`, or "superseded" when `supersededAt` is set (never
   labeled "resolved"). A "Load more" button appears while `nextCursor` is
   non-null and appends the next page; changing any filter resets the items
   and cursor and reloads the first page.

### Save-flow refresh

After a successful save in `CheckInEditView` and `LabResultSetEditView` (and
after laboratory removal where the SPA exposes it), call
`redFlagsStore.refreshCurrent()` fire-and-forget. Both edit views stay on the
same page after saving, so the `AppShell` banner appears in place as soon as
the refresh resolves. A refresh failure never blocks or errors the save.

### Rendering details

- Severity badge colors come from a small severity-to-class map colocated in
  the view, matching the copy-paste Tailwind idiom; no shared CSS is
  extracted.
- `detectedAt` and `supersededAt` are ISO instants; render them via a new
  small `utils/dateTime.ts` helper using `toLocaleString` for the active
  locale, since the SPA has no datetime-formatting helper today.

## Internationalization

Add to `en.json` and `cs.json`, keeping keys aligned:

- `nav.redFlags`;
- a `redFlags` section: view title, current/history headings, empty states,
  table headers, load-more label, and the "superseded" status label;
- `redFlags.severity.{ROUTINE_REVIEW,URGENT_REVIEW,EMERGENCY}` and
  `redFlags.sourceType.{SYMPTOM_CHECK_IN,LAB_RESULT_SET}` using the
  feature-scoped enum pattern (like `checkIn.FlareState`);
- `redFlags.rules.<ruleKey>` with neutral descriptive names for every seeded
  rule key from the foundation catalogue (all `SYM_*` and `LAB_*` keys),
  rendered with a `te()`-based fallback to the raw key so rules added later
  by the backend never render blank.

No guidance or advice strings are added anywhere.

## Error Handling and Privacy

- View-level failures use the standard `useApiError` composable and the
  existing red error box; history filter errors (HTTP 400) surface through the
  normal `errors.<code>` mapping.
- Store `refreshCurrent()` never throws; failures set `loadFailed`, the banner
  hides, and save flows are unaffected.
- The SPA never logs flag contents beyond existing conventions, and adds no
  new persistence of health data outside the session-authenticated API calls.

## Testing

Vitest + MSW, following existing patterns:

- `tests/api/redFlags.test.ts` — URL and query-parameter construction for
  current and history, including omitted optional parameters.
- `tests/stores/redFlags.test.ts` — refresh stores the snapshot, failure sets
  `loadFailed` without throwing, `clear()` resets.
- `tests/components/RedFlagBanner.test.ts` — hidden with no flags and when
  `loadFailed`; renders only when `highestSeverity` is in the `severities`
  prop; severity color class per level; link target.
- `tests/components/AppShell.test.ts` — urgent and emergency snapshots show
  the banner, routine does not; banner hidden on `/` and `/red-flags`; update
  nav-link assertions for the new entry.
- `tests/views/DashboardView.test.ts` — banner shows for any severity
  including `ROUTINE_REVIEW` when flags exist.
- `tests/views/RedFlagsView.test.ts` — MSW-stubbed endpoints; renders current
  flags and the empty state; renders history rows; date-range validation
  blocks API calls; the severity filter changes the query; "Load more"
  appends a page and hides at `nextCursor: null`; unknown `ruleKey` falls
  back to the raw key.
- Save-flow tests for `CheckInEditView` and `LabResultSetEditView` — a
  successful save triggers a current-snapshot refetch.

Verification:

```bash
cd frontend && npm run test && npm run typecheck
```

No backend changes are made; `./gradlew test` remains the backend gate and is
unaffected.

## Completion Criteria

- An urgent or emergency current flag shows the banner on every authenticated
  page except `/` and `/red-flags`, immediately after a triggering save and
  until a refresh observes it cleared.
- The dashboard shows the banner for current flags of any severity, including
  `ROUTINE_REVIEW`.
- `/red-flags` shows the current snapshot and a filterable, cursor-paginated
  history reachable from the nav.
- Saving a symptom check-in or laboratory result set refreshes the snapshot.
- Rule names, severities, and source types are localized in English and Czech
  with a raw-key fallback.
- Superseded history entries are labeled "superseded", never "resolved".
- No guidance text, acknowledgement lifecycle, polling, or backend change is
  introduced.
- `npm run test` and `npm run typecheck` pass.
