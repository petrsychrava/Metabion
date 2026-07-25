# Patient SPA Frontend — Design

Date: 2026-07-24
Status: Approved (design review)

## Goal

A standalone single-page application (SPA) that gives patients a modern web client for the
Metabion REST API, covering **patient flows only**. Staff/clinical/admin areas remain in the
existing Thymeleaf application. No backend code changes in this phase.

## Decisions (from brainstorming)

| Question | Decision |
|---|---|
| Client type | Standalone SPA |
| Scope | Patient flows only |
| Stack | Vue 3 + TypeScript + Vite |
| Styling | Tailwind CSS |
| i18n | English + Czech via vue-i18n |
| Testing | Vitest + MSW (no e2e this phase) |
| Integration | Monorepo `frontend/` + Vite dev proxy, hand-written typed API client |

## Architecture

New self-contained Node project in `frontend/` in this repository. The root `package.json`
(stub, no usable scripts) is left untouched.

```text
frontend/
├── package.json            # vue, vue-router, pinia, vue-i18n, tailwind, vitest, msw
├── vite.config.ts          # dev proxy: /api → localhost:8080
├── index.html
├── src/
│   ├── main.ts             # app bootstrap: i18n, pinia, router
│   ├── api/                # typed API layer (http.ts + one module per domain)
│   ├── stores/             # Pinia: auth + patient data stores
│   ├── router/             # vue-router with auth guards
│   ├── views/              # route-level screens
│   ├── components/         # shared UI (forms, tables, charts)
│   ├── i18n/               # en.json, cs.json
│   └── types/              # TS interfaces mirroring backend DTO records
└── tests/                  # Vitest + MSW handlers mirroring the API
```

### Serving model

- **Development:** `vite` on :5173 proxies `/api` to Spring Boot on :8080. Cookies stay
  same-origin through the proxy, so the existing `SameSite=Strict` session cookie works
  unchanged. No CORS configuration is needed. (OAuth and `/.well-known` endpoints are not
  proxied — the SPA does not use them; see Out of scope.)
- **Production:** `vite build` output. Wiring the bundle into Spring static resources (or a
  Gradle task) is a follow-up, not part of this phase.

### Rejected alternatives

- **Separate deployment + CORS:** requires `SecurityConfig` changes and `SameSite=None`
  cookies, weakening the current security posture for no gain at this stage.
- **OpenAPI codegen (springdoc):** adds a backend dependency and build tooling; overkill for
  ~15 patient endpoints.

## Auth & API layer

### Auth flow (session-based, mirrors backend exactly)

1. `POST /api/auth/login` with JSON `{email, password}` — CSRF-exempt, sets the session
   cookie. Response is `AUTHENTICATED` (with roles) or `MFA_REQUIRED`. MFA is a no-op backend
   extension point, so the SPA renders `MFA_REQUIRED` as an informational placeholder only.
2. On app boot and after login, `GET /api/auth/me` resolves identity into the Pinia auth
   store; 401 → redirect to `/login`.
3. Before the first mutating request, `GET /api/csrf` once; the returned `{token, headerName}`
   is cached in the API wrapper and sent as a header on every POST/PUT/DELETE.
4. `POST /api/auth/logout` + store reset.
5. Register / verify / forgot / reset-password screens call the CSRF-exempt `/api/auth/*`
   endpoints and display the generic anti-enumeration responses as-is.

### API layer structure

- `http.ts`: single wrapper around `fetch` — `credentials: 'same-origin'`, JSON handling,
  CSRF header injection, uniform error normalization.
- One module per domain on top of it: `authApi`, `accountApi`, `dietLogApi`, `symptomApi`,
  `labApi`, `educationApi`, `onboardingApi`.
- `types/`: hand-written TS interfaces mirroring the backend DTO records in
  `com.metabion.dto`.
- Router guards: routes require auth via the auth store; patient-only routes redirect staff
  roles to an info screen pointing at the staff (Thymeleaf) UI.

## Screens (all behind auth guard unless noted)

- **Public:** Login, Register, Verify-email result, Forgot password, Reset password.
- **Dashboard** (`/` after login): today's snapshot — diet log status, symptom check-in
  status, quick links.
- **Diet logs:** daily editor (meals, deviations, adherence/appetite, notes, glucose/ketone
  measurements) → `POST /api/diet-logs`; photo upload via
  `POST /api/diet-log-photos/uploads` (multipart) with thumbnails from
  `GET /api/diet-log-photos/{id}/content`; history list from `GET /api/diet-logs?from&to`.
- **Symptom check-in:** renders the active questionnaire
  (`GET /api/symptom-questionnaires/active`), submits `POST /api/symptom-check-ins`; past
  check-in list (`GET /api/symptom-check-ins?from&to`).
- **Trends:** `GET /api/trends/daily?from&to` as line charts (Chart.js via vue-chartjs) —
  symptom score, glucose, ketones; respects the response's `glucoseUnit`.
- **Labs:** lab test catalog (`GET /api/lab-tests`), result-set list + entry/edit form
  (`POST/PUT /api/lab-result-sets`) with optimistic-lock `version` handling (409 → refresh
  prompt), removal request (`POST /api/lab-result-sets/{id}/removal`), per-test trend chart
  (`GET /api/lab-trends/{testCode}?from&to`).
- **Onboarding:** submission form (`POST /api/onboarding/submissions`), view latest/history.
- **Education:** module list (`GET /api/education/modules`), module/lesson reader
  (`GET /api/education/modules/{moduleSlug}`), lesson complete/incomplete toggles
  (`POST/DELETE .../lessons/{lessonSlug}/complete`).
- **Account:** profile view/edit (`GET/PUT /api/account/profile`); access-token management
  (`/api/account/access-tokens`) — issue (plaintext shown once in a copy dialog), list,
  revoke.

## Error handling

`http.ts` normalizes backend errors (`GlobalExceptionHandler` shapes) into
`{status, message, fieldErrors?}`:

- **400** with field-error map → per-field messages under form inputs.
- **401** → global auth-store reset + redirect to `/login`.
- **403** → "no access" screen.
- **409** (lab optimistic lock) → prompt to refresh and retry.
- **429** (rate limit) → toast with retry-later message.

All UI strings go through vue-i18n (`en.json`, `cs.json`); keys mirror backend message keys
where a backend message exists.

## Testing

Vitest + MSW. MSW handlers replicate the real API contract, including the CSRF bootstrap and
the 401/400/409/429 response shapes.

Coverage targets:

- `http.ts` — CSRF injection, error normalization for each status above.
- Auth store + router guard — login/logout/me, 401 redirect, `MFA_REQUIRED` placeholder.
- One representative component test per domain module: diet log editor, symptom questionnaire
  render + submit, lab result form optimistic-lock conflict, education completion toggle,
  access-token issue dialog.

No Playwright/e2e in this phase.

## Out of scope

- Staff/clinical/admin UI (stays in Thymeleaf).
- OAuth authorization-code flow and MCP bearer-token clients (the SPA uses session auth;
  access-token management is only for issuing/revoking tokens as a patient account feature).
- Real MFA challenge flow (backend extension point is a no-op).
- Production serving integration (Gradle/static resources wiring) — follow-up.
