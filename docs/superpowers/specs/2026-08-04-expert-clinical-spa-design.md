# Expert Clinical SPA — Design

Date: 2026-08-04
Status: Approved (design review)

## Goal

Extend the existing patient SPA (`frontend/`, Vue 3 + TypeScript + Vite) with an **expert
clinical area** for nutrition specialists and physicians (admins included), covering
**clinical oversight only**. The Thymeleaf workspace coexists unchanged; the SPA becomes
the primary expert experience. Coordinators are out of scope for this round.

## Decisions (from brainstorming)

| Question | Decision |
|---|---|
| App shape | Extend the existing SPA with role-based areas (no separate staff app) |
| Feature scope | Clinical oversight only — education content authoring stays in Thymeleaf |
| Thymeleaf clinical pages | Coexist, SPA primary for experts; nothing removed |
| Roles admitted | `NUTRITION_SPECIALIST`, `PHYSICIAN`, `ADMIN` (matches backend clinical authorization); coordinators get the existing staff notice |
| Landing / IA | Monitor-first: clinical overview + drill-down patient workspace + onboarding review queue |
| Overview patient set | **Monitored patients only** (active cohort-staff or direct expert assignment) for every role — no admin bypass on this aggregate (data minimization; bounded set, no paging). Admins keep full targeted access via existing endpoints |
| Merged daily check-in view | New REST endpoint wrapping `ClinicalDailyCheckInService` (no client-side merge) |
| Overview data | New aggregate endpoint `GET /api/clinical/overview` (no N+1 client composition) |

## Architecture

Single SPA, two role-based areas:

- **Patient area** stays as today at `/…` (dashboard, diet logs, check-ins, trends, labs,
  onboarding, education, account).
- **Expert area** under `/clinical/…`, mirroring the API namespace (`/api/clinical/*`) and
  the Thymeleaf convention (`/app/clinical/*`):
  - `/clinical` — monitoring overview (expert landing page)
  - `/clinical/patients/{patientProfileId}` — patient workspace with tab subroutes:
    `check-ins` (list + `{date}` detail), `trends`, `labs` (list + entry/edit),
    `red-flags`, `onboarding`
  - `/clinical/onboarding` — review queue + `/{submissionId}` review detail

### Router guard

Role-aware route meta: `roles: ['NUTRITION_SPECIALIST', 'PHYSICIAN', 'ADMIN']` on the
clinical tree. After `fetchMe()` resolves identity:

- Expert/admin → after login land on `/clinical`; patient-only routes redirect to `/clinical`.
- Patient → clinical routes redirect to `/`.
- Coordinator → `/staff-notice` (unchanged; still links to `/app` in Thymeleaf).
- `StaffNoticeView` remains for coordinators and any future non-patient/non-expert role.

### Shells

- New `ClinicalShell.vue`: nav with Overview, Onboarding review, Education library;
  locale + theme selects and logout reused from the existing shell patterns.
- `AppShell.vue` stays the patient shell.
- The existing **published education library** routes become shared (any authenticated
  role) — experts read the library; authoring is out of scope.
- Experts get **no Account page**: profile and access-token APIs are patient-only;
  theme/language preferences already live in the shell selects.

### Frontend structure

- New API modules (`frontend/src/api/clinical.ts`, plus clinical variants for labs/red
  flags as needed) on the existing `http.ts` wrapper — session cookie auth, CSRF bootstrap
  via `GET /api/csrf`, uniform `ApiError` normalization, unchanged.
- New types in `frontend/src/types/api.ts` mirroring the backend clinical DTOs.
- New `clinical.*` i18n section added to both `en.json` and `cs.json` (key parity is
  test-enforced).
- Charts reuse `LineChart.vue` (chart.js); server-side SVG trend renderers are not ported.
- Photos render via `GET /api/diet-log-photos/{id}/content` (already staff-readable).

## Backend additions (the only server work)

Two new endpoints, all following the established pattern: URL rule `/api/**`
authenticated + service-layer role check (NS/PHYS/ADMIN) + `AccessControlService`
per-patient scoping. **No `SecurityConfig` URL changes** — `/api/clinical/*` relies on
service-level checks, same as the existing clinical controllers.

1. `GET /api/clinical/overview` → one summary row per **monitored** patient:
   - patient profile id, email
   - current red-flag count + highest severity
   - latest flare state + latest symptom score (with date)
   - latest ketone value (with measurement date)
   - latest adherence level
   - last activity date (max of diet-log and check-in dates)
   - count of onboarding submissions awaiting clinical review

   *Monitored* = patients the caller is actively assigned to (cohort-staff assignment or
   direct expert assignment), resolved for **every role including admin** — the overview
   is a personal workload view, so the usual admin access bypass deliberately does not
   apply to this aggregate. New `ClinicalOverviewService` composes existing query
   services. The result set is bounded by assignment size (tens, not thousands): no
   paging, sorting (needs-attention-first) happens client-side.
2. `GET /api/clinical/daily-check-ins?patientProfileId=&from=&to=` and
   `GET /api/clinical/daily-check-ins/{patientProfileId}/{date}` — thin wrappers over
   `ClinicalDailyCheckInService`, whose DTOs (`ClinicalDailyCheckInSummaryResponse` /
   `ClinicalDailyCheckInDetailResponse`) already exist but are web-only today.

Everything else the expert screens need already exists via REST: clinical diet-logs
(+ photo content), symptom check-ins + daily trends, clinical labs including writes,
clinical red flags (SPA-shaped, cursor-paginated), onboarding list/detail/review, lab
catalog, auth/csrf/preferences.

## Screens & data flow

**Clinical overview (`/clinical`)** — single fetch of `GET /api/clinical/overview`; one
row per monitored patient showing identity (email), red-flag badge (highest severity +
count), flare state + latest symptom score, latest ketones, latest adherence, last
activity date (highlighted stale when older than 2 days, since logging is daily),
awaiting-review onboarding badge. Client-side sort: red flags → active/suspected flare →
stale → ok. Row click opens the patient workspace. No cross-view store; single-fetch view
like the existing history views. Users with no assignments (e.g. an admin who monitors no
one) see an empty state pointing at the onboarding review queue; admins can still reach
any patient workspace directly (`/clinical/patients/{id}`) or from the queue — the
existing targeted-access model is unchanged.

**Patient workspace (`/clinical/patients/{patientProfileId}`)** — tabbed layout, patient
identity in the header:

- *Check-ins*: date-range list from `GET /api/clinical/daily-check-ins` (default 7 days,
  like Thymeleaf); row → merged-day detail (`…/{date}`): diet-log meals, deviations,
  adherence, measurements + symptom answers; photo thumbnails → full view via
  `GET /api/diet-log-photos/{id}/content`.
- *Trends*: `GET /api/clinical/trends/daily` (default 30 days) via `LineChart.vue` —
  symptom score, glucose (respecting the response's unit), ketones.
- *Labs*: result-set list (`GET …/labs/result-sets?from=&to=`, 12-month default range
  matching the Thymeleaf clinical labs page), per-test trend chart
  (`GET …/labs/trends/{testCode}`), and the write flows: entry/edit form (lab catalog
  from `GET /api/lab-tests`; optimistic-lock `version`, 409 → refresh prompt, same
  pattern as the patient labs view) and soft-removal with reason.
- *Red flags*: current snapshot (`…/red-flags/current`) + cursor-paginated history
  (`…/red-flags/history`) — a clinical variant of the patient `RedFlagsView`; the
  app-wide red-flag banner stays patient-side only.
- *Onboarding*: that patient's submission history + detail + review form
  (`POST …/submissions/{id}/review` with `REVIEWED` / `NEEDS_FOLLOW_UP` + notes).

**Onboarding review queue (`/clinical/onboarding`)** — cross-patient list from
`GET /api/clinical/onboarding/submissions?context=&status=` with filters; row → the same
review detail as the workspace tab. The one task-queue page.

## Error handling

Reuses the existing machinery unchanged: `http.ts` normalization (400 field errors →
`FieldError`, 401 → global auth expiry + login redirect, 403 → no-access screen, 409 →
lab refresh prompt, 429 → retry toast) and `useApiError` with new `errors.*` codes as
needed. Cursor-pagination and refresh guards follow the generation-counter pattern from
the red-flags store work.

## Testing

- **Backend**: MVC slice tests for the two new controllers (role matrix: NS/PHYS/ADMIN
  allowed, PATIENT/COORDINATOR → 403, anonymous → 401) and service tests for
  `ClinicalOverviewService` aggregation and monitored-patient scoping — including that
  admins without assignments get an empty overview (no bypass). Verified via
  `./gradlew test`.
- **Frontend**: Vitest + MSW (handlers for the new and reused clinical endpoints).
  Coverage: router-guard role matrix (patient/expert/coordinator/admin routing), overview
  view (sort order, badges, stale highlighting), one representative test per workspace
  tab (check-in detail render, lab form 409 flow, red-flag cursor pagination, onboarding
  review submit), i18n key parity. Verified via `npm run test` and `npm run typecheck`.

## Out of scope

- Education content authoring (drafts, review, approve/publish) — stays in Thymeleaf.
- Coordinator features (cohorts, memberships, staff/expert assignments) and staff
  invitations.
- Removing or changing any Thymeleaf pages or their controllers.
- Production serving integration for the SPA bundle (same open follow-up as the patient
  SPA).
- Any changes to OAuth, MCP, or patient bearer-token surfaces.
- Cross-patient red-flag or check-in queue pages beyond the overview row badges.
- A cross-patient patient-directory endpoint (no SPA consumer once the overview covers
  navigation) and any admin bulk-all-patients overview variant — deliberately excluded
  on data-minimization grounds.
