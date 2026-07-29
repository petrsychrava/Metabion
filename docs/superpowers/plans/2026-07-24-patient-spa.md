# Patient SPA Frontend Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a standalone Vue 3 + TypeScript SPA in `frontend/` that gives patients a web client for the Metabion REST API (patient flows only).

**Architecture:** Session-authenticated SPA, no backend changes. Vite dev server proxies `/api` to Spring Boot on :8080 so `SameSite=Strict` session cookies work unchanged. A single `fetch` wrapper (`src/api/http.ts`) handles JSON, lazy CSRF-token bootstrap, and error normalization; one API module per domain sits on top. Pinia for auth state, vue-router with guards, vue-i18n (en/cs), Tailwind CSS v4 for styling.

**Tech Stack:** Vue 3, TypeScript, Vite 7, Pinia 3, vue-router 4, vue-i18n 11, Tailwind CSS 4, Chart.js 4 + vue-chartjs 5, Vitest 3 + MSW 2 + @vue/test-utils 2, Node 22 (Homebrew).

**Spec:** `docs/superpowers/specs/2026-07-24-patient-spa-design.md`

## Global Constraints

- **No backend changes.** Everything lives in the new `frontend/` directory. The root `package.json` is a stub and stays untouched.
- **Node:** Node 22 via `brew install node@22` (keg-only; add `/opt/homebrew/opt/node@22/bin` to PATH). Ask the user before running `brew install` — it modifies the system outside the project.
- **Auth:** session cookie only. All requests use `credentials: 'same-origin'`. Login/register/forgot/reset POSTs are CSRF-exempt on the backend — pass `csrf: false` for them.
- **CSRF:** before the first mutating authenticated request, `GET /api/csrf` → `{token, headerName}`; cache it and send the token under `headerName` on every POST/PUT/DELETE. Reset the cache after login and logout.
- **Error shapes (from `GlobalExceptionHandler`, verified against source):**
  - 400 validation: `{"error":"validation_failed","fields":{"<field>":"<message>"}}`
  - 401 bad login: `{"error":"invalid_credentials"}` (rate-limited login also returns this — there is **no 429 anywhere**; other rate-limited endpoints return 200 `{"status":"ok"}` and must be treated as success)
  - 401 unauthenticated `/api/auth/me`: **empty body**
  - 403: `{"error":"forbidden"}` or `{"error":"insufficient_scope"}`
  - 404: `{"error":"not_found"}`; 409 optimistic lock: `{"error":"conflict"}`
  - 400 invalid verify/reset token: `{"error":"invalid_token"}`; 503: `{"error":"mail_unavailable"}`
- **No 429 handling** — do not write any; the backend never emits it. (This corrects the spec.)
- **MFA:** `LoginResponse.status === "MFA_REQUIRED"` renders an informational placeholder only. The backend MFA service is a no-op; do not build a challenge flow.
- **i18n:** every user-facing string via vue-i18n keys in `src/i18n/en.json` and `src/i18n/cs.json`. Error codes map to `errors.<code>` keys.
- **Commits:** commit after every task. Imperative messages, e.g. `Add patient SPA scaffold`.
- **Verification:** each task lists exact commands. Full gates: `npm run test`, `npm run typecheck`, `npm run build` must pass at the end of every task that touches `src/`.
- **Dates:** the API uses ISO `yyyy-MM-dd` dates and ISO-8601 instants. "Today" in the SPA means the browser-local date formatted as `yyyy-MM-dd`.
- **Roles:** SPA is patient-only. Authenticated non-patient roles are redirected to `/staff-notice` (points them at the Thymeleaf staff UI at `/app`).

## File Structure

```text
frontend/
├── package.json                 # scripts: dev, build, typecheck, test
├── vite.config.ts               # vue + tailwind plugins, /api proxy, vitest config, @ alias
├── tsconfig.json
├── index.html
├── src/
│   ├── main.ts                  # bootstrap: pinia, i18n, router
│   ├── App.vue                  # plain <router-view>
│   ├── style.css                # @import "tailwindcss";
│   ├── env.d.ts                 # vite/client reference
│   ├── types/api.ts             # ALL DTO-mirroring interfaces (single source of truth)
│   ├── api/http.ts              # fetch wrapper, ApiError, CSRF cache
│   ├── api/auth.ts              # authApi
│   ├── api/account.ts           # accountApi, accessTokenApi
│   ├── api/dietLogs.ts          # dietLogApi
│   ├── api/symptoms.ts          # symptomApi
│   ├── api/labs.ts              # labApi
│   ├── api/onboarding.ts        # onboardingApi
│   ├── api/education.ts         # educationApi
│   ├── stores/auth.ts           # useAuthStore
│   ├── router/index.ts          # routes + beforeEach guard
│   ├── i18n/{index.ts,en.json,cs.json}
│   ├── composables/useApiError.ts
│   ├── components/
│   │   ├── AppShell.vue         # authed layout: nav, locale switch, logout
│   │   ├── FieldError.vue       # per-field validation message
│   │   ├── LineChart.vue        # vue-chartjs wrapper
│   │   └── PhotoUpload.vue      # diet photo upload + thumbnails
│   └── views/
│       ├── LoginView.vue  RegisterView.vue  ForgotPasswordView.vue
│       ├── ResetPasswordView.vue  VerifyEmailView.vue  StaffNoticeView.vue
│       ├── DashboardView.vue
│       ├── AccountProfileView.vue  AccessTokensView.vue
│       ├── DietLogEditView.vue  DietLogHistoryView.vue
│       ├── CheckInEditView.vue  CheckInListView.vue
│       ├── TrendsView.vue
│       ├── LabResultSetsView.vue  LabResultSetEditView.vue  LabTrendsView.vue
│       ├── OnboardingView.vue
│       ├── EducationListView.vue  EducationModuleView.vue
└── tests/
    ├── setup.ts                 # MSW server lifecycle + resetCsrfToken
    ├── msw/server.ts
    ├── api/http.test.ts
    ├── stores/auth.test.ts
    ├── router/guards.test.ts
    └── views/*.test.ts          # one representative component test per domain
```

Route table (all under `createWebHistory()`, guard in `router/index.ts`):

| Path | View | Meta |
|---|---|---|
| `/login` `/register` `/forgot-password` `/reset-password` `/verify` | public views | none |
| `/` | AppShell → DashboardView | `requiresAuth` |
| `/staff-notice` | StaffNoticeView | `requiresAuth, allowStaff` |
| `/diet-logs` `/diet-logs/:date` | history / editor | `requiresAuth` |
| `/check-ins` `/check-ins/:date` | list / editor | `requiresAuth` |
| `/trends` | TrendsView | `requiresAuth` |
| `/labs` `/labs/new` `/labs/:id` `/labs/trends` | lab views | `requiresAuth` |
| `/onboarding` | OnboardingView | `requiresAuth` |
| `/education` `/education/:moduleSlug` | education views | `requiresAuth` |
| `/account` `/account/access-tokens` | account views | `requiresAuth` |

---

### Task 1: Frontend scaffold (Node, Vite, Tailwind, TS)

**Files:**
- Create: `frontend/package.json`, `frontend/vite.config.ts`, `frontend/tsconfig.json`, `frontend/index.html`, `frontend/src/main.ts`, `frontend/src/App.vue`, `frontend/src/style.css`, `frontend/src/env.d.ts`

**Interfaces:**
- Produces: runnable Vite project; `@/*` → `src/*` alias; scripts `dev`/`build`/`typecheck`/`test`. Later tasks add source files only.

- [ ] **Step 1: Install Node 22 (ask user first — system-wide change)**

```bash
brew install node@22
echo 'export PATH="/opt/homebrew/opt/node@22/bin:$PATH"' >> ~/.zshrc
export PATH="/opt/homebrew/opt/node@22/bin:$PATH"
node --version
```

Expected: `v22.x.y`. All subsequent `npm` commands in this plan run in shells with that PATH exported.

- [ ] **Step 2: Create `frontend/package.json`**

```json
{
  "name": "metabion-patient-spa",
  "private": true,
  "version": "0.1.0",
  "type": "module",
  "scripts": {
    "dev": "vite",
    "build": "vite build",
    "typecheck": "vue-tsc --noEmit",
    "test": "vitest run"
  }
}
```

- [ ] **Step 3: Install dependencies**

```bash
cd frontend
npm install vue@^3 vue-router@^4 pinia@^3 vue-i18n@^11 chart.js@^4 vue-chartjs@^5
npm install --save-dev vite@^7 @vitejs/plugin-vue@^6 typescript@~5.9 vue-tsc@^3 \
  tailwindcss@^4 @tailwindcss/vite@^4 vitest@^3 @vue/test-utils@^2 msw@^2 jsdom@^27
```

Expected: install completes without `ERESOLVE` errors. If a pinned major does not resolve, use the latest available major of that package and note it in the commit message.

- [ ] **Step 4: Create `frontend/vite.config.ts`**

```ts
/// <reference types="vitest/config" />
import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import tailwindcss from '@tailwindcss/vite'

export default defineConfig({
  plugins: [vue(), tailwindcss()],
  resolve: {
    alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) },
  },
  server: {
    port: 5173,
    proxy: { '/api': 'http://localhost:8080' },
  },
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./tests/setup.ts'],
  },
})
```

- [ ] **Step 5: Create `frontend/tsconfig.json`**

```json
{
  "compilerOptions": {
    "target": "ES2022",
    "module": "ESNext",
    "moduleResolution": "bundler",
    "strict": true,
    "noUnusedLocals": true,
    "noEmit": true,
    "skipLibCheck": true,
    "types": ["vite/client", "vitest/globals"],
    "baseUrl": ".",
    "paths": { "@/*": ["src/*"] }
  },
  "include": ["src/**/*.ts", "src/**/*.vue", "tests/**/*.ts", "vite.config.ts"]
}
```

- [ ] **Step 6: Create entry files**

`frontend/index.html`:

```html
<!doctype html>
<html lang="en">
  <head>
    <meta charset="UTF-8" />
    <meta name="viewport" content="width=device-width, initial-scale=1.0" />
    <title>Metabion</title>
  </head>
  <body>
    <div id="app"></div>
    <script type="module" src="/src/main.ts"></script>
  </body>
</html>
```

`frontend/src/env.d.ts`:

```ts
/// <reference types="vite/client" />
```

`frontend/src/style.css`:

```css
@import "tailwindcss";
```

`frontend/src/App.vue` (final form — stays a bare router-view):

```vue
<template>
  <router-view />
</template>
```

`frontend/src/main.ts` (placeholder — replaced in Task 3):

```ts
import { createApp } from 'vue'
import App from './App.vue'
import './style.css'

createApp(App).mount('#app')
```

- [ ] **Step 7: Verify build**

Run: `cd frontend && npm run build`
Expected: `✓ built in ...` and a `dist/` directory.

- [ ] **Step 8: Commit**

```bash
git add frontend
git commit -m "Add patient SPA scaffold (Vite, Vue 3, Tailwind)"
```

---

### Task 2: API types, fetch wrapper, MSW test infrastructure

**Files:**
- Create: `frontend/src/types/api.ts`
- Create: `frontend/src/api/http.ts`
- Create: `frontend/tests/msw/server.ts`, `frontend/tests/setup.ts`
- Test: `frontend/tests/api/http.test.ts`

**Interfaces:**
- Produces: `apiFetch<T>(path: string, options?: ApiFetchOptions): Promise<T>`; `ApiError extends Error { status: number; code: string; fields?: Record<string,string> }`; `resetCsrfToken(): void`; all shared DTO types in `@/types/api`. Every later API module and test consumes these.

- [ ] **Step 1: Write the MSW server and Vitest setup**

`frontend/tests/msw/server.ts`:

```ts
import { setupServer } from 'msw/node'

export const server = setupServer()
```

`frontend/tests/setup.ts`:

```ts
import { afterAll, afterEach, beforeAll } from 'vitest'
import { server } from './msw/server'
import { resetCsrfToken } from '@/api/http'

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }))
afterEach(() => {
  server.resetHandlers()
  resetCsrfToken()
})
afterAll(() => server.close())
```

- [ ] **Step 2: Write the failing test `frontend/tests/api/http.test.ts`**

```ts
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import { server } from '../msw/server'
import { ApiError, apiFetch } from '@/api/http'

describe('apiFetch', () => {
  it('returns parsed JSON for GET', async () => {
    server.use(http.get('/api/auth/me', () => HttpResponse.json({ email: 'p@example.com', roles: ['PATIENT'] })))
    const me = await apiFetch<{ email: string; roles: string[] }>('/api/auth/me')
    expect(me.email).toBe('p@example.com')
  })

  it('bootstraps CSRF token once and sends it on mutations', async () => {
    let csrfCalls = 0
    let seenToken: string | null = null
    server.use(
      http.get('/api/csrf', () => {
        csrfCalls++
        return HttpResponse.json({ token: 'tok-1', headerName: 'X-XSRF-TOKEN' })
      }),
      http.post('/api/example', ({ request }) => {
        seenToken = request.headers.get('X-XSRF-TOKEN')
        return HttpResponse.json({ status: 'ok' })
      }),
    )
    await apiFetch('/api/example', { method: 'POST', body: { a: 1 } })
    await apiFetch('/api/example', { method: 'POST', body: { a: 2 } })
    expect(seenToken).toBe('tok-1')
    expect(csrfCalls).toBe(1)
  })

  it('skips CSRF when csrf:false', async () => {
    server.use(
      http.post('/api/auth/login', ({ request }) => {
        expect(request.headers.get('X-XSRF-TOKEN')).toBeNull()
        return HttpResponse.json({ status: 'AUTHENTICATED', email: 'p@example.com', roles: ['PATIENT'], challengeId: null, methods: null })
      }),
    )
    const res = await apiFetch<{ status: string }>('/api/auth/login', { method: 'POST', body: { email: 'p@example.com', password: 'secret' }, csrf: false })
    expect(res.status).toBe('AUTHENTICATED')
  })

  it('maps 400 validation body to ApiError with fields', async () => {
    server.use(
      http.get('/api/csrf', () => HttpResponse.json({ token: 't', headerName: 'X-XSRF-TOKEN' })),
      http.post('/api/x', () => HttpResponse.json({ error: 'validation_failed', fields: { email: 'must be valid' } }, { status: 400 })),
    )
    const err = await apiFetch('/api/x', { method: 'POST', body: {} }).catch((e) => e)
    expect(err).toBeInstanceOf(ApiError)
    expect(err.status).toBe(400)
    expect(err.code).toBe('validation_failed')
    expect(err.fields).toEqual({ email: 'must be valid' })
  })

  it('maps 401 with empty body to ApiError("unauthorized")', async () => {
    server.use(http.get('/api/auth/me', () => new HttpResponse(null, { status: 401 })))
    const err = await apiFetch('/api/auth/me').catch((e) => e)
    expect(err).toBeInstanceOf(ApiError)
    expect(err.status).toBe(401)
    expect(err.code).toBe('unauthorized')
  })

  it('invokes the registered unauthorized handler on 401', async () => {
    const { setUnauthorizedHandler } = await import('@/api/http')
    let called = 0
    setUnauthorizedHandler(() => { called++ })
    server.use(http.get('/api/auth/me', () => new HttpResponse(null, { status: 401 })))
    await apiFetch('/api/auth/me').catch(() => undefined)
    expect(called).toBe(1)
    setUnauthorizedHandler(null)
  })

  it('sends FormData without Content-Type override', async () => {
    let contentType: string | null = null
    server.use(
      http.get('/api/csrf', () => HttpResponse.json({ token: 't', headerName: 'X-XSRF-TOKEN' })),
      http.post('/api/diet-log-photos/uploads', ({ request }) => {
        contentType = request.headers.get('Content-Type')
        return HttpResponse.json({ uploadId: 7, originalFilename: 'a.jpg', contentType: 'image/jpeg', sizeBytes: 10, caption: null, contentUrl: '/api/diet-log-photos/7/content' })
      }),
    )
    await apiFetch('/api/diet-log-photos/uploads', { method: 'POST', formData: new FormData() })
    expect(contentType).toMatch(/^multipart\/form-data/)
  })
})
```

- [ ] **Step 3: Run test to verify it fails**

Run: `cd frontend && npm run test`
Expected: FAIL — `Cannot find module '@/api/http'`.

- [ ] **Step 4: Implement `frontend/src/api/http.ts`**

```ts
export class ApiError extends Error {
  constructor(
    public readonly status: number,
    public readonly code: string,
    public readonly fields?: Record<string, string>,
  ) {
    super(`${status} ${code}`)
    this.name = 'ApiError'
  }
}

export interface ApiFetchOptions {
  method?: 'GET' | 'POST' | 'PUT' | 'DELETE'
  body?: unknown
  formData?: FormData
  csrf?: boolean
}

interface CsrfState {
  token: string
  headerName: string
}

let csrf: CsrfState | null = null

export function resetCsrfToken(): void {
  csrf = null
}

type UnauthorizedHandler = () => void
let unauthorizedHandler: UnauthorizedHandler | null = null

/** Called whenever any request returns 401 (e.g. expired session mid-use). Wired in main.ts (Task 3). */
export function setUnauthorizedHandler(handler: UnauthorizedHandler | null): void {
  unauthorizedHandler = handler
}

async function ensureCsrf(): Promise<CsrfState> {
  if (!csrf) {
    const res = await fetch('/api/csrf', { credentials: 'same-origin' })
    if (!res.ok) throw new ApiError(res.status, 'unauthorized')
    csrf = (await res.json()) as CsrfState
  }
  return csrf
}

async function parseBody(res: Response): Promise<unknown> {
  const text = await res.text()
  if (!text) return undefined
  try {
    return JSON.parse(text)
  } catch {
    return undefined
  }
}

export async function apiFetch<T>(path: string, options: ApiFetchOptions = {}): Promise<T> {
  const method = options.method ?? 'GET'
  const headers: Record<string, string> = {}

  if (method !== 'GET' && options.csrf !== false) {
    const { token, headerName } = await ensureCsrf()
    headers[headerName] = token
  }

  let body: BodyInit | undefined
  if (options.formData !== undefined) {
    body = options.formData
  } else if (options.body !== undefined) {
    headers['Content-Type'] = 'application/json'
    body = JSON.stringify(options.body)
  }

  const res = await fetch(path, { method, headers, body, credentials: 'same-origin' })
  const data = await parseBody(res)

  if (!res.ok) {
    if (res.status === 401) unauthorizedHandler?.()
    const errBody = data as { error?: string; fields?: Record<string, string> } | undefined
    throw new ApiError(res.status, errBody?.error ?? (res.status === 401 ? 'unauthorized' : 'request_failed'), errBody?.fields)
  }
  return data as T
}
```

- [ ] **Step 5: Implement `frontend/src/types/api.ts`**

Mirrors the backend DTO records in `src/main/java/com/metabion/dto/` exactly (JSON names are identical to record component names):

```ts
// Shared enums (mirror src/main/java/com/metabion/domain/)
export type Sex = 'FEMALE' | 'MALE' | 'INTERSEX' | 'PREFER_NOT_TO_SAY'
export type DietAdherenceLevel = 'FULL' | 'MOSTLY' | 'PARTIAL' | 'LOW' | 'NOT_FOLLOWED'
export type AppetiteLevel = 'LOW' | 'NORMAL' | 'HIGH' | 'VARIABLE'
export type MealType = 'BREAKFAST' | 'LUNCH' | 'DINNER' | 'SNACK' | 'DRINK' | 'OTHER'
export type DietDeviationCategory = 'EXCESS_CARBS' | 'NON_PROTOCOL_FOOD' | 'MISSED_MEAL' | 'DINING_OUT' | 'ALCOHOL' | 'GI_TOLERANCE' | 'OTHER'
export type DietDeviationSeverity = 'MINOR' | 'MODERATE' | 'MAJOR'
export type MeasurementType = 'KETONE' | 'GLUCOSE'
export type MeasurementUnit = 'MMOL_L' | 'MG_DL'
export type MeasurementContext = 'FASTING' | 'PRE_MEAL' | 'POST_MEAL' | 'BEDTIME' | 'SYMPTOMS' | 'OTHER'
export type FlareState = 'NO_FLARE' | 'SUSPECTED_FLARE' | 'ACTIVE_FLARE'
export type SymptomAnswerType = 'SINGLE_CHOICE' | 'NUMERIC' | 'TEXT'
export type LabTestCategory = 'INFLAMMATION' | 'HEMATOLOGY' | 'NUTRITION' | 'ELECTROLYTE' | 'LIVER' | 'KIDNEY'
export type LabResultSource = 'MANUAL' | 'IMPORTED'
export type LabResultConfirmationStatus = 'CONFIRMED' | 'UNCONFIRMED'
export type IbdDiagnosisType = 'CROHNS_DISEASE' | 'ULCERATIVE_COLITIS' | 'IBD_UNCLASSIFIED'
export type DiseaseActivityEstimate = 'REMISSION' | 'MILD' | 'MODERATE' | 'SEVERE' | 'UNKNOWN'
export type SteroidUse = 'NONE' | 'CURRENT' | 'RECENT_LAST_3_MONTHS'
export type AdvancedTherapyExposure = 'NEVER_USED' | 'CURRENT' | 'PAST' | 'UNKNOWN'
export type OnboardingReviewStatus = 'PENDING_REVIEW' | 'REVIEWED' | 'NEEDS_FOLLOW_UP'
export type EducationLanguage = 'EN' | 'CS'
export type PatientAccessClientType = 'MCP_CLAUDE' | 'MCP_CODEX' | 'MCP_OTHER' | 'MOBILE_IOS' | 'MOBILE_ANDROID' | 'INTERNAL_TEST'

// Auth
export interface LoginResponse {
  status: 'AUTHENTICATED' | 'MFA_REQUIRED'
  email: string
  roles: string[]
  challengeId: string | null
  methods: string[] | null
}
export interface MeResponse { email: string; roles: string[] }
export interface CsrfTokenResponse { token: string; headerName: string }

// Account
export interface PatientProfile {
  dateOfBirth: string // yyyy-MM-dd
  sex: Sex
  countryRegion: string
  timezone: string
}

// Access tokens
export interface IssuePatientAccessTokenRequest {
  clientType: PatientAccessClientType
  displayLabel: string
  expiresInDays: number
  scopes: string[]
}
export interface IssuePatientAccessTokenResponse {
  tokenId: number
  plainToken: string
  clientType: PatientAccessClientType
  displayLabel: string
  expiresAt: string
  scopes: string[]
}
export interface PatientAccessTokenSummary {
  tokenId: number
  clientType: PatientAccessClientType
  displayLabel: string
  createdAt: string
  expiresAt: string
  lastUsedAt: string | null
  scopes: string[]
}

// Diet logs
export interface MealRequest { mealType: MealType; foodDescription?: string; notes?: string }
export interface DeviationRequest { mealIndex?: number | null; deviationCategory: DietDeviationCategory; severity: DietDeviationSeverity; notes?: string }
export interface PhotoUploadReferenceRequest { mealIndex?: number | null; uploadId: number; caption?: string }
export interface DailyMeasurementEntryRequest {
  measurementType: MeasurementType
  value: number
  unit: MeasurementUnit
  measuredAt: string
  context: MeasurementContext
  notes?: string
  metadata?: string
}
export interface DailyDietLogRequest {
  logDate: string
  adherenceLevel: DietAdherenceLevel
  appetiteLevel: AppetiteLevel
  notes?: string
  metadata?: string
  meals: MealRequest[]
  deviations: DeviationRequest[]
  photoReferences: PhotoUploadReferenceRequest[]
  measurements: DailyMeasurementEntryRequest[]
}
export interface MealResponse { id: number; mealType: MealType; foodDescription: string | null; notes: string | null; sortOrder: number }
export interface DeviationResponse { id: number; mealId: number | null; deviationCategory: DietDeviationCategory; severity: DietDeviationSeverity; notes: string | null; sortOrder: number }
export interface PhotoReferenceResponse { id: number; mealId: number | null; originalFilename: string; contentType: string; sizeBytes: number; caption: string | null; contentUrl: string; sortOrder: number }
export interface DailyMeasurementEntryResponse {
  id: number
  patientProfileId: number
  dailyDietLogId: number
  measurementType: MeasurementType
  value: number
  unit: MeasurementUnit
  measuredAt: string
  context: MeasurementContext
  notes: string | null
  metadata: string | null
  createdAt: string
}
export interface DailyDietLogResponse {
  id: number
  patientProfileId: number
  patientEmail: string
  logDate: string
  adherenceLevel: DietAdherenceLevel
  appetiteLevel: AppetiteLevel
  notes: string | null
  metadata: string | null
  createdAt: string
  updatedAt: string
  meals: MealResponse[]
  deviations: DeviationResponse[]
  photoReferences: PhotoReferenceResponse[]
  measurements: DailyMeasurementEntryResponse[]
}
export interface DailyDietLogSummary {
  id: number
  patientProfileId: number
  patientEmail: string
  logDate: string
  adherenceLevel: DietAdherenceLevel
  appetiteLevel: AppetiteLevel
  mealCount: number
  deviationCount: number
  measurementCount: number
  notesPreview: string | null
}
export interface DietLogPhotoUploadResponse {
  uploadId: number
  originalFilename: string
  contentType: string
  sizeBytes: number
  caption: string | null
  contentUrl: string
}

// Symptoms
export interface SymptomOption { id: number; stableKey: string; label: string; numericScore: number | null }
export interface SymptomQuestion {
  id: number
  stableKey: string
  label: string
  helpText: string | null
  answerType: SymptomAnswerType
  required: boolean
  minNumericValue: number | null
  maxNumericValue: number | null
  options: SymptomOption[]
}
export interface SymptomQuestionnaire {
  id: number
  stableKey: string
  displayName: string
  versionId: number
  versionNumber: number
  questions: SymptomQuestion[]
}
export interface AnswerRequest { questionId: number; optionId?: number | null; answerText?: string | null; answerNumeric?: number | null }
export interface SymptomCheckInRequest {
  checkInDate: string
  questionnaireVersionId: number
  flareState: FlareState
  answers: AnswerRequest[]
  notes?: string
}
export interface AnswerResponse {
  questionId: number
  questionStableKey: string
  label: string
  answerType: SymptomAnswerType
  optionId: number | null
  optionStableKey: string | null
  optionLabel: string | null
  answerText: string | null
  answerNumeric: number | null
  numericScore: number | null
}
export interface SymptomCheckInResponse {
  id: number
  patientProfileId: number
  questionnaireVersionId: number
  checkInDate: string
  flareState: FlareState
  totalSymptomScore: number | null
  notes: string | null
  answers: AnswerResponse[]
  createdAt: string
  updatedAt: string
}
export interface MeasurementPoint { id: number; measurementType: MeasurementType; value: number; unit: MeasurementUnit; measuredAt: string; context: MeasurementContext }
export interface DayTrend {
  date: string
  symptomCheckInId: number | null
  symptomScore: number | null
  flareState: FlareState | null
  dietLogId: number | null
  adherenceLevel: DietAdherenceLevel | null
  appetiteLevel: AppetiteLevel | null
  glucoseMeasurements: MeasurementPoint[]
  ketoneMeasurements: MeasurementPoint[]
}
export interface DailyTrendResponse {
  patientProfileId: number
  from: string
  to: string
  glucoseUnit: MeasurementUnit
  timezone: string
  days: DayTrend[]
}

// Labs
export interface LabTestDefinition { code: string; label: string; category: LabTestCategory; canonicalUnit: string; displayScale: number; allowedUnits: string[] }
export interface LabResultRequest { testCode: string; value: number; unit: string; referenceLower?: number | null; referenceUpper?: number | null }
export interface LabResultSetRequest {
  resultSetId?: number | null
  version?: number | null
  collectionDate: string
  notes?: string
  results: LabResultRequest[]
}
export interface LabResultResponse {
  id: number
  testCode: string
  label: string
  reportedValue: number
  reportedUnit: string
  canonicalValue: number
  canonicalUnit: string
  referenceLower: number | null
  referenceUpper: number | null
}
export interface LabResultSetResponse {
  id: number
  version: number
  patientProfileId: number
  collectionDate: string
  notes: string | null
  source: LabResultSource
  confirmationStatus: LabResultConfirmationStatus
  createdByCurrentPatient: boolean
  createdAt: string
  updatedAt: string
  results: LabResultResponse[]
}
export interface LabTrendPoint {
  resultSetId: number
  resultSetVersion: number
  collectionDate: string
  canonicalValue: number
  reportedValue: number
  reportedUnit: string
  referenceLower: number | null
  referenceUpper: number | null
  editable: boolean
}
export interface LabTrendResponse {
  patientProfileId: number
  testCode: string
  label: string
  canonicalUnit: string
  displayScale: number
  from: string
  to: string
  points: LabTrendPoint[]
}

// Onboarding
export interface OnboardingSubmissionRequest {
  onboardingContext?: string
  diagnosisType: IbdDiagnosisType
  diagnosisYear?: number | null
  diseaseLocation?: string
  diseaseBehavior?: string
  activityEstimate: DiseaseActivityEstimate
  currentMedications?: string
  steroidUse: SteroidUse
  advancedTherapyExposure: AdvancedTherapyExposure
  medicationNotes?: string
  labsCollectedAt?: string | null
  crpMgL?: number | null
  fecalCalprotectinUgG?: number | null
  hemoglobinGDl?: number | null
  albuminGDl?: number | null
  labNotes?: string
}
export interface OnboardingSubmissionResponse extends OnboardingSubmissionRequest {
  id: number
  patientProfileId: number
  patientEmail: string
  version: number
  createdAt: string
  submittedAt: string
  dateOfBirth: string | null
  sex: Sex | null
  countryRegion: string | null
  timezone: string | null
  reviewStatus: OnboardingReviewStatus
}
export interface OnboardingSubmissionSummary {
  id: number
  patientProfileId: number
  patientEmail: string
  onboardingContext: string | null
  version: number
  submittedAt: string
  diagnosisType: IbdDiagnosisType
  reviewStatus: OnboardingReviewStatus
}

// Education
export interface EducationModuleSummary {
  moduleSlug: string
  topic: string
  sortOrder: number
  version: number
  requestedLanguage: EducationLanguage
  contentLanguage: EducationLanguage
  title: string
  summary: string | null
  lessonCount: number
  completedLessonCount: number | null
  completed: boolean | null
  publishedAt: string | null
}
export interface EducationLesson {
  lessonSlug: string
  sortOrder: number
  requestedLanguage: EducationLanguage
  contentLanguage: EducationLanguage
  title: string
  summary: string | null
  bodyMarkdown: string | null
  bodyHtml: string | null
  completed: boolean | null
}
export interface EducationModuleDetail extends Omit<EducationModuleSummary, never> {
  lessons: EducationLesson[]
}
```

- [ ] **Step 6: Run tests to verify they pass**

Run: `cd frontend && npm run test`
Expected: 7 tests PASS.

- [ ] **Step 7: Commit**

```bash
git add frontend
git commit -m "Add SPA fetch wrapper with CSRF bootstrap and API types"
```

---

### Task 3: i18n, auth store, router with guards

**Files:**
- Create: `frontend/src/i18n/index.ts`, `frontend/src/i18n/en.json`, `frontend/src/i18n/cs.json`
- Create: `frontend/src/api/auth.ts`
- Create: `frontend/src/stores/auth.ts`
- Create: `frontend/src/router/index.ts`
- Modify: `frontend/src/main.ts` (full bootstrap)
- Test: `frontend/tests/stores/auth.test.ts`, `frontend/tests/router/guards.test.ts`

**Interfaces:**
- Consumes: `apiFetch`, `ApiError`, `resetCsrfToken` from `@/api/http`; `LoginResponse`, `MeResponse` from `@/types/api`.
- Produces: `authApi` (register/verify/forgotPassword/resetPassword/login/logout/me); `useAuthStore()` with state `{ email, roles, status: 'unknown'|'authenticated'|'anonymous', mfaRequired }`, getters `isAuthenticated`, `isPatient`, actions `fetchMe()`, `login(email, password): Promise<LoginResponse>`, `logout()`, `expire()`; `router`; `i18n`, `setLocale('en'|'cs')`. All views and the AppShell consume these.

- [ ] **Step 1: Write the failing auth store test `frontend/tests/stores/auth.test.ts`**

```ts
import { http, HttpResponse } from 'msw'
import { beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { server } from '../msw/server'
import { useAuthStore } from '@/stores/auth'

describe('auth store', () => {
  beforeEach(() => setActivePinia(createPinia()))

  it('fetchMe sets authenticated identity', async () => {
    server.use(http.get('/api/auth/me', () => HttpResponse.json({ email: 'p@example.com', roles: ['PATIENT'] })))
    const auth = useAuthStore()
    await auth.fetchMe()
    expect(auth.status).toBe('authenticated')
    expect(auth.email).toBe('p@example.com')
    expect(auth.isPatient).toBe(true)
  })

  it('fetchMe maps 401 to anonymous', async () => {
    server.use(http.get('/api/auth/me', () => new HttpResponse(null, { status: 401 })))
    const auth = useAuthStore()
    await auth.fetchMe()
    expect(auth.status).toBe('anonymous')
    expect(auth.isAuthenticated).toBe(false)
  })

  it('login sets identity on AUTHENTICATED', async () => {
    server.use(http.post('/api/auth/login', () => HttpResponse.json({ status: 'AUTHENTICATED', email: 'p@example.com', roles: ['PATIENT'], challengeId: null, methods: null })))
    const auth = useAuthStore()
    const res = await auth.login('p@example.com', 'password-123')
    expect(res.status).toBe('AUTHENTICATED')
    expect(auth.status).toBe('authenticated')
    expect(auth.mfaRequired).toBe(false)
  })

  it('login flags mfaRequired without authenticating', async () => {
    server.use(http.post('/api/auth/login', () => HttpResponse.json({ status: 'MFA_REQUIRED', email: 'p@example.com', roles: ['PATIENT'], challengeId: 'ch-1', methods: ['TOTP'] })))
    const auth = useAuthStore()
    await auth.login('p@example.com', 'password-123')
    expect(auth.mfaRequired).toBe(true)
    expect(auth.status).not.toBe('authenticated')
  })

  it('logout resets state', async () => {
    server.use(
      http.get('/api/auth/me', () => HttpResponse.json({ email: 'p@example.com', roles: ['PATIENT'] })),
      http.get('/api/csrf', () => HttpResponse.json({ token: 't', headerName: 'X-XSRF-TOKEN' })),
      http.post('/api/auth/logout', () => new HttpResponse(null, { status: 200 })),
    )
    const auth = useAuthStore()
    await auth.fetchMe()
    await auth.logout()
    expect(auth.status).toBe('anonymous')
    expect(auth.email).toBeNull()
    expect(auth.roles).toEqual([])
  })
})
```

- [ ] **Step 2: Write the failing router guard test `frontend/tests/router/guards.test.ts`**

```ts
import { http, HttpResponse } from 'msw'
import { beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createMemoryHistory } from 'vue-router'
import { server } from '../msw/server'
import { routes, installAuthGuard } from '@/router/index'

function makeRouter() {
  const router = createRouter({ history: createMemoryHistory(), routes })
  installAuthGuard(router)
  return router
}

describe('router auth guard', () => {
  beforeEach(() => setActivePinia(createPinia()))

  it('redirects anonymous users from protected routes to /login', async () => {
    server.use(http.get('/api/auth/me', () => new HttpResponse(null, { status: 401 })))
    const router = makeRouter()
    await router.push('/diet-logs')
    expect(router.currentRoute.value.path).toBe('/login')
    expect(router.currentRoute.value.query.redirect).toBe('/diet-logs')
  })

  it('lets patients through to protected routes', async () => {
    server.use(http.get('/api/auth/me', () => HttpResponse.json({ email: 'p@example.com', roles: ['PATIENT'] })))
    const router = makeRouter()
    await router.push('/diet-logs')
    expect(router.currentRoute.value.path).toBe('/diet-logs')
  })

  it('redirects non-patient staff to /staff-notice', async () => {
    server.use(http.get('/api/auth/me', () => HttpResponse.json({ email: 's@example.com', roles: ['PHYSICIAN'] })))
    const router = makeRouter()
    await router.push('/diet-logs')
    expect(router.currentRoute.value.path).toBe('/staff-notice')
  })

  it('redirects authenticated users away from /login', async () => {
    server.use(http.get('/api/auth/me', () => HttpResponse.json({ email: 'p@example.com', roles: ['PATIENT'] })))
    const router = makeRouter()
    await router.push('/login')
    expect(router.currentRoute.value.path).toBe('/')
  })
})
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `cd frontend && npm run test`
Expected: FAIL — `Cannot find module '@/stores/auth'` / `@/router/index`.

- [ ] **Step 4: Implement `frontend/src/api/auth.ts`**

```ts
import { apiFetch } from './http'
import type { LoginResponse, MeResponse } from '@/types/api'

export const authApi = {
  register: (email: string, password: string) =>
    apiFetch<{ status: string }>('/api/auth/register', { method: 'POST', body: { email, password }, csrf: false }),

  verify: (token: string) =>
    apiFetch<void>(`/api/auth/verify?token=${encodeURIComponent(token)}`),

  forgotPassword: (email: string) =>
    apiFetch<{ status: string }>('/api/auth/forgot-password', { method: 'POST', body: { email }, csrf: false }),

  resetPassword: (token: string, newPassword: string) =>
    apiFetch<{ status: string }>('/api/auth/reset-password', { method: 'POST', body: { token, newPassword }, csrf: false }),

  login: (email: string, password: string) =>
    apiFetch<LoginResponse>('/api/auth/login', { method: 'POST', body: { email, password }, csrf: false }),

  logout: () => apiFetch<void>('/api/auth/logout', { method: 'POST' }),

  me: () => apiFetch<MeResponse>('/api/auth/me'),
}
```

- [ ] **Step 5: Implement `frontend/src/stores/auth.ts`**

```ts
import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { authApi } from '@/api/auth'
import { ApiError, resetCsrfToken } from '@/api/http'
import type { LoginResponse } from '@/types/api'

export type AuthStatus = 'unknown' | 'authenticated' | 'anonymous'

export const useAuthStore = defineStore('auth', () => {
  const email = ref<string | null>(null)
  const roles = ref<string[]>([])
  const status = ref<AuthStatus>('unknown')
  const mfaRequired = ref(false)

  const isAuthenticated = computed(() => status.value === 'authenticated')
  const isPatient = computed(() => roles.value.includes('PATIENT'))

  async function fetchMe(): Promise<void> {
    try {
      const me = await authApi.me()
      email.value = me.email
      roles.value = me.roles
      status.value = 'authenticated'
    } catch (e) {
      if (e instanceof ApiError && e.status === 401) {
        email.value = null
        roles.value = []
        status.value = 'anonymous'
        return
      }
      throw e
    }
  }

  async function login(emailInput: string, password: string): Promise<LoginResponse> {
    const res = await authApi.login(emailInput, password)
    if (res.status === 'MFA_REQUIRED') {
      mfaRequired.value = true
      return res
    }
    resetCsrfToken()
    mfaRequired.value = false
    email.value = res.email
    roles.value = res.roles
    status.value = 'authenticated'
    return res
  }

  async function logout(): Promise<void> {
    try {
      await authApi.logout()
    } finally {
      expire()
    }
  }

  /** Local-only reset, e.g. when a request fails with 401 mid-session. */
  function expire(): void {
    resetCsrfToken()
    email.value = null
    roles.value = []
    status.value = 'anonymous'
    mfaRequired.value = false
  }

  return { email, roles, status, mfaRequired, isAuthenticated, isPatient, fetchMe, login, logout, expire }
})
```

- [ ] **Step 6: Implement `frontend/src/i18n/index.ts` and message bundles**

`frontend/src/i18n/index.ts`:

```ts
import { createI18n } from 'vue-i18n'
import en from './en.json'
import cs from './cs.json'

export type AppLocale = 'en' | 'cs'

export const i18n = createI18n({
  legacy: false,
  locale: 'en',
  fallbackLocale: 'en',
  messages: { en, cs },
})

export function setLocale(locale: AppLocale): void {
  i18n.global.locale.value = locale
}
```

`frontend/src/i18n/en.json` (Task 14 adds any missing keys; keep this exact set for now):

```json
{
  "app": { "title": "Metabion" },
  "nav": {
    "dashboard": "Dashboard", "dietLogs": "Diet logs", "checkIns": "Check-ins",
    "trends": "Trends", "labs": "Labs", "onboarding": "Onboarding",
    "education": "Education", "account": "Account", "logout": "Log out"
  },
  "auth": {
    "login": "Log in", "register": "Create account", "email": "Email", "password": "Password",
    "forgotPassword": "Forgot password?", "resetPassword": "Reset password",
    "newPassword": "New password", "sendResetLink": "Send reset link",
    "resetLinkSent": "If an account exists for this email, a reset link has been sent.",
    "registered": "Account created. Check your email for a verification link.",
    "verified": "Email verified. You can log in now.",
    "passwordReset": "Password changed. You can log in now.",
    "mfaRequired": "This account requires multi-factor authentication, which is not yet available in this app. Please use the main web application.",
    "noAccount": "No account yet?", "haveAccount": "Already registered?"
  },
  "staffNotice": {
    "title": "Staff area",
    "body": "This app is for patients. Please use the main staff application.",
    "openStaffApp": "Open staff application"
  },
  "errors": {
    "invalid_credentials": "Invalid email or password.",
    "invalid_token": "This link is invalid or has expired.",
    "validation_failed": "Please check the highlighted fields.",
    "unauthorized": "Please log in again.",
    "forbidden": "You do not have access to this.",
    "not_found": "Not found.",
    "conflict": "This record was changed elsewhere. Reload and try again.",
    "mail_unavailable": "Email service is temporarily unavailable. Try again later.",
    "request_failed": "Something went wrong. Please try again.",
    "network": "Cannot reach the server. Check your connection."
  },
  "common": {
    "save": "Save", "saved": "Saved.", "cancel": "Cancel", "delete": "Delete",
    "loading": "Loading…", "today": "Today", "from": "From", "to": "To",
    "apply": "Apply", "yes": "Yes", "no": "No", "back": "Back", "add": "Add",
    "remove": "Remove", "close": "Close", "copy": "Copy", "copied": "Copied"
  }
}
```

`frontend/src/i18n/cs.json` — same key structure, Czech values:

```json
{
  "app": { "title": "Metabion" },
  "nav": {
    "dashboard": "Přehled", "dietLogs": "Denníky stravy", "checkIns": "Kontroly",
    "trends": "Trendy", "labs": "Laboratoř", "onboarding": "Onboarding",
    "education": "Vzdělávání", "account": "Účet", "logout": "Odhlásit se"
  },
  "auth": {
    "login": "Přihlásit se", "register": "Vytvořit účet", "email": "E-mail", "password": "Heslo",
    "forgotPassword": "Zapomenuté heslo?", "resetPassword": "Obnovit heslo",
    "newPassword": "Nové heslo", "sendResetLink": "Odeslat odkaz",
    "resetLinkSent": "Pokud účet s tímto e-mailem existuje, byl odeslán odkaz pro obnovu.",
    "registered": "Účet vytvořen. Zkontrolujte e-mail s ověřovacím odkazem.",
    "verified": "E-mail ověřen. Nyní se můžete přihlásit.",
    "passwordReset": "Heslo změněno. Nyní se můžete přihlásit.",
    "mfaRequired": "Tento účet vyžaduje vícefaktorové ověření, které zatím není v této aplikaci dostupné. Použijte hlavní webovou aplikaci.",
    "noAccount": "Nemáte účet?", "haveAccount": "Již máte účet?"
  },
  "staffNotice": {
    "title": "Oblast pro personál",
    "body": "Tato aplikace je určena pacientům. Použijte hlavní aplikaci pro personál.",
    "openStaffApp": "Otevřít aplikaci pro personál"
  },
  "errors": {
    "invalid_credentials": "Neplatný e-mail nebo heslo.",
    "invalid_token": "Tento odkaz je neplatný nebo vypršel.",
    "validation_failed": "Zkontrolujte zvýrazněná pole.",
    "unauthorized": "Přihlaste se znovu.",
    "forbidden": "K tomuto nemáte přístup.",
    "not_found": "Nenalezeno.",
    "conflict": "Záznam byl mezitím změněn. Načtěte znovu a zkuste to znovu.",
    "mail_unavailable": "E-mailová služba je dočasně nedostupná. Zkuste to později.",
    "request_failed": "Něco se nepovedlo. Zkuste to znovu.",
    "network": "Server je nedostupný. Zkontrolujte připojení."
  },
  "common": {
    "save": "Uložit", "saved": "Uloženo.", "cancel": "Zrušit", "delete": "Smazat",
    "loading": "Načítání…", "today": "Dnes", "from": "Od", "to": "Do",
    "apply": "Použít", "yes": "Ano", "no": "Ne", "back": "Zpět", "add": "Přidat",
    "remove": "Odebrat", "close": "Zavřít", "copy": "Kopírovat", "copied": "Zkopírováno"
  }
}
```

- [ ] **Step 7: Implement `frontend/src/router/index.ts`**

Routes reference views created in later tasks; to keep the build green after this task, create a minimal placeholder now and replace it per task. `frontend/src/router/index.ts`:

```ts
import { createRouter, createWebHistory, type Router, type RouteRecordRaw } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import LoginView from '@/views/LoginView.vue'
import StaffNoticeView from '@/views/StaffNoticeView.vue'
import DashboardView from '@/views/DashboardView.vue'

export const routes: RouteRecordRaw[] = [
  { path: '/', component: DashboardView, meta: { requiresAuth: true } },
  { path: '/login', component: LoginView },
  { path: '/staff-notice', component: StaffNoticeView, meta: { requiresAuth: true, allowStaff: true } },
  // Later tasks insert their feature routes here.
]

export function installAuthGuard(router: Router): void {
  router.beforeEach(async (to) => {
    const auth = useAuthStore()
    if (auth.status === 'unknown') {
      await auth.fetchMe()
    }
    if (to.meta.requiresAuth && !auth.isAuthenticated) {
      return { path: '/login', query: { redirect: to.fullPath } }
    }
    if (to.meta.requiresAuth && !to.meta.allowStaff && auth.isAuthenticated && !auth.isPatient) {
      return { path: '/staff-notice' }
    }
    if (to.path === '/login' && auth.isAuthenticated) {
      return { path: '/' }
    }
    return true
  })
}

export const router = createRouter({
  history: createWebHistory(),
  routes,
})

installAuthGuard(router)
```

The guard test pushes `/diet-logs`, which does not exist as a route until Task 8 — add a catch-all placeholder route now so those pushes resolve (feature tasks add the real routes above it):

```ts
  { path: '/:pathMatch(.*)*', component: () => import('@/views/NotFoundView.vue'), meta: { requiresAuth: true } },
```

With the catch-all carrying `requiresAuth`, an anonymous visit to `/diet-logs` still redirects to `/login` (the guard checks merged meta), and a patient visit keeps the path `/diet-logs` — which is what the guard tests assert. `frontend/src/views/NotFoundView.vue`:

```vue
<script setup lang="ts">
import { useI18n } from 'vue-i18n'
const { t } = useI18n()
</script>

<template>
  <main class="mx-auto max-w-lg p-8 text-center">
    <h1 class="text-2xl font-semibold">404</h1>
    <p class="mt-2 text-gray-600">{{ t('errors.not_found') }}</p>
  </main>
</template>
```

- [ ] **Step 8: Replace `frontend/src/main.ts` with the full bootstrap**

```ts
import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import { i18n } from './i18n'
import { router } from './router'
import { setUnauthorizedHandler } from '@/api/http'
import { useAuthStore } from '@/stores/auth'
import './style.css'

const app = createApp(App).use(createPinia()).use(i18n).use(router)

// Expired session mid-use → reset local auth state and go to /login.
setUnauthorizedHandler(() => {
  useAuthStore().expire()
  if (router.currentRoute.value.path !== '/login') {
    void router.push('/login').catch(() => undefined)
  }
})

app.mount('#app')
```

- [ ] **Step 9: Create stub views so the build stays green**

Each is a placeholder replaced in its feature task. `frontend/src/views/LoginView.vue`:

```vue
<template>
  <main class="p-8">Login (implemented in Task 4)</main>
</template>
```

`frontend/src/views/DashboardView.vue`:

```vue
<template>
  <main class="p-8">Dashboard (implemented in Task 5)</main>
</template>
```

`frontend/src/views/StaffNoticeView.vue`:

```vue
<script setup lang="ts">
import { useI18n } from 'vue-i18n'
const { t } = useI18n()
</script>

<template>
  <main class="mx-auto max-w-lg p-8 text-center">
    <h1 class="text-2xl font-semibold">{{ t('staffNotice.title') }}</h1>
    <p class="mt-2 text-gray-600">{{ t('staffNotice.body') }}</p>
    <a href="/app" class="mt-4 inline-block rounded bg-blue-600 px-4 py-2 text-white">{{ t('staffNotice.openStaffApp') }}</a>
  </main>
</template>
```

- [ ] **Step 10: Run tests, typecheck, build**

Run: `cd frontend && npm run test && npm run typecheck && npm run build`
Expected: all new tests PASS (plus Task 2's), typecheck clean, build succeeds.

- [ ] **Step 11: Commit**

```bash
git add frontend
git commit -m "Add SPA auth store, router guards, and i18n scaffolding"
```

---

### Task 4: Public auth screens (login, register, forgot/reset, verify)

**Files:**
- Create: `frontend/src/composables/useApiError.ts`, `frontend/src/components/FieldError.vue`
- Modify: `frontend/src/views/LoginView.vue` (replace stub)
- Create: `frontend/src/views/RegisterView.vue`, `frontend/src/views/ForgotPasswordView.vue`, `frontend/src/views/ResetPasswordView.vue`, `frontend/src/views/VerifyEmailView.vue`
- Modify: `frontend/src/router/index.ts` (add public routes)
- Test: `frontend/tests/views/LoginView.test.ts`

**Interfaces:**
- Consumes: `useAuthStore`, `authApi`, `router`, `i18n`.
- Produces: `useApiError()` → `{ message: Ref<string>, fieldErrors: Ref<Record<string,string>>, capture(e: unknown): void, clear(): void }`; `FieldError` component with prop `field: string` reading injected errors — used by every later form view. Public routes `/register`, `/forgot-password`, `/reset-password`, `/verify`.

- [ ] **Step 1: Write the failing test `frontend/tests/views/LoginView.test.ts`**

```ts
import { flushPromises, mount } from '@vue/test-utils'
import { http, HttpResponse } from 'msw'
import { beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createMemoryHistory } from 'vue-router'
import { createI18n } from 'vue-i18n'
import { server } from '../msw/server'
import LoginView from '@/views/LoginView.vue'
import en from '@/i18n/en.json'

function makeI18n() {
  return createI18n({ legacy: false, locale: 'en', messages: { en } })
}

function makeRouter() {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/login', component: LoginView },
      { path: '/', component: { template: '<div />' }, meta: { requiresAuth: true } },
    ],
  })
}

describe('LoginView', () => {
  beforeEach(() => setActivePinia(createPinia()))

  it('logs in and navigates home', async () => {
    server.use(
      http.post('/api/auth/login', () => HttpResponse.json({ status: 'AUTHENTICATED', email: 'p@example.com', roles: ['PATIENT'], challengeId: null, methods: null })),
      http.get('/api/auth/me', () => HttpResponse.json({ email: 'p@example.com', roles: ['PATIENT'] })),
    )
    const router = makeRouter()
    await router.push('/login')
    const wrapper = mount(LoginView, { global: { plugins: [createPinia(), makeI18n(), router] } })
    await wrapper.find('input[type="email"]').setValue('p@example.com')
    await wrapper.find('input[type="password"]').setValue('password-123')
    await wrapper.find('form').trigger('submit.prevent')
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/')
  })

  it('shows invalid_credentials error on 401', async () => {
    server.use(http.post('/api/auth/login', () => HttpResponse.json({ error: 'invalid_credentials' }, { status: 401 })))
    const router = makeRouter()
    await router.push('/login')
    const wrapper = mount(LoginView, { global: { plugins: [createPinia(), makeI18n(), router] } })
    await wrapper.find('input[type="email"]').setValue('p@example.com')
    await wrapper.find('input[type="password"]').setValue('wrong-password')
    await wrapper.find('form').trigger('submit.prevent')
    await flushPromises()
    expect(wrapper.text()).toContain(en.errors.invalid_credentials)
  })

  it('shows MFA placeholder when MFA_REQUIRED', async () => {
    server.use(http.post('/api/auth/login', () => HttpResponse.json({ status: 'MFA_REQUIRED', email: 'p@example.com', roles: ['PATIENT'], challengeId: 'c1', methods: ['TOTP'] })))
    const router = makeRouter()
    await router.push('/login')
    const wrapper = mount(LoginView, { global: { plugins: [createPinia(), makeI18n(), router] } })
    await wrapper.find('input[type="email"]').setValue('p@example.com')
    await wrapper.find('input[type="password"]').setValue('password-123')
    await wrapper.find('form').trigger('submit.prevent')
    await flushPromises()
    expect(wrapper.text()).toContain(en.auth.mfaRequired)
    expect(router.currentRoute.value.path).toBe('/login')
  })
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && npm run test -- tests/views/LoginView.test.ts`
Expected: FAIL — the stub view has no form.

- [ ] **Step 3: Implement the shared error composable and FieldError component**

`frontend/src/composables/useApiError.ts`:

```ts
import { ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ApiError } from '@/api/http'

export function useApiError() {
  const { t, te } = useI18n()
  const message = ref('')
  const fieldErrors = ref<Record<string, string>>({})

  function clear(): void {
    message.value = ''
    fieldErrors.value = {}
  }

  function capture(e: unknown): void {
    clear()
    if (e instanceof ApiError) {
      fieldErrors.value = e.fields ?? {}
      if (!e.fields) {
        const key = `errors.${e.code}`
        message.value = te(key) ? t(key) : t('errors.request_failed')
      } else {
        message.value = t('errors.validation_failed')
      }
      return
    }
    message.value = t('errors.network')
  }

  return { message, fieldErrors, capture, clear }
}
```

`frontend/src/components/FieldError.vue`:

```vue
<script setup lang="ts">
defineProps<{ message?: string }>()
</script>

<template>
  <p v-if="message" class="mt-1 text-sm text-red-600">{{ message }}</p>
</template>
```

- [ ] **Step 4: Implement `frontend/src/views/LoginView.vue`**

```vue
<script setup lang="ts">
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { useAuthStore } from '@/stores/auth'
import { useApiError } from '@/composables/useApiError'

const { t } = useI18n()
const auth = useAuthStore()
const router = useRouter()
const route = useRoute()
const { message, capture } = useApiError()

const email = ref('')
const password = ref('')
const submitting = ref(false)

async function submit() {
  submitting.value = true
  try {
    const res = await auth.login(email.value, password.value)
    if (res.status === 'MFA_REQUIRED') {
      message.value = t('auth.mfaRequired')
      return
    }
    const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : '/'
    await router.push(redirect)
  } catch (e) {
    capture(e)
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <main class="mx-auto max-w-sm p-8">
    <h1 class="text-2xl font-semibold">{{ t('auth.login') }}</h1>
    <form class="mt-6 space-y-4" @submit.prevent="submit">
      <p v-if="message" class="rounded bg-red-50 p-3 text-sm text-red-700">{{ message }}</p>
      <div>
        <label class="block text-sm font-medium" for="email">{{ t('auth.email') }}</label>
        <input id="email" v-model="email" type="email" required autocomplete="email"
               class="mt-1 w-full rounded border border-gray-300 px-3 py-2" />
      </div>
      <div>
        <label class="block text-sm font-medium" for="password">{{ t('auth.password') }}</label>
        <input id="password" v-model="password" type="password" required autocomplete="current-password"
               class="mt-1 w-full rounded border border-gray-300 px-3 py-2" />
      </div>
      <button type="submit" :disabled="submitting"
              class="w-full rounded bg-blue-600 px-4 py-2 text-white disabled:opacity-50">
        {{ t('auth.login') }}
      </button>
    </form>
    <div class="mt-4 flex justify-between text-sm">
      <router-link to="/forgot-password" class="text-blue-600">{{ t('auth.forgotPassword') }}</router-link>
      <router-link to="/register" class="text-blue-600">{{ t('auth.register') }}</router-link>
    </div>
  </main>
</template>
```

- [ ] **Step 5: Run the LoginView test to verify it passes**

Run: `cd frontend && npm run test -- tests/views/LoginView.test.ts`
Expected: 3 tests PASS.

- [ ] **Step 6: Implement the remaining public views**

`frontend/src/views/RegisterView.vue`:

```vue
<script setup lang="ts">
import { ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { authApi } from '@/api/auth'
import { useApiError } from '@/composables/useApiError'
import FieldError from '@/components/FieldError.vue'

const { t } = useI18n()
const { message, fieldErrors, capture } = useApiError()
const email = ref('')
const password = ref('')
const done = ref(false)
const submitting = ref(false)

async function submit() {
  submitting.value = true
  try {
    await authApi.register(email.value, password.value)
    done.value = true
  } catch (e) {
    capture(e)
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <main class="mx-auto max-w-sm p-8">
    <h1 class="text-2xl font-semibold">{{ t('auth.register') }}</h1>
    <p v-if="done" class="mt-6 rounded bg-green-50 p-3 text-sm text-green-700">{{ t('auth.registered') }}</p>
    <form v-else class="mt-6 space-y-4" @submit.prevent="submit">
      <p v-if="message" class="rounded bg-red-50 p-3 text-sm text-red-700">{{ message }}</p>
      <div>
        <label class="block text-sm font-medium" for="email">{{ t('auth.email') }}</label>
        <input id="email" v-model="email" type="email" required autocomplete="email"
               class="mt-1 w-full rounded border border-gray-300 px-3 py-2" />
        <FieldError :message="fieldErrors.email" />
      </div>
      <div>
        <label class="block text-sm font-medium" for="password">{{ t('auth.password') }}</label>
        <input id="password" v-model="password" type="password" required minlength="12" maxlength="72"
               autocomplete="new-password" class="mt-1 w-full rounded border border-gray-300 px-3 py-2" />
        <FieldError :message="fieldErrors.password" />
      </div>
      <button type="submit" :disabled="submitting"
              class="w-full rounded bg-blue-600 px-4 py-2 text-white disabled:opacity-50">
        {{ t('auth.register') }}
      </button>
    </form>
    <p class="mt-4 text-sm">
      <router-link to="/login" class="text-blue-600">{{ t('auth.haveAccount') }}</router-link>
    </p>
  </main>
</template>
```

`frontend/src/views/ForgotPasswordView.vue`:

```vue
<script setup lang="ts">
import { ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { authApi } from '@/api/auth'
import { useApiError } from '@/composables/useApiError'

const { t } = useI18n()
const { message, capture } = useApiError()
const email = ref('')
const done = ref(false)
const submitting = ref(false)

async function submit() {
  submitting.value = true
  try {
    await authApi.forgotPassword(email.value)
    done.value = true
  } catch (e) {
    capture(e)
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <main class="mx-auto max-w-sm p-8">
    <h1 class="text-2xl font-semibold">{{ t('auth.forgotPassword') }}</h1>
    <p v-if="done" class="mt-6 rounded bg-green-50 p-3 text-sm text-green-700">{{ t('auth.resetLinkSent') }}</p>
    <form v-else class="mt-6 space-y-4" @submit.prevent="submit">
      <p v-if="message" class="rounded bg-red-50 p-3 text-sm text-red-700">{{ message }}</p>
      <div>
        <label class="block text-sm font-medium" for="email">{{ t('auth.email') }}</label>
        <input id="email" v-model="email" type="email" required autocomplete="email"
               class="mt-1 w-full rounded border border-gray-300 px-3 py-2" />
      </div>
      <button type="submit" :disabled="submitting"
              class="w-full rounded bg-blue-600 px-4 py-2 text-white disabled:opacity-50">
        {{ t('auth.sendResetLink') }}
      </button>
    </form>
  </main>
</template>
```

`frontend/src/views/ResetPasswordView.vue`:

```vue
<script setup lang="ts">
import { ref } from 'vue'
import { useRoute } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { authApi } from '@/api/auth'
import { useApiError } from '@/composables/useApiError'
import FieldError from '@/components/FieldError.vue'

const { t } = useI18n()
const route = useRoute()
const { message, fieldErrors, capture } = useApiError()
const newPassword = ref('')
const done = ref(false)
const submitting = ref(false)

async function submit() {
  submitting.value = true
  try {
    const token = typeof route.query.token === 'string' ? route.query.token : ''
    await authApi.resetPassword(token, newPassword.value)
    done.value = true
  } catch (e) {
    capture(e)
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <main class="mx-auto max-w-sm p-8">
    <h1 class="text-2xl font-semibold">{{ t('auth.resetPassword') }}</h1>
    <p v-if="done" class="mt-6 rounded bg-green-50 p-3 text-sm text-green-700">{{ t('auth.passwordReset') }}</p>
    <form v-else class="mt-6 space-y-4" @submit.prevent="submit">
      <p v-if="message" class="rounded bg-red-50 p-3 text-sm text-red-700">{{ message }}</p>
      <div>
        <label class="block text-sm font-medium" for="newPassword">{{ t('auth.newPassword') }}</label>
        <input id="newPassword" v-model="newPassword" type="password" required minlength="12" maxlength="72"
               autocomplete="new-password" class="mt-1 w-full rounded border border-gray-300 px-3 py-2" />
        <FieldError :message="fieldErrors.newPassword" />
      </div>
      <button type="submit" :disabled="submitting"
              class="w-full rounded bg-blue-600 px-4 py-2 text-white disabled:opacity-50">
        {{ t('auth.resetPassword') }}
      </button>
    </form>
  </main>
</template>
```

`frontend/src/views/VerifyEmailView.vue`:

```vue
<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { authApi } from '@/api/auth'
import { useApiError } from '@/composables/useApiError'

const { t } = useI18n()
const route = useRoute()
const { message, capture } = useApiError()
const verified = ref(false)
const loading = ref(true)

onMounted(async () => {
  try {
    const token = typeof route.query.token === 'string' ? route.query.token : ''
    await authApi.verify(token)
    verified.value = true
  } catch (e) {
    capture(e)
  } finally {
    loading.value = false
  }
})
</script>

<template>
  <main class="mx-auto max-w-sm p-8">
    <p v-if="loading">{{ t('common.loading') }}</p>
    <template v-else>
      <p v-if="verified" class="rounded bg-green-50 p-3 text-sm text-green-700">{{ t('auth.verified') }}</p>
      <p v-else class="rounded bg-red-50 p-3 text-sm text-red-700">{{ message }}</p>
      <router-link to="/login" class="mt-4 inline-block text-blue-600">{{ t('auth.login') }}</router-link>
    </template>
  </main>
</template>
```

- [ ] **Step 7: Register the public routes in `frontend/src/router/index.ts`**

Replace the imports and `routes` array (keep everything else unchanged):

```ts
import { createRouter, createWebHistory, type Router, type RouteRecordRaw } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import LoginView from '@/views/LoginView.vue'
import RegisterView from '@/views/RegisterView.vue'
import ForgotPasswordView from '@/views/ForgotPasswordView.vue'
import ResetPasswordView from '@/views/ResetPasswordView.vue'
import VerifyEmailView from '@/views/VerifyEmailView.vue'
import StaffNoticeView from '@/views/StaffNoticeView.vue'
import DashboardView from '@/views/DashboardView.vue'

export const routes: RouteRecordRaw[] = [
  { path: '/', component: DashboardView, meta: { requiresAuth: true } },
  { path: '/login', component: LoginView },
  { path: '/register', component: RegisterView },
  { path: '/forgot-password', component: ForgotPasswordView },
  { path: '/reset-password', component: ResetPasswordView },
  { path: '/verify', component: VerifyEmailView },
  { path: '/staff-notice', component: StaffNoticeView, meta: { requiresAuth: true, allowStaff: true } },
  { path: '/:pathMatch(.*)*', component: () => import('@/views/NotFoundView.vue'), meta: { requiresAuth: true } },
]
```

- [ ] **Step 8: Run tests, typecheck, build**

Run: `cd frontend && npm run test && npm run typecheck && npm run build`
Expected: all PASS.

- [ ] **Step 9: Commit**

```bash
git add frontend
git commit -m "Add SPA public auth screens"
```

---

### Task 5: App shell, dashboard, account + diet/symptom API modules

**Files:**
- Create: `frontend/src/components/AppShell.vue`
- Modify: `frontend/src/views/DashboardView.vue` (replace stub)
- Modify: `frontend/src/router/index.ts` (wrap authed routes in AppShell)
- Create: `frontend/src/api/account.ts`, `frontend/src/api/dietLogs.ts`, `frontend/src/api/symptoms.ts`, `frontend/src/api/labs.ts`, `frontend/src/api/onboarding.ts`, `frontend/src/api/education.ts`
- Test: `frontend/tests/views/DashboardView.test.ts`

**Interfaces:**
- Consumes: `useAuthStore`, `apiFetch`, all types from `@/types/api`.
- Produces: `AppShell` layout (all later authed views render inside it); domain API modules used by all later tasks:
  - `accountApi.getProfile(): Promise<PatientProfile>` / `accountApi.updateProfile(p: PatientProfile): Promise<{status:string}>`; `accessTokenApi.issue(req): Promise<IssuePatientAccessTokenResponse>` / `.list(): Promise<PatientAccessTokenSummary[]>` / `.revoke(id: number): Promise<void>`
  - `dietLogApi.save(req: DailyDietLogRequest): Promise<DailyDietLogResponse>` / `.get(date): Promise<DailyDietLogResponse>` / `.list(from,to): Promise<DailyDietLogSummary[]>` / `.addMeasurement(date, entry): Promise<DailyMeasurementEntryResponse>` / `.uploadPhoto(file: File): Promise<DietLogPhotoUploadResponse>` / `.photoContentUrl(id): string`
  - `symptomApi.activeQuestionnaire(): Promise<SymptomQuestionnaire>` / `.saveCheckIn(req): Promise<SymptomCheckInResponse>` / `.getCheckIn(date)` / `.listCheckIns(from,to): Promise<SymptomCheckInResponse[]>` / `.dailyTrend(from,to): Promise<DailyTrendResponse>`
  - `labApi.listTests(): Promise<LabTestDefinition[]>` / `.createResultSet(req)` / `.getResultSet(id)` / `.listResultSets(from,to)` / `.updateResultSet(id, req)` / `.requestRemoval(id, version, reason)` / `.trend(testCode, from, to)`
  - `onboardingApi.submit(req)` / `.latest()` / `.history()` / `.get(id)`
  - `educationApi.listModules()` / `.getModule(slug)` / `.completeLesson(moduleSlug, lessonSlug)` / `.uncompleteLesson(moduleSlug, lessonSlug)`

- [ ] **Step 1: Create all domain API modules (mechanical wrappers over `apiFetch`)**

`frontend/src/api/account.ts`:

```ts
import { apiFetch } from './http'
import type {
  IssuePatientAccessTokenRequest,
  IssuePatientAccessTokenResponse,
  PatientAccessTokenSummary,
  PatientProfile,
} from '@/types/api'

export const accountApi = {
  getProfile: () => apiFetch<PatientProfile>('/api/account/profile'),
  updateProfile: (profile: PatientProfile) =>
    apiFetch<{ status: string }>('/api/account/profile', { method: 'PUT', body: profile }),
}

export const accessTokenApi = {
  issue: (req: IssuePatientAccessTokenRequest) =>
    apiFetch<IssuePatientAccessTokenResponse>('/api/account/access-tokens', { method: 'POST', body: req }),
  list: () => apiFetch<PatientAccessTokenSummary[]>('/api/account/access-tokens'),
  revoke: (id: number) => apiFetch<void>(`/api/account/access-tokens/${id}`, { method: 'DELETE' }),
}
```

`frontend/src/api/dietLogs.ts`:

```ts
import { apiFetch } from './http'
import type {
  DailyDietLogRequest,
  DailyDietLogResponse,
  DailyDietLogSummary,
  DailyMeasurementEntryRequest,
  DailyMeasurementEntryResponse,
  DietLogPhotoUploadResponse,
} from '@/types/api'

export const dietLogApi = {
  save: (req: DailyDietLogRequest) =>
    apiFetch<DailyDietLogResponse>('/api/diet-logs', { method: 'POST', body: req }),
  get: (date: string) => apiFetch<DailyDietLogResponse>(`/api/diet-logs/${date}`),
  list: (from: string, to: string) =>
    apiFetch<DailyDietLogSummary[]>(`/api/diet-logs?from=${from}&to=${to}`),
  addMeasurement: (date: string, entry: DailyMeasurementEntryRequest) =>
    apiFetch<DailyMeasurementEntryResponse>(`/api/diet-logs/${date}/measurements`, { method: 'POST', body: entry }),
  uploadPhoto: (file: File) => {
    const formData = new FormData()
    formData.append('file', file)
    return apiFetch<DietLogPhotoUploadResponse>('/api/diet-log-photos/uploads', { method: 'POST', formData })
  },
  photoContentUrl: (id: number) => `/api/diet-log-photos/${id}/content`,
}
```

`frontend/src/api/symptoms.ts`:

```ts
import { apiFetch } from './http'
import type {
  DailyTrendResponse,
  SymptomCheckInRequest,
  SymptomCheckInResponse,
  SymptomQuestionnaire,
} from '@/types/api'

export const symptomApi = {
  activeQuestionnaire: () => apiFetch<SymptomQuestionnaire>('/api/symptom-questionnaires/active'),
  saveCheckIn: (req: SymptomCheckInRequest) =>
    apiFetch<SymptomCheckInResponse>('/api/symptom-check-ins', { method: 'POST', body: req }),
  getCheckIn: (date: string) => apiFetch<SymptomCheckInResponse>(`/api/symptom-check-ins/${date}`),
  listCheckIns: (from: string, to: string) =>
    apiFetch<SymptomCheckInResponse[]>(`/api/symptom-check-ins?from=${from}&to=${to}`),
  dailyTrend: (from: string, to: string) =>
    apiFetch<DailyTrendResponse>(`/api/trends/daily?from=${from}&to=${to}`),
}
```

`frontend/src/api/labs.ts`:

```ts
import { apiFetch } from './http'
import type {
  LabResultSetRequest,
  LabResultSetResponse,
  LabTestDefinition,
  LabTrendResponse,
} from '@/types/api'

export const labApi = {
  listTests: () => apiFetch<LabTestDefinition[]>('/api/lab-tests'),
  createResultSet: (req: LabResultSetRequest) =>
    apiFetch<LabResultSetResponse>('/api/lab-result-sets', { method: 'POST', body: req }),
  getResultSet: (id: number) => apiFetch<LabResultSetResponse>(`/api/lab-result-sets/${id}`),
  listResultSets: (from: string, to: string) =>
    apiFetch<LabResultSetResponse[]>(`/api/lab-result-sets?from=${from}&to=${to}`),
  updateResultSet: (id: number, req: LabResultSetRequest) =>
    apiFetch<LabResultSetResponse>(`/api/lab-result-sets/${id}`, { method: 'PUT', body: req }),
  requestRemoval: (id: number, version: number, reason: string) =>
    apiFetch<{ status: string }>(`/api/lab-result-sets/${id}/removal`, {
      method: 'POST',
      body: { resultSetId: id, version, reason },
    }),
  trend: (testCode: string, from: string, to: string) =>
    apiFetch<LabTrendResponse>(`/api/lab-trends/${encodeURIComponent(testCode)}?from=${from}&to=${to}`),
}
```

`frontend/src/api/onboarding.ts`:

```ts
import { apiFetch } from './http'
import type {
  OnboardingSubmissionRequest,
  OnboardingSubmissionResponse,
  OnboardingSubmissionSummary,
} from '@/types/api'

export const onboardingApi = {
  submit: (req: OnboardingSubmissionRequest) =>
    apiFetch<OnboardingSubmissionResponse>('/api/onboarding/submissions', { method: 'POST', body: req }),
  latest: () => apiFetch<OnboardingSubmissionResponse>('/api/onboarding/submissions/latest'),
  history: () => apiFetch<OnboardingSubmissionSummary[]>('/api/onboarding/submissions'),
  get: (id: number) => apiFetch<OnboardingSubmissionResponse>(`/api/onboarding/submissions/${id}`),
}
```

`frontend/src/api/education.ts`:

```ts
import { apiFetch } from './http'
import type { EducationModuleDetail, EducationModuleSummary } from '@/types/api'

export const educationApi = {
  listModules: () => apiFetch<EducationModuleSummary[]>('/api/education/modules'),
  getModule: (moduleSlug: string) =>
    apiFetch<EducationModuleDetail>(`/api/education/modules/${encodeURIComponent(moduleSlug)}`),
  completeLesson: (moduleSlug: string, lessonSlug: string) =>
    apiFetch<void>(`/api/education/modules/${encodeURIComponent(moduleSlug)}/lessons/${encodeURIComponent(lessonSlug)}/complete`, { method: 'POST' }),
  uncompleteLesson: (moduleSlug: string, lessonSlug: string) =>
    apiFetch<void>(`/api/education/modules/${encodeURIComponent(moduleSlug)}/lessons/${encodeURIComponent(lessonSlug)}/complete`, { method: 'DELETE' }),
}
```

- [ ] **Step 2: Write the failing dashboard test `frontend/tests/views/DashboardView.test.ts`**

The dashboard shows today's diet-log and check-in completion status. Both endpoints return 404 `{"error":"not_found"}` when nothing exists for the date.

```ts
import { flushPromises, mount } from '@vue/test-utils'
import { http, HttpResponse } from 'msw'
import { beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { createI18n } from 'vue-i18n'
import { server } from '../msw/server'
import DashboardView from '@/views/DashboardView.vue'
import en from '@/i18n/en.json'

const i18n = createI18n({ legacy: false, locale: 'en', messages: { en } })

function todayIso(): string {
  const d = new Date()
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

describe('DashboardView', () => {
  beforeEach(() => setActivePinia(createPinia()))

  it('shows both items as open when nothing exists today', async () => {
    server.use(
      http.get(`/api/diet-logs/${todayIso()}`, () => HttpResponse.json({ error: 'not_found' }, { status: 404 })),
      http.get(`/api/symptom-check-ins/${todayIso()}`, () => HttpResponse.json({ error: 'not_found' }, { status: 404 })),
    )
    const wrapper = mount(DashboardView, { global: { plugins: [createPinia(), i18n] } })
    await flushPromises()
    expect(wrapper.find('[data-testid="diet-log-status"]').text()).toContain(en.dashboard.dietLogOpen)
    expect(wrapper.find('[data-testid="check-in-status"]').text()).toContain(en.dashboard.checkInOpen)
  })

  it('shows completed states when today is filled', async () => {
    server.use(
      http.get(`/api/diet-logs/${todayIso()}`, () => HttpResponse.json({ id: 1, logDate: todayIso() })),
      http.get(`/api/symptom-check-ins/${todayIso()}`, () => HttpResponse.json({ id: 2, checkInDate: todayIso() })),
    )
    const wrapper = mount(DashboardView, { global: { plugins: [createPinia(), i18n] } })
    await flushPromises()
    expect(wrapper.find('[data-testid="diet-log-status"]').text()).toContain(en.dashboard.dietLogDone)
    expect(wrapper.find('[data-testid="check-in-status"]').text()).toContain(en.dashboard.checkInDone)
  })
})
```

Add the dashboard keys to `frontend/src/i18n/en.json` (insert after the `"common"` block; keep valid JSON):

```json
  "dashboard": {
    "title": "Today",
    "dietLog": "Diet log",
    "checkIn": "Symptom check-in",
    "dietLogDone": "Filled in",
    "dietLogOpen": "Not filled in yet",
    "checkInDone": "Filled in",
    "checkInOpen": "Not filled in yet"
  }
```

and to `frontend/src/i18n/cs.json`:

```json
  "dashboard": {
    "title": "Dnes",
    "dietLog": "Denník stravy",
    "checkIn": "Kontrola příznaků",
    "dietLogDone": "Vyplněno",
    "dietLogOpen": "Zatím nevyplněno",
    "checkInDone": "Vyplněno",
    "checkInOpen": "Zatím nevyplněno"
  }
```

- [ ] **Step 3: Run test to verify it fails**

Run: `cd frontend && npm run test -- tests/views/DashboardView.test.ts`
Expected: FAIL — stub dashboard has no `data-testid` elements.

- [ ] **Step 4: Implement `frontend/src/components/AppShell.vue`**

```vue
<script setup lang="ts">
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { useAuthStore } from '@/stores/auth'
import { setLocale, type AppLocale } from '@/i18n'

const { t, locale } = useI18n()
const auth = useAuthStore()
const router = useRouter()

const links = computed(() => [
  { to: '/', label: t('nav.dashboard') },
  { to: '/diet-logs', label: t('nav.dietLogs') },
  { to: '/check-ins', label: t('nav.checkIns') },
  { to: '/trends', label: t('nav.trends') },
  { to: '/labs', label: t('nav.labs') },
  { to: '/onboarding', label: t('nav.onboarding') },
  { to: '/education', label: t('nav.education') },
  { to: '/account', label: t('nav.account') },
])

function switchLocale(event: Event) {
  setLocale((event.target as HTMLSelectElement).value as AppLocale)
}

async function logout() {
  await auth.logout()
  await router.push('/login')
}
</script>

<template>
  <div class="min-h-screen bg-gray-50">
    <header class="border-b bg-white">
      <div class="mx-auto flex max-w-5xl items-center gap-4 px-4 py-3">
        <span class="text-lg font-semibold">{{ t('app.title') }}</span>
        <nav class="flex flex-1 flex-wrap gap-3 text-sm">
          <router-link v-for="link in links" :key="link.to" :to="link.to"
                       class="text-gray-700 hover:text-blue-700"
                       active-class="font-semibold text-blue-700">
            {{ link.label }}
          </router-link>
        </nav>
        <select :value="locale" class="rounded border border-gray-300 px-2 py-1 text-sm" @change="switchLocale">
          <option value="en">EN</option>
          <option value="cs">CS</option>
        </select>
        <button class="text-sm text-gray-700 hover:text-blue-700" @click="logout">{{ t('nav.logout') }}</button>
      </div>
    </header>
    <main class="mx-auto max-w-5xl px-4 py-6">
      <router-view />
    </main>
  </div>
</template>
```

- [ ] **Step 5: Restructure authed routes under AppShell in `frontend/src/router/index.ts`**

Replace the `routes` array (imports unchanged except adding AppShell):

```ts
import AppShell from '@/components/AppShell.vue'
// ...existing view imports stay

export const routes: RouteRecordRaw[] = [
  { path: '/login', component: LoginView },
  { path: '/register', component: RegisterView },
  { path: '/forgot-password', component: ForgotPasswordView },
  { path: '/reset-password', component: ResetPasswordView },
  { path: '/verify', component: VerifyEmailView },
  {
    path: '/',
    component: AppShell,
    meta: { requiresAuth: true },
    children: [
      { path: '', component: DashboardView },
      // Later tasks insert feature child routes here (paths without leading '/').
    ],
  },
  { path: '/staff-notice', component: StaffNoticeView, meta: { requiresAuth: true, allowStaff: true } },
  { path: '/:pathMatch(.*)*', component: () => import('@/views/NotFoundView.vue'), meta: { requiresAuth: true } },
]
```

Note: vue-router merges `meta` from all matched records into `to.meta`, so child routes automatically see the parent's `requiresAuth: true`. The `meta: { requiresAuth: true }` repeated on child routes in later tasks is belt-and-braces (harmless duplication, keeps each entry self-contained). Keep the catch-all as-is.

- [ ] **Step 6: Implement `frontend/src/views/DashboardView.vue`**

```vue
<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ApiError } from '@/api/http'
import { dietLogApi } from '@/api/dietLogs'
import { symptomApi } from '@/api/symptoms'

const { t } = useI18n()
const dietLogDone = ref(false)
const checkInDone = ref(false)
const loading = ref(true)

function todayIso(): string {
  const d = new Date()
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

async function exists(fetcher: () => Promise<unknown>): Promise<boolean> {
  try {
    await fetcher()
    return true
  } catch (e) {
    if (e instanceof ApiError && e.status === 404) return false
    throw e
  }
}

onMounted(async () => {
  const today = todayIso()
  ;[dietLogDone.value, checkInDone.value] = await Promise.all([
    exists(() => dietLogApi.get(today)),
    exists(() => symptomApi.getCheckIn(today)),
  ])
  loading.value = false
})
</script>

<template>
  <section>
    <h1 class="text-2xl font-semibold">{{ t('dashboard.title') }}</h1>
    <p v-if="loading" class="mt-4">{{ t('common.loading') }}</p>
    <div v-else class="mt-4 grid gap-4 sm:grid-cols-2">
      <router-link :to="`/diet-logs/${todayIso()}`" class="rounded border bg-white p-4 hover:border-blue-400">
        <h2 class="font-medium">{{ t('dashboard.dietLog') }}</h2>
        <p data-testid="diet-log-status" class="mt-1 text-sm" :class="dietLogDone ? 'text-green-700' : 'text-amber-700'">
          {{ dietLogDone ? t('dashboard.dietLogDone') : t('dashboard.dietLogOpen') }}
        </p>
      </router-link>
      <router-link :to="`/check-ins/${todayIso()}`" class="rounded border bg-white p-4 hover:border-blue-400">
        <h2 class="font-medium">{{ t('dashboard.checkIn') }}</h2>
        <p data-testid="check-in-status" class="mt-1 text-sm" :class="checkInDone ? 'text-green-700' : 'text-amber-700'">
          {{ checkInDone ? t('dashboard.checkInDone') : t('dashboard.checkInOpen') }}
        </p>
      </router-link>
    </div>
  </section>
</template>
```

- [ ] **Step 7: Run tests, typecheck, build**

Run: `cd frontend && npm run test && npm run typecheck && npm run build`
Expected: all PASS.

- [ ] **Step 8: Commit**

```bash
git add frontend
git commit -m "Add SPA app shell, dashboard, and domain API modules"
```

---

### Task 6: Account profile + access tokens

**Files:**
- Create: `frontend/src/views/AccountProfileView.vue`, `frontend/src/views/AccessTokensView.vue`
- Modify: `frontend/src/router/index.ts` (add child routes `account`, `account/access-tokens`)
- Test: `frontend/tests/views/AccessTokensView.test.ts`

**Interfaces:**
- Consumes: `accountApi`, `accessTokenApi` (Task 5), `useApiError`, `FieldError`.
- Produces: routes `/account` and `/account/access-tokens`.

- [ ] **Step 1: Write the failing test `frontend/tests/views/AccessTokensView.test.ts`**

```ts
import { flushPromises, mount } from '@vue/test-utils'
import { http, HttpResponse } from 'msw'
import { beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { createI18n } from 'vue-i18n'
import { server } from '../msw/server'
import AccessTokensView from '@/views/AccessTokensView.vue'
import en from '@/i18n/en.json'

const i18n = createI18n({ legacy: false, locale: 'en', messages: { en } })

const summary = {
  tokenId: 5,
  clientType: 'MCP_OTHER',
  displayLabel: 'My client',
  createdAt: '2026-07-01T10:00:00Z',
  expiresAt: '2026-08-01T10:00:00Z',
  lastUsedAt: null,
  scopes: ['patient:profile:read'],
}

describe('AccessTokensView', () => {
  beforeEach(() => setActivePinia(createPinia()))

  it('lists existing tokens', async () => {
    server.use(http.get('/api/account/access-tokens', () => HttpResponse.json([summary])))
    const wrapper = mount(AccessTokensView, { global: { plugins: [createPinia(), i18n] } })
    await flushPromises()
    expect(wrapper.text()).toContain('My client')
    expect(wrapper.text()).toContain('patient:profile:read')
  })

  it('shows the plaintext token once after issuing', async () => {
    server.use(
      http.get('/api/account/access-tokens', () => HttpResponse.json([])),
      http.get('/api/csrf', () => HttpResponse.json({ token: 't', headerName: 'X-XSRF-TOKEN' })),
      http.post('/api/account/access-tokens', () =>
        HttpResponse.json({ tokenId: 6, plainToken: 'plain-secret-token', clientType: 'MCP_OTHER', displayLabel: 'New', expiresAt: '2026-08-01T10:00:00Z', scopes: ['patient:profile:read'] }),
      ),
    )
    const wrapper = mount(AccessTokensView, { global: { plugins: [createPinia(), i18n] } })
    await flushPromises()
    await wrapper.find('input[data-testid="display-label"]').setValue('New')
    await wrapper.find('form').trigger('submit.prevent')
    await flushPromises()
    expect(wrapper.find('[data-testid="plain-token"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="plain-token"]').text()).toContain('plain-secret-token')
  })

  it('revokes a token after confirmation', async () => {
    let deleted = false
    server.use(
      http.get('/api/account/access-tokens', () => HttpResponse.json(deleted ? [] : [summary])),
      http.get('/api/csrf', () => HttpResponse.json({ token: 't', headerName: 'X-XSRF-TOKEN' })),
      http.delete('/api/account/access-tokens/5', () => {
        deleted = true
        return new HttpResponse(null, { status: 200 })
      }),
    )
    const wrapper = mount(AccessTokensView, { global: { plugins: [createPinia(), i18n] } })
    await flushPromises()
    await wrapper.find('[data-testid="revoke-5"]').trigger('click')
    await flushPromises()
    // confirmation dialog appears; confirm it
    await wrapper.find('[data-testid="confirm-revoke"]').trigger('click')
    await flushPromises()
    expect(wrapper.text()).not.toContain('My client')
  })
})
```

Add i18n keys to `en.json`:

```json
  "account": {
    "profileTitle": "Profile",
    "dateOfBirth": "Date of birth",
    "sex": "Sex",
    "countryRegion": "Country / region",
    "timezone": "Timezone",
    "tokensTitle": "Access tokens",
    "tokensIntro": "Tokens let external tools (for example AI assistants) access your data with the scopes you choose.",
    "displayLabel": "Label",
    "clientType": "Client type",
    "expiresInDays": "Expires in (days)",
    "scopes": "Scopes",
    "issue": "Issue token",
    "tokenCreatedTitle": "Token created — copy it now",
    "tokenCreatedWarning": "This is the only time the token is shown. Store it somewhere safe.",
    "lastUsed": "Last used",
    "never": "never",
    "expiresAt": "Expires",
    "createdAt": "Created",
    "revoke": "Revoke",
    "revokeConfirm": "Revoke this token? Clients using it will lose access immediately.",
    "confirm": "Confirm",
    "noTokens": "No tokens yet."
  }
```

and `cs.json`:

```json
  "account": {
    "profileTitle": "Profil",
    "dateOfBirth": "Datum narození",
    "sex": "Pohlaví",
    "countryRegion": "Země / region",
    "timezone": "Časové pásmo",
    "tokensTitle": "Přístupové tokeny",
    "tokensIntro": "Tokeny umožňují externím nástrojům (například AI asistentům) přístup k vašim datům s vámi zvolenými oprávněními.",
    "displayLabel": "Popisek",
    "clientType": "Typ klienta",
    "expiresInDays": "Platnost (dní)",
    "scopes": "Oprávnění",
    "issue": "Vystavit token",
    "tokenCreatedTitle": "Token vytvořen — zkopírujte si ho",
    "tokenCreatedWarning": "Token se zobrazí pouze jednou. Uložte si ho na bezpečné místo.",
    "lastUsed": "Naposledy použit",
    "never": "nikdy",
    "expiresAt": "Platí do",
    "createdAt": "Vytvořen",
    "revoke": "Zrušit",
    "revokeConfirm": "Opravdu zrušit tento token? Klienti, kteří ho používají, okamžitě ztratí přístup.",
    "confirm": "Potvrdit",
    "noTokens": "Zatím žádné tokeny."
  }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && npm run test -- tests/views/AccessTokensView.test.ts`
Expected: FAIL — view does not exist.

- [ ] **Step 3: Implement `frontend/src/views/AccountProfileView.vue`**

```vue
<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { accountApi } from '@/api/account'
import { useApiError } from '@/composables/useApiError'
import FieldError from '@/components/FieldError.vue'
import type { Sex } from '@/types/api'

const { t } = useI18n()
const { message, fieldErrors, capture } = useApiError()
const dateOfBirth = ref('')
const sex = ref<Sex>('PREFER_NOT_TO_SAY')
const countryRegion = ref('')
const timezone = ref('')
const saved = ref(false)
const loading = ref(true)

const sexOptions: Sex[] = ['FEMALE', 'MALE', 'INTERSEX', 'PREFER_NOT_TO_SAY']

onMounted(async () => {
  try {
    const p = await accountApi.getProfile()
    dateOfBirth.value = p.dateOfBirth
    sex.value = p.sex
    countryRegion.value = p.countryRegion
    timezone.value = p.timezone
  } finally {
    loading.value = false
  }
})

async function submit() {
  saved.value = false
  try {
    await accountApi.updateProfile({
      dateOfBirth: dateOfBirth.value,
      sex: sex.value,
      countryRegion: countryRegion.value,
      timezone: timezone.value,
    })
    saved.value = true
  } catch (e) {
    capture(e)
  }
}
</script>

<template>
  <section class="max-w-md">
    <h1 class="text-2xl font-semibold">{{ t('account.profileTitle') }}</h1>
    <p v-if="loading" class="mt-4">{{ t('common.loading') }}</p>
    <form v-else class="mt-4 space-y-4" @submit.prevent="submit">
      <p v-if="message" class="rounded bg-red-50 p-3 text-sm text-red-700">{{ message }}</p>
      <p v-if="saved" class="rounded bg-green-50 p-3 text-sm text-green-700">{{ t('common.saved') }}</p>
      <div>
        <label class="block text-sm font-medium" for="dob">{{ t('account.dateOfBirth') }}</label>
        <input id="dob" v-model="dateOfBirth" type="date" required
               class="mt-1 w-full rounded border border-gray-300 px-3 py-2" />
        <FieldError :message="fieldErrors.dateOfBirth" />
      </div>
      <div>
        <label class="block text-sm font-medium" for="sex">{{ t('account.sex') }}</label>
        <select id="sex" v-model="sex" class="mt-1 w-full rounded border border-gray-300 px-3 py-2">
          <option v-for="s in sexOptions" :key="s" :value="s">{{ t(`sex.${s}`) }}</option>
        </select>
        <FieldError :message="fieldErrors.sex" />
      </div>
      <div>
        <label class="block text-sm font-medium" for="country">{{ t('account.countryRegion') }}</label>
        <input id="country" v-model="countryRegion" type="text" required maxlength="100"
               class="mt-1 w-full rounded border border-gray-300 px-3 py-2" />
        <FieldError :message="fieldErrors.countryRegion" />
      </div>
      <div>
        <label class="block text-sm font-medium" for="tz">{{ t('account.timezone') }}</label>
        <input id="tz" v-model="timezone" type="text" required maxlength="100" placeholder="Europe/Prague"
               class="mt-1 w-full rounded border border-gray-300 px-3 py-2" />
        <FieldError :message="fieldErrors.timezone" />
      </div>
      <button type="submit" class="rounded bg-blue-600 px-4 py-2 text-white">{{ t('common.save') }}</button>
    </form>
    <p class="mt-6 text-sm">
      <router-link to="/account/access-tokens" class="text-blue-600">{{ t('account.tokensTitle') }}</router-link>
    </p>
  </section>
</template>
```

Add `sex` keys to both bundles (top-level key):

```json
  "sex": {
    "FEMALE": "Female",
    "MALE": "Male",
    "INTERSEX": "Intersex",
    "PREFER_NOT_TO_SAY": "Prefer not to say"
  }
```

```json
  "sex": {
    "FEMALE": "Žena",
    "MALE": "Muž",
    "INTERSEX": "Intersex",
    "PREFER_NOT_TO_SAY": "Nechci uvádět"
  }
```

- [ ] **Step 4: Implement `frontend/src/views/AccessTokensView.vue`**

```vue
<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { accessTokenApi } from '@/api/account'
import { useApiError } from '@/composables/useApiError'
import type { PatientAccessClientType, PatientAccessTokenSummary } from '@/types/api'

const { t } = useI18n()
const { message, capture } = useApiError()

const ALL_SCOPES = [
  'patient:profile:read', 'patient:profile:write',
  'patient:diet-log:read', 'patient:diet-log:write',
  'patient:diet-photo:read', 'patient:diet-photo:write',
  'patient:symptom:read', 'patient:symptom:write',
  'patient:onboarding:read', 'patient:onboarding:write',
  'patient:education:read', 'patient:education:write',
  'patient:lab:read', 'patient:lab:write',
  'patient:trend:read',
]
const CLIENT_TYPES: PatientAccessClientType[] = ['MCP_CLAUDE', 'MCP_CODEX', 'MCP_OTHER', 'MOBILE_IOS', 'MOBILE_ANDROID', 'INTERNAL_TEST']

const tokens = ref<PatientAccessTokenSummary[]>([])
const loading = ref(true)
const displayLabel = ref('')
const clientType = ref<PatientAccessClientType>('MCP_OTHER')
const expiresInDays = ref(30)
const selectedScopes = ref<string[]>(['patient:profile:read'])
const plainToken = ref<string | null>(null)
const copied = ref(false)
const pendingRevoke = ref<PatientAccessTokenSummary | null>(null)

async function load() {
  tokens.value = await accessTokenApi.list()
  loading.value = false
}

onMounted(load)

async function issue() {
  try {
    const res = await accessTokenApi.issue({
      clientType: clientType.value,
      displayLabel: displayLabel.value,
      expiresInDays: expiresInDays.value,
      scopes: selectedScopes.value,
    })
    plainToken.value = res.plainToken
    copied.value = false
    displayLabel.value = ''
    await load()
  } catch (e) {
    capture(e)
  }
}

async function copyToken() {
  if (plainToken.value) {
    await navigator.clipboard.writeText(plainToken.value)
    copied.value = true
  }
}

async function confirmRevoke() {
  if (!pendingRevoke.value) return
  try {
    await accessTokenApi.revoke(pendingRevoke.value.tokenId)
    pendingRevoke.value = null
    await load()
  } catch (e) {
    capture(e)
  }
}

function formatInstant(iso: string | null): string {
  return iso ? new Date(iso).toLocaleString() : t('account.never')
}
</script>

<template>
  <section class="max-w-2xl">
    <h1 class="text-2xl font-semibold">{{ t('account.tokensTitle') }}</h1>
    <p class="mt-1 text-sm text-gray-600">{{ t('account.tokensIntro') }}</p>

    <p v-if="message" class="mt-4 rounded bg-red-50 p-3 text-sm text-red-700">{{ message }}</p>

    <div v-if="plainToken" class="mt-4 rounded border border-amber-400 bg-amber-50 p-4">
      <h2 class="font-medium">{{ t('account.tokenCreatedTitle') }}</h2>
      <p class="mt-1 text-sm text-amber-800">{{ t('account.tokenCreatedWarning') }}</p>
      <code data-testid="plain-token" class="mt-2 block break-all rounded bg-white p-2 text-sm">{{ plainToken }}</code>
      <div class="mt-2 flex gap-2">
        <button class="rounded bg-blue-600 px-3 py-1 text-sm text-white" @click="copyToken">
          {{ copied ? t('common.copied') : t('common.copy') }}
        </button>
        <button class="rounded border px-3 py-1 text-sm" @click="plainToken = null">{{ t('common.close') }}</button>
      </div>
    </div>

    <form class="mt-6 space-y-3 rounded border bg-white p-4" @submit.prevent="issue">
      <div>
        <label class="block text-sm font-medium" for="label">{{ t('account.displayLabel') }}</label>
        <input id="label" v-model="displayLabel" data-testid="display-label" type="text" required maxlength="120"
               class="mt-1 w-full rounded border border-gray-300 px-3 py-2" />
      </div>
      <div class="grid grid-cols-2 gap-3">
        <div>
          <label class="block text-sm font-medium" for="ctype">{{ t('account.clientType') }}</label>
          <select id="ctype" v-model="clientType" class="mt-1 w-full rounded border border-gray-300 px-3 py-2">
            <option v-for="c in CLIENT_TYPES" :key="c" :value="c">{{ c }}</option>
          </select>
        </div>
        <div>
          <label class="block text-sm font-medium" for="days">{{ t('account.expiresInDays') }}</label>
          <input id="days" v-model.number="expiresInDays" type="number" min="1" max="90" required
                 class="mt-1 w-full rounded border border-gray-300 px-3 py-2" />
        </div>
      </div>
      <fieldset>
        <legend class="text-sm font-medium">{{ t('account.scopes') }}</legend>
        <div class="mt-1 grid grid-cols-2 gap-1 text-sm">
          <label v-for="s in ALL_SCOPES" :key="s" class="flex items-center gap-2">
            <input v-model="selectedScopes" type="checkbox" :value="s" /> {{ s }}
          </label>
        </div>
      </fieldset>
      <button type="submit" class="rounded bg-blue-600 px-4 py-2 text-white">{{ t('account.issue') }}</button>
    </form>

    <p v-if="loading" class="mt-6">{{ t('common.loading') }}</p>
    <p v-else-if="tokens.length === 0" class="mt-6 text-sm text-gray-600">{{ t('account.noTokens') }}</p>
    <ul v-else class="mt-6 space-y-3">
      <li v-for="token in tokens" :key="token.tokenId" class="rounded border bg-white p-4">
        <div class="flex items-start justify-between">
          <div>
            <p class="font-medium">{{ token.displayLabel }} <span class="text-sm text-gray-500">({{ token.clientType }})</span></p>
            <p class="mt-1 text-sm text-gray-600">
              {{ t('account.createdAt') }}: {{ formatInstant(token.createdAt) }} ·
              {{ t('account.expiresAt') }}: {{ formatInstant(token.expiresAt) }} ·
              {{ t('account.lastUsed') }}: {{ formatInstant(token.lastUsedAt) }}
            </p>
            <p class="mt-1 text-xs text-gray-500">{{ token.scopes.join(', ') }}</p>
          </div>
          <button :data-testid="`revoke-${token.tokenId}`" class="text-sm text-red-600"
                  @click="pendingRevoke = token">{{ t('account.revoke') }}</button>
        </div>
      </li>
    </ul>

    <div v-if="pendingRevoke" class="fixed inset-0 flex items-center justify-center bg-black/40">
      <div class="w-full max-w-sm rounded bg-white p-6">
        <p class="text-sm">{{ t('account.revokeConfirm') }}</p>
        <div class="mt-4 flex justify-end gap-2">
          <button class="rounded border px-3 py-1 text-sm" @click="pendingRevoke = null">{{ t('common.cancel') }}</button>
          <button data-testid="confirm-revoke" class="rounded bg-red-600 px-3 py-1 text-sm text-white"
                  @click="confirmRevoke">{{ t('account.confirm') }}</button>
        </div>
      </div>
    </div>
  </section>
</template>
```

- [ ] **Step 5: Add routes in `frontend/src/router/index.ts`**

Add imports:

```ts
import AccountProfileView from '@/views/AccountProfileView.vue'
import AccessTokensView from '@/views/AccessTokensView.vue'
```

Add inside the AppShell `children` array:

```ts
      { path: 'account', component: AccountProfileView, meta: { requiresAuth: true } },
      { path: 'account/access-tokens', component: AccessTokensView, meta: { requiresAuth: true } },
```

- [ ] **Step 6: Run tests, typecheck, build**

Run: `cd frontend && npm run test && npm run typecheck && npm run build`
Expected: all PASS (the AccessTokensView revoke test needs `window.confirm`-free flow — it uses the inline dialog implemented above).

- [ ] **Step 7: Commit**

```bash
git add frontend
git commit -m "Add SPA account profile and access token management"
```

---

### Task 7: Diet log editor with photos and measurements

**Files:**
- Create: `frontend/src/components/PhotoUpload.vue`
- Create: `frontend/src/views/DietLogEditView.vue`
- Modify: `frontend/src/router/index.ts` (add child route `diet-logs/:date`)
- Test: `frontend/tests/views/DietLogEditView.test.ts`

**Interfaces:**
- Consumes: `dietLogApi` (Task 5), types `DailyDietLogRequest`, `DailyDietLogResponse`, `MealRequest`, `DeviationRequest`, `PhotoUploadReferenceRequest`, `DailyMeasurementEntryRequest`; `useApiError`, `FieldError`.
- Produces: route `/diet-logs/:date`; `PhotoUpload` component emitting `uploaded` with `DietLogPhotoUploadResponse`.

Behavior notes (from backend): `POST /api/diet-logs` is an upsert keyed by `logDate` — saving replaces meals/deviations/photoReferences/measurements with the submitted lists. `GET /api/diet-logs/{date}` returns 404 `{"error":"not_found"}` when empty. Photos are uploaded first (`POST /api/diet-log-photos/uploads`, multipart part name `file`) and then referenced by `uploadId` in the log payload.

- [ ] **Step 1: Write the failing test `frontend/tests/views/DietLogEditView.test.ts`**

```ts
import { flushPromises, mount } from '@vue/test-utils'
import { http, HttpResponse } from 'msw'
import { beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { createI18n } from 'vue-i18n'
import { createRouter, createMemoryHistory } from 'vue-router'
import { server } from '../msw/server'
import DietLogEditView from '@/views/DietLogEditView.vue'
import en from '@/i18n/en.json'
import type { DailyDietLogRequest } from '@/types/api'

const i18n = createI18n({ legacy: false, locale: 'en', messages: { en } })

function makeRouter() {
  return createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/diet-logs/:date', component: DietLogEditView }],
  })
}

describe('DietLogEditView', () => {
  beforeEach(() => setActivePinia(createPinia()))

  it('initializes a blank form when the day has no log (404)', async () => {
    server.use(
      http.get('/api/diet-logs/2026-07-24', () => HttpResponse.json({ error: 'not_found' }, { status: 404 })),
    )
    const router = makeRouter()
    await router.push('/diet-logs/2026-07-24')
    const wrapper = mount(DietLogEditView, { global: { plugins: [createPinia(), i18n, router] } })
    await flushPromises()
    expect(wrapper.find('select[data-testid="adherence"]').exists()).toBe(true)
    expect(wrapper.findAll('[data-testid^="meal-row-"]')).toHaveLength(0)
  })

  it('upserts the full log on save', async () => {
    let received: DailyDietLogRequest | null = null
    server.use(
      http.get('/api/diet-logs/2026-07-24', () => HttpResponse.json({ error: 'not_found' }, { status: 404 })),
      http.get('/api/csrf', () => HttpResponse.json({ token: 't', headerName: 'X-XSRF-TOKEN' })),
      http.post('/api/diet-logs', async ({ request }) => {
        received = (await request.json()) as DailyDietLogRequest
        return HttpResponse.json({ id: 1, logDate: '2026-07-24' })
      }),
    )
    const router = makeRouter()
    await router.push('/diet-logs/2026-07-24')
    const wrapper = mount(DietLogEditView, { global: { plugins: [createPinia(), i18n, router] } })
    await flushPromises()

    await wrapper.find('[data-testid="add-meal"]').trigger('click')
    await wrapper.find('input[data-testid="meal-desc-0"]').setValue('Eggs and avocado')
    await wrapper.find('[data-testid="save"]').trigger('click')
    await flushPromises()

    expect(received).not.toBeNull()
    expect(received!.logDate).toBe('2026-07-24')
    expect(received!.meals).toHaveLength(1)
    expect(received!.meals[0].foodDescription).toBe('Eggs and avocado')
    expect(wrapper.text()).toContain(en.common.saved)
  })

  it('shows an error and withholds the editor when loading fails (non-404)', async () => {
    server.use(
      http.get('/api/diet-logs/2026-07-24', () => HttpResponse.json({ error: 'request_failed' }, { status: 500 })),
    )
    const router = makeRouter()
    await router.push('/diet-logs/2026-07-24')
    const wrapper = mount(DietLogEditView, { global: { plugins: [createPinia(), i18n, router] } })
    await flushPromises()
    expect(wrapper.text()).toContain(en.errors.request_failed)
    expect(wrapper.find('[data-testid="save"]').exists()).toBe(false)
  })
})
```

Add i18n keys to `en.json`:

```json
  "dietLog": {
    "title": "Diet log",
    "adherence": "Diet adherence",
    "appetite": "Appetite",
    "notes": "Notes",
    "meals": "Meals",
    "addMeal": "Add meal",
    "mealType": "Meal type",
    "foodDescription": "Food description",
    "deviations": "Deviations",
    "addDeviation": "Add deviation",
    "deviationCategory": "Category",
    "deviationSeverity": "Severity",
    "measurements": "Measurements",
    "addMeasurement": "Add measurement",
    "measurementType": "Type",
    "measurementValue": "Value",
    "measurementUnit": "Unit",
    "measurementContext": "Context",
    "measuredAt": "Measured at",
    "photos": "Photos",
    "photoCaption": "Caption (optional)",
    "history": "History",
    "mealCount": "Meals",
    "deviationCount": "Deviations",
    "measurementCount": "Measurements",
    "open": "Open"
  },
  "enums": {
    "DietAdherenceLevel": { "FULL": "Full", "MOSTLY": "Mostly", "PARTIAL": "Partial", "LOW": "Low", "NOT_FOLLOWED": "Not followed" },
    "AppetiteLevel": { "LOW": "Low", "NORMAL": "Normal", "HIGH": "High", "VARIABLE": "Variable" },
    "MealType": { "BREAKFAST": "Breakfast", "LUNCH": "Lunch", "DINNER": "Dinner", "SNACK": "Snack", "DRINK": "Drink", "OTHER": "Other" },
    "DietDeviationCategory": { "EXCESS_CARBS": "Excess carbs", "NON_PROTOCOL_FOOD": "Non-protocol food", "MISSED_MEAL": "Missed meal", "DINING_OUT": "Dining out", "ALCOHOL": "Alcohol", "GI_TOLERANCE": "GI tolerance", "OTHER": "Other" },
    "DietDeviationSeverity": { "MINOR": "Minor", "MODERATE": "Moderate", "MAJOR": "Major" },
    "MeasurementType": { "GLUCOSE": "Glucose", "KETONE": "Ketones" },
    "MeasurementUnit": { "MMOL_L": "mmol/L", "MG_DL": "mg/dL" },
    "MeasurementContext": { "FASTING": "Fasting", "PRE_MEAL": "Before meal", "POST_MEAL": "After meal", "BEDTIME": "Bedtime", "SYMPTOMS": "Symptoms", "OTHER": "Other" }
  }
```

and `cs.json`:

```json
  "dietLog": {
    "title": "Denník stravy",
    "adherence": "Dodržování diety",
    "appetite": "Chuť k jídlu",
    "notes": "Poznámky",
    "meals": "Jídla",
    "addMeal": "Přidat jídlo",
    "mealType": "Typ jídla",
    "foodDescription": "Popis jídla",
    "deviations": "Odchylky",
    "addDeviation": "Přidat odchylku",
    "deviationCategory": "Kategorie",
    "deviationSeverity": "Závažnost",
    "measurements": "Měření",
    "addMeasurement": "Přidat měření",
    "measurementType": "Typ",
    "measurementValue": "Hodnota",
    "measurementUnit": "Jednotka",
    "measurementContext": "Kontext",
    "measuredAt": "Změřeno",
    "photos": "Fotografie",
    "photoCaption": "Popisek (nepovinný)",
    "history": "Historie",
    "mealCount": "Jídla",
    "deviationCount": "Odchylky",
    "measurementCount": "Měření",
    "open": "Otevřít"
  },
  "enums": {
    "DietAdherenceLevel": { "FULL": "Plné", "MOSTLY": "Převážně", "PARTIAL": "Částečné", "LOW": "Nízké", "NOT_FOLLOWED": "Nedodržováno" },
    "AppetiteLevel": { "LOW": "Nízká", "NORMAL": "Normální", "HIGH": "Vysoká", "VARIABLE": "Měnlivá" },
    "MealType": { "BREAKFAST": "Snídaně", "LUNCH": "Oběd", "DINNER": "Večeře", "SNACK": "Svačina", "DRINK": "Nápoj", "OTHER": "Jiné" },
    "DietDeviationCategory": { "EXCESS_CARBS": "Přebytek sacharidů", "NON_PROTOCOL_FOOD": "Potravina mimo protokol", "MISSED_MEAL": "Vynechané jídlo", "DINING_OUT": "Stravování venku", "ALCOHOL": "Alkohol", "GI_TOLERANCE": "GI tolerance", "OTHER": "Jiné" },
    "DietDeviationSeverity": { "MINOR": "Mírná", "MODERATE": "Střední", "MAJOR": "Závažná" },
    "MeasurementType": { "GLUCOSE": "Glukóza", "KETONE": "Ketony" },
    "MeasurementUnit": { "MMOL_L": "mmol/L", "MG_DL": "mg/dL" },
    "MeasurementContext": { "FASTING": "Nalačno", "PRE_MEAL": "Před jídlem", "POST_MEAL": "Po jídle", "BEDTIME": "Před spaním", "SYMPTOMS": "Příznaky", "OTHER": "Jiné" }
  }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && npm run test -- tests/views/DietLogEditView.test.ts`
Expected: FAIL — view does not exist.

- [ ] **Step 3: Implement `frontend/src/components/PhotoUpload.vue`**

```vue
<script setup lang="ts">
import { ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { dietLogApi } from '@/api/dietLogs'
import { useApiError } from '@/composables/useApiError'
import type { DietLogPhotoUploadResponse } from '@/types/api'

const emit = defineEmits<{ uploaded: [photo: DietLogPhotoUploadResponse] }>()
const { t } = useI18n()
const { message, capture } = useApiError()
const uploading = ref(false)

async function onFile(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) return
  uploading.value = true
  try {
    const res = await dietLogApi.uploadPhoto(file)
    emit('uploaded', res)
  } catch (e) {
    capture(e)
  } finally {
    uploading.value = false
    input.value = ''
  }
}
</script>

<template>
  <div>
    <input type="file" accept="image/*" :disabled="uploading" data-testid="photo-input" @change="onFile" />
    <p v-if="uploading" class="text-sm text-gray-500">{{ t('common.loading') }}</p>
    <p v-if="message" class="text-sm text-red-600">{{ message }}</p>
  </div>
</template>
```

- [ ] **Step 4: Implement `frontend/src/views/DietLogEditView.vue`**

```vue
<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { ApiError } from '@/api/http'
import { dietLogApi } from '@/api/dietLogs'
import { useApiError } from '@/composables/useApiError'
import FieldError from '@/components/FieldError.vue'
import PhotoUpload from '@/components/PhotoUpload.vue'
import type {
  AppetiteLevel,
  DailyMeasurementEntryRequest,
  DeviationRequest,
  DietAdherenceLevel,
  DietDeviationCategory,
  DietDeviationSeverity,
  DietLogPhotoUploadResponse,
  MealRequest,
  MealType,
  MeasurementContext,
  MeasurementType,
  MeasurementUnit,
  PhotoUploadReferenceRequest,
} from '@/types/api'

const { t } = useI18n()
const route = useRoute()
const { message, fieldErrors, capture, clear } = useApiError()

const logDate = computed(() => route.params.date as string)

const adherenceLevel = ref<DietAdherenceLevel>('FULL')
const appetiteLevel = ref<AppetiteLevel>('NORMAL')
const notes = ref('')
const meals = reactive<MealRequest[]>([])
const deviations = reactive<DeviationRequest[]>([])
const photoReferences = reactive<(PhotoUploadReferenceRequest & { contentUrl?: string })[]>([])
const measurements = reactive<DailyMeasurementEntryRequest[]>([])
const loading = ref(true)
const loadFailed = ref(false)
const saved = ref(false)

const adherenceOptions: DietAdherenceLevel[] = ['FULL', 'MOSTLY', 'PARTIAL', 'LOW', 'NOT_FOLLOWED']
const appetiteOptions: AppetiteLevel[] = ['LOW', 'NORMAL', 'HIGH', 'VARIABLE']
const mealTypeOptions: MealType[] = ['BREAKFAST', 'LUNCH', 'DINNER', 'SNACK', 'DRINK', 'OTHER']
const deviationCategoryOptions: DietDeviationCategory[] = ['EXCESS_CARBS', 'NON_PROTOCOL_FOOD', 'MISSED_MEAL', 'DINING_OUT', 'ALCOHOL', 'GI_TOLERANCE', 'OTHER']
const deviationSeverityOptions: DietDeviationSeverity[] = ['MINOR', 'MODERATE', 'MAJOR']
const measurementTypeOptions: MeasurementType[] = ['GLUCOSE', 'KETONE']
const measurementUnitOptions: MeasurementUnit[] = ['MMOL_L', 'MG_DL']
const measurementContextOptions: MeasurementContext[] = ['FASTING', 'PRE_MEAL', 'POST_MEAL', 'BEDTIME', 'SYMPTOMS', 'OTHER']

function nowLocalIsoMinute(): string {
  const d = new Date()
  d.setSeconds(0, 0)
  return d.toISOString()
}

function toLocalInputValue(iso: string): string {
  const d = new Date(iso)
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`
}

onMounted(async () => {
  try {
    const log = await dietLogApi.get(logDate.value)
    adherenceLevel.value = log.adherenceLevel
    appetiteLevel.value = log.appetiteLevel
    notes.value = log.notes ?? ''
    meals.push(...log.meals.map((m) => ({ mealType: m.mealType, foodDescription: m.foodDescription ?? '', notes: m.notes ?? '' })))
    const mealIndexById = new Map(log.meals.map((m, idx) => [m.id, idx]))
    deviations.push(...log.deviations.map((d) => ({
      mealIndex: d.mealId == null ? null : mealIndexById.get(d.mealId) ?? null,
      deviationCategory: d.deviationCategory,
      severity: d.severity,
      notes: d.notes ?? '',
    })))
    photoReferences.push(...log.photoReferences.map((p) => ({ mealIndex: null, uploadId: p.id, caption: p.caption ?? '', contentUrl: p.contentUrl })))
    measurements.push(...log.measurements.map((m) => ({
      measurementType: m.measurementType,
      value: m.value,
      unit: m.unit,
      measuredAt: m.measuredAt,
      context: m.context,
      notes: m.notes ?? '',
    })))
  } catch (e) {
    if (e instanceof ApiError && e.status === 404) {
      // no entry for this date yet — start blank
    } else {
      // Load failed: show the error and withhold the editor so a blind save
      // cannot wipe the day's existing data (the save is a replacing upsert).
      capture(e)
      loadFailed.value = true
    }
  } finally {
    loading.value = false
  }
})

function addMeal() {
  meals.push({ mealType: 'BREAKFAST', foodDescription: '', notes: '' })
}

function addDeviation() {
  deviations.push({ mealIndex: null, deviationCategory: 'OTHER', severity: 'MINOR', notes: '' })
}

function addMeasurement() {
  measurements.push({ measurementType: 'GLUCOSE', value: 5.0, unit: 'MMOL_L', measuredAt: nowLocalIsoMinute(), context: 'FASTING', notes: '' })
}

function onPhotoUploaded(photo: DietLogPhotoUploadResponse) {
  photoReferences.push({ mealIndex: null, uploadId: photo.uploadId, caption: '', contentUrl: photo.contentUrl })
}

async function save() {
  clear()
  saved.value = false
  try {
    await dietLogApi.save({
      logDate: logDate.value,
      adherenceLevel: adherenceLevel.value,
      appetiteLevel: appetiteLevel.value,
      notes: notes.value || undefined,
      meals,
      deviations,
      photoReferences: photoReferences.map(({ contentUrl: _contentUrl, ...rest }) => rest),
      measurements,
    })
    saved.value = true
  } catch (e) {
    capture(e)
  }
}
</script>

<template>
  <section>
    <h1 class="text-2xl font-semibold">{{ t('dietLog.title') }} — {{ logDate }}</h1>
    <p v-if="loading" class="mt-4">{{ t('common.loading') }}</p>
    <p v-else-if="loadFailed" class="mt-4 rounded bg-red-50 p-3 text-sm text-red-700">{{ message }}</p>
    <div v-else class="mt-4 space-y-6">
      <p v-if="message" class="rounded bg-red-50 p-3 text-sm text-red-700">{{ message }}</p>
      <p v-if="saved" class="rounded bg-green-50 p-3 text-sm text-green-700">{{ t('common.saved') }}</p>

      <div class="grid gap-4 rounded border bg-white p-4 sm:grid-cols-2">
        <div>
          <label class="block text-sm font-medium" for="adherence">{{ t('dietLog.adherence') }}</label>
          <select id="adherence" v-model="adherenceLevel" data-testid="adherence"
                  class="mt-1 w-full rounded border border-gray-300 px-3 py-2">
            <option v-for="o in adherenceOptions" :key="o" :value="o">{{ t(`enums.DietAdherenceLevel.${o}`) }}</option>
          </select>
          <FieldError :message="fieldErrors.adherenceLevel" />
        </div>
        <div>
          <label class="block text-sm font-medium" for="appetite">{{ t('dietLog.appetite') }}</label>
          <select id="appetite" v-model="appetiteLevel" class="mt-1 w-full rounded border border-gray-300 px-3 py-2">
            <option v-for="o in appetiteOptions" :key="o" :value="o">{{ t(`enums.AppetiteLevel.${o}`) }}</option>
          </select>
          <FieldError :message="fieldErrors.appetiteLevel" />
        </div>
        <div class="sm:col-span-2">
          <label class="block text-sm font-medium" for="notes">{{ t('dietLog.notes') }}</label>
          <textarea id="notes" v-model="notes" maxlength="1000" rows="2"
                    class="mt-1 w-full rounded border border-gray-300 px-3 py-2" />
          <FieldError :message="fieldErrors.notes" />
        </div>
      </div>

      <div class="rounded border bg-white p-4">
        <div class="flex items-center justify-between">
          <h2 class="font-medium">{{ t('dietLog.meals') }}</h2>
          <button data-testid="add-meal" class="rounded border px-3 py-1 text-sm" @click="addMeal">{{ t('dietLog.addMeal') }}</button>
        </div>
        <div v-for="(meal, i) in meals" :key="i" :data-testid="`meal-row-${i}`" class="mt-3 grid gap-2 border-t pt-3 sm:grid-cols-[10rem_1fr_auto]">
          <select v-model="meal.mealType" class="rounded border border-gray-300 px-2 py-1">
            <option v-for="o in mealTypeOptions" :key="o" :value="o">{{ t(`enums.MealType.${o}`) }}</option>
          </select>
          <input v-model="meal.foodDescription" :data-testid="`meal-desc-${i}`" type="text" maxlength="500"
                 :placeholder="t('dietLog.foodDescription')" class="rounded border border-gray-300 px-2 py-1" />
          <button class="text-sm text-red-600" @click="meals.splice(i, 1)">{{ t('common.remove') }}</button>
        </div>
      </div>

      <div class="rounded border bg-white p-4">
        <div class="flex items-center justify-between">
          <h2 class="font-medium">{{ t('dietLog.deviations') }}</h2>
          <button class="rounded border px-3 py-1 text-sm" @click="addDeviation">{{ t('dietLog.addDeviation') }}</button>
        </div>
        <div v-for="(dev, i) in deviations" :key="i" class="mt-3 grid gap-2 border-t pt-3 sm:grid-cols-[1fr_8rem_1fr_auto]">
          <select v-model="dev.deviationCategory" class="rounded border border-gray-300 px-2 py-1">
            <option v-for="o in deviationCategoryOptions" :key="o" :value="o">{{ t(`enums.DietDeviationCategory.${o}`) }}</option>
          </select>
          <select v-model="dev.severity" class="rounded border border-gray-300 px-2 py-1">
            <option v-for="o in deviationSeverityOptions" :key="o" :value="o">{{ t(`enums.DietDeviationSeverity.${o}`) }}</option>
          </select>
          <input v-model="dev.notes" type="text" maxlength="1000" :placeholder="t('dietLog.notes')"
                 class="rounded border border-gray-300 px-2 py-1" />
          <button class="text-sm text-red-600" @click="deviations.splice(i, 1)">{{ t('common.remove') }}</button>
        </div>
      </div>

      <div class="rounded border bg-white p-4">
        <div class="flex items-center justify-between">
          <h2 class="font-medium">{{ t('dietLog.measurements') }}</h2>
          <button class="rounded border px-3 py-1 text-sm" @click="addMeasurement">{{ t('dietLog.addMeasurement') }}</button>
        </div>
        <div v-for="(m, i) in measurements" :key="i" class="mt-3 grid gap-2 border-t pt-3 sm:grid-cols-[8rem_6rem_7rem_1fr_auto]">
          <select v-model="m.measurementType" class="rounded border border-gray-300 px-2 py-1">
            <option v-for="o in measurementTypeOptions" :key="o" :value="o">{{ t(`enums.MeasurementType.${o}`) }}</option>
          </select>
          <input v-model.number="m.value" type="number" step="0.1" min="0" class="rounded border border-gray-300 px-2 py-1" />
          <select v-model="m.unit" class="rounded border border-gray-300 px-2 py-1">
            <option v-for="o in measurementUnitOptions" :key="o" :value="o">{{ t(`enums.MeasurementUnit.${o}`) }}</option>
          </select>
          <div class="flex gap-2">
            <select v-model="m.context" class="rounded border border-gray-300 px-2 py-1">
              <option v-for="o in measurementContextOptions" :key="o" :value="o">{{ t(`enums.MeasurementContext.${o}`) }}</option>
            </select>
            <input :value="toLocalInputValue(m.measuredAt)" type="datetime-local"
                   class="rounded border border-gray-300 px-2 py-1"
                   @input="m.measuredAt = new Date(($event.target as HTMLInputElement).value).toISOString()" />
          </div>
          <button class="text-sm text-red-600" @click="measurements.splice(i, 1)">{{ t('common.remove') }}</button>
        </div>
      </div>

      <div class="rounded border bg-white p-4">
        <h2 class="font-medium">{{ t('dietLog.photos') }}</h2>
        <PhotoUpload class="mt-2" @uploaded="onPhotoUploaded" />
        <div class="mt-3 flex flex-wrap gap-3">
          <figure v-for="(p, i) in photoReferences" :key="p.uploadId" class="w-32">
            <img :src="p.contentUrl" :alt="p.caption ?? ''" class="h-24 w-32 rounded border object-cover" />
            <input v-model="p.caption" type="text" maxlength="500" :placeholder="t('dietLog.photoCaption')"
                   class="mt-1 w-full rounded border border-gray-300 px-2 py-1 text-xs" />
            <button class="mt-1 text-xs text-red-600" @click="photoReferences.splice(i, 1)">{{ t('common.remove') }}</button>
          </figure>
        </div>
      </div>

      <button data-testid="save" class="rounded bg-blue-600 px-6 py-2 text-white" @click="save">{{ t('common.save') }}</button>
    </div>
  </section>
</template>
```

- [ ] **Step 5: Add the route in `frontend/src/router/index.ts`**

Import and add inside AppShell `children`:

```ts
import DietLogEditView from '@/views/DietLogEditView.vue'
```

```ts
      { path: 'diet-logs/:date', component: DietLogEditView, meta: { requiresAuth: true } },
```

- [ ] **Step 6: Run tests, typecheck, build**

Run: `cd frontend && npm run test && npm run typecheck && npm run build`
Expected: all PASS.

- [ ] **Step 7: Commit**

```bash
git add frontend
git commit -m "Add SPA diet log editor with photos and measurements"
```

---

### Task 8: Diet log history

**Files:**
- Create: `frontend/src/views/DietLogHistoryView.vue`
- Modify: `frontend/src/router/index.ts` (add child route `diet-logs`)

No new test — this is a thin list view exercising `dietLogApi.list`, which is already covered by the Task 7 editor test path through MSW. (Keeping the test budget on behavior, per the spec's one-component-test-per-domain rule, which the diet editor test satisfies.)

- [ ] **Step 1: Implement `frontend/src/views/DietLogHistoryView.vue`**

```vue
<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { dietLogApi } from '@/api/dietLogs'
import { useApiError } from '@/composables/useApiError'
import type { DailyDietLogSummary } from '@/types/api'

const { t } = useI18n()
const { message, capture } = useApiError()

function iso(d: Date): string {
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

const today = new Date()
const monthAgo = new Date(today)
monthAgo.setDate(monthAgo.getDate() - 30)

const from = ref(iso(monthAgo))
const to = ref(iso(today))
const logs = ref<DailyDietLogSummary[]>([])
const loading = ref(true)

async function load() {
  loading.value = true
  try {
    logs.value = await dietLogApi.list(from.value, to.value)
  } catch (e) {
    capture(e)
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <section>
    <h1 class="text-2xl font-semibold">{{ t('dietLog.history') }}</h1>
    <div class="mt-4 flex flex-wrap items-end gap-3">
      <label class="text-sm">{{ t('common.from') }}
        <input v-model="from" type="date" class="ml-1 rounded border border-gray-300 px-2 py-1" />
      </label>
      <label class="text-sm">{{ t('common.to') }}
        <input v-model="to" type="date" class="ml-1 rounded border border-gray-300 px-2 py-1" />
      </label>
      <button class="rounded bg-blue-600 px-3 py-1 text-sm text-white" @click="load">{{ t('common.apply') }}</button>
    </div>

    <p v-if="message" class="mt-4 rounded bg-red-50 p-3 text-sm text-red-700">{{ message }}</p>
    <p v-if="loading" class="mt-4">{{ t('common.loading') }}</p>
    <table v-else class="mt-4 w-full border-collapse bg-white text-sm">
      <thead>
        <tr class="border-b text-left">
          <th class="p-2">{{ t('dashboard.dietLog') }}</th>
          <th class="p-2">{{ t('dietLog.adherence') }}</th>
          <th class="p-2">{{ t('dietLog.mealCount') }}</th>
          <th class="p-2">{{ t('dietLog.deviationCount') }}</th>
          <th class="p-2">{{ t('dietLog.measurementCount') }}</th>
          <th class="p-2"></th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="log in logs" :key="log.id" class="border-b">
          <td class="p-2">{{ log.logDate }}</td>
          <td class="p-2">{{ t(`enums.DietAdherenceLevel.${log.adherenceLevel}`) }}</td>
          <td class="p-2">{{ log.mealCount }}</td>
          <td class="p-2">{{ log.deviationCount }}</td>
          <td class="p-2">{{ log.measurementCount }}</td>
          <td class="p-2">
            <router-link :to="`/diet-logs/${log.logDate}`" class="text-blue-600">{{ t('dietLog.open') }}</router-link>
          </td>
        </tr>
      </tbody>
    </table>
  </section>
</template>
```

- [ ] **Step 2: Add the route (before `diet-logs/:date` so the exact path matches first)**

Inside AppShell `children`, directly above the `diet-logs/:date` entry:

```ts
      { path: 'diet-logs', component: DietLogHistoryView, meta: { requiresAuth: true } },
```

with import:

```ts
import DietLogHistoryView from '@/views/DietLogHistoryView.vue'
```

- [ ] **Step 3: Run tests, typecheck, build**

Run: `cd frontend && npm run test && npm run typecheck && npm run build`
Expected: all PASS.

- [ ] **Step 4: Commit**

```bash
git add frontend
git commit -m "Add SPA diet log history"
```

---

### Task 9: Symptom check-in form + list

**Files:**
- Create: `frontend/src/views/CheckInEditView.vue`, `frontend/src/views/CheckInListView.vue`
- Modify: `frontend/src/router/index.ts` (child routes `check-ins`, `check-ins/:date`)
- Test: `frontend/tests/views/CheckInEditView.test.ts`

**Interfaces:**
- Consumes: `symptomApi` (Task 5), `SymptomQuestionnaire`, `SymptomCheckInRequest`, `AnswerRequest`, `FlareState`.
- Produces: routes `/check-ins`, `/check-ins/:date`.

Behavior notes: the form renders the *active questionnaire* (`GET /api/symptom-questionnaires/active`) and submits `questionnaireVersionId` from it. `POST /api/symptom-check-ins` upserts by `checkInDate`. If a check-in already exists for the date (`GET /api/symptom-check-ins/{date}` 200), pre-fill answers matched by `questionId` — but only when the existing check-in's `questionnaireVersionId` equals the active questionnaire's `versionId`; otherwise render blank (answers reference a different questionnaire version).

- [ ] **Step 1: Write the failing test `frontend/tests/views/CheckInEditView.test.ts`**

```ts
import { flushPromises, mount } from '@vue/test-utils'
import { http, HttpResponse } from 'msw'
import { beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { createI18n } from 'vue-i18n'
import { createRouter, createMemoryHistory } from 'vue-router'
import { server } from '../msw/server'
import CheckInEditView from '@/views/CheckInEditView.vue'
import en from '@/i18n/en.json'
import type { SymptomCheckInRequest, SymptomQuestionnaire } from '@/types/api'

const i18n = createI18n({ legacy: false, locale: 'en', messages: { en } })

const questionnaire: SymptomQuestionnaire = {
  id: 1,
  stableKey: 'daily',
  displayName: 'Daily symptoms',
  versionId: 11,
  versionNumber: 3,
  questions: [
    {
      id: 101, stableKey: 'pain', label: 'Abdominal pain', helpText: null,
      answerType: 'SINGLE_CHOICE', required: true,
      minNumericValue: null, maxNumericValue: null,
      options: [
        { id: 1001, stableKey: 'none', label: 'None', numericScore: 0 },
        { id: 1002, stableKey: 'severe', label: 'Severe', numericScore: 3 },
      ],
    },
    {
      id: 102, stableKey: 'stools', label: 'Stool count', helpText: null,
      answerType: 'NUMERIC', required: true,
      minNumericValue: 0, maxNumericValue: 20, options: [],
    },
  ],
}

function makeRouter() {
  return createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/check-ins/:date', component: CheckInEditView }],
  })
}

describe('CheckInEditView', () => {
  beforeEach(() => setActivePinia(createPinia()))

  it('renders questionnaire questions by answer type', async () => {
    server.use(
      http.get('/api/symptom-questionnaires/active', () => HttpResponse.json(questionnaire)),
      http.get('/api/symptom-check-ins/2026-07-24', () => HttpResponse.json({ error: 'not_found' }, { status: 404 })),
    )
    const router = makeRouter()
    await router.push('/check-ins/2026-07-24')
    const wrapper = mount(CheckInEditView, { global: { plugins: [createPinia(), i18n, router] } })
    await flushPromises()
    expect(wrapper.text()).toContain('Abdominal pain')
    expect(wrapper.findAll('input[type="radio"]')).toHaveLength(2)
    expect(wrapper.find('input[type="number"]').exists()).toBe(true)
  })

  it('submits answers with the questionnaire version id', async () => {
    let received: SymptomCheckInRequest | null = null
    server.use(
      http.get('/api/symptom-questionnaires/active', () => HttpResponse.json(questionnaire)),
      http.get('/api/symptom-check-ins/2026-07-24', () => HttpResponse.json({ error: 'not_found' }, { status: 404 })),
      http.get('/api/csrf', () => HttpResponse.json({ token: 't', headerName: 'X-XSRF-TOKEN' })),
      http.post('/api/symptom-check-ins', async ({ request }) => {
        received = (await request.json()) as SymptomCheckInRequest
        return HttpResponse.json({ id: 1 })
      }),
    )
    const router = makeRouter()
    await router.push('/check-ins/2026-07-24')
    const wrapper = mount(CheckInEditView, { global: { plugins: [createPinia(), i18n, router] } })
    await flushPromises()

    await wrapper.findAll('input[type="radio"]')[1].setValue(true)
    await wrapper.find('input[type="number"]').setValue(4)
    await wrapper.find('[data-testid="save"]').trigger('click')
    await flushPromises()

    expect(received).not.toBeNull()
    expect(received!.questionnaireVersionId).toBe(11)
    expect(received!.checkInDate).toBe('2026-07-24')
    expect(received!.answers).toContainEqual({ questionId: 101, optionId: 1002, answerText: null, answerNumeric: null })
    expect(received!.answers).toContainEqual({ questionId: 102, optionId: null, answerText: null, answerNumeric: 4 })
  })

  it('shows an error and withholds the editor when the existing check-in fails to load (non-404)', async () => {
    server.use(
      http.get('/api/symptom-questionnaires/active', () => HttpResponse.json(questionnaire)),
      http.get('/api/symptom-check-ins/2026-07-24', () => HttpResponse.json({ error: 'request_failed' }, { status: 500 })),
    )
    const router = makeRouter()
    await router.push('/check-ins/2026-07-24')
    const wrapper = mount(CheckInEditView, { global: { plugins: [createPinia(), i18n, router] } })
    await flushPromises()
    expect(wrapper.text()).toContain(en.errors.request_failed)
    expect(wrapper.find('[data-testid="save"]').exists()).toBe(false)
  })
})
```

Add i18n keys to `en.json`:

```json
  "checkIn": {
    "title": "Symptom check-in",
    "flareState": "Flare state",
    "notes": "Notes",
    "history": "Past check-ins",
    "score": "Score",
    "FlareState": { "NO_FLARE": "No flare", "SUSPECTED_FLARE": "Suspected flare", "ACTIVE_FLARE": "Active flare" }
  }
```

and `cs.json`:

```json
  "checkIn": {
    "title": "Kontrola příznaků",
    "flareState": "Stav vzplanutí",
    "notes": "Poznámky",
    "history": "Minulé kontroly",
    "score": "Skóre",
    "FlareState": { "NO_FLARE": "Bez vzplanutí", "SUSPECTED_FLARE": "Podezření na vzplanutí", "ACTIVE_FLARE": "Aktivní vzplanutí" }
  }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && npm run test -- tests/views/CheckInEditView.test.ts`
Expected: FAIL — view does not exist.

- [ ] **Step 3: Implement `frontend/src/views/CheckInEditView.vue`**

```vue
<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { ApiError } from '@/api/http'
import { symptomApi } from '@/api/symptoms'
import { useApiError } from '@/composables/useApiError'
import type { AnswerRequest, FlareState, SymptomQuestionnaire } from '@/types/api'

const { t } = useI18n()
const route = useRoute()
const { message, capture, clear } = useApiError()

const checkInDate = computed(() => route.params.date as string)
const flareOptions: FlareState[] = ['NO_FLARE', 'SUSPECTED_FLARE', 'ACTIVE_FLARE']

const questionnaire = ref<SymptomQuestionnaire | null>(null)
const flareState = ref<FlareState>('NO_FLARE')
const notes = ref('')
// questionId -> partial answer state
const answers = reactive<Record<number, { optionId: number | null; answerText: string; answerNumeric: number | null }>>({})
const loading = ref(true)
const loadFailed = ref(false)
const saved = ref(false)

onMounted(async () => {
  const q = await symptomApi.activeQuestionnaire()
  questionnaire.value = q
  for (const question of q.questions) {
    answers[question.id] = { optionId: null, answerText: '', answerNumeric: null }
  }
  try {
    const existing = await symptomApi.getCheckIn(checkInDate.value)
    if (existing.questionnaireVersionId === q.versionId) {
      flareState.value = existing.flareState
      notes.value = existing.notes ?? ''
      for (const a of existing.answers) {
        if (answers[a.questionId]) {
          answers[a.questionId] = {
            optionId: a.optionId,
            answerText: a.answerText ?? '',
            answerNumeric: a.answerNumeric,
          }
        }
      }
    }
  } catch (e) {
    if (e instanceof ApiError && e.status === 404) {
      // no entry for this date yet — start blank
    } else {
      // Load failed: show the error and withhold the editor so a blind save
      // cannot wipe the day's existing data (the save is a replacing upsert).
      capture(e)
      loadFailed.value = true
    }
  } finally {
    loading.value = false
  }
})

async function save() {
  clear()
  saved.value = false
  const q = questionnaire.value
  if (!q) return
  const payload: AnswerRequest[] = q.questions
    .map((question) => ({
      questionId: question.id,
      optionId: answers[question.id].optionId,
      answerText: question.answerType === 'TEXT' ? answers[question.id].answerText || null : null,
      answerNumeric: question.answerType === 'NUMERIC' ? answers[question.id].answerNumeric : null,
    }))
    .filter((a) => a.optionId !== null || a.answerText !== null || a.answerNumeric !== null)
  try {
    await symptomApi.saveCheckIn({
      checkInDate: checkInDate.value,
      questionnaireVersionId: q.versionId,
      flareState: flareState.value,
      answers: payload,
      notes: notes.value || undefined,
    })
    saved.value = true
  } catch (e) {
    capture(e)
  }
}
</script>

<template>
  <section class="max-w-2xl">
    <h1 class="text-2xl font-semibold">{{ t('checkIn.title') }} — {{ checkInDate }}</h1>
    <p v-if="loading" class="mt-4">{{ t('common.loading') }}</p>
    <p v-else-if="loadFailed" class="mt-4 rounded bg-red-50 p-3 text-sm text-red-700">{{ message }}</p>
    <div v-else-if="questionnaire" class="mt-4 space-y-6">
      <p v-if="message" class="rounded bg-red-50 p-3 text-sm text-red-700">{{ message }}</p>
      <p v-if="saved" class="rounded bg-green-50 p-3 text-sm text-green-700">{{ t('common.saved') }}</p>

      <div>
        <label class="block text-sm font-medium" for="flare">{{ t('checkIn.flareState') }}</label>
        <select id="flare" v-model="flareState" class="mt-1 w-full rounded border border-gray-300 px-3 py-2">
          <option v-for="f in flareOptions" :key="f" :value="f">{{ t(`checkIn.FlareState.${f}`) }}</option>
        </select>
      </div>

      <div v-for="question in questionnaire.questions" :key="question.id" class="rounded border bg-white p-4">
        <p class="font-medium">{{ question.label }} <span v-if="question.required" class="text-red-500">*</span></p>
        <p v-if="question.helpText" class="mt-1 text-sm text-gray-500">{{ question.helpText }}</p>

        <div v-if="question.answerType === 'SINGLE_CHOICE'" class="mt-2 space-y-1">
          <label v-for="option in question.options" :key="option.id" class="flex items-center gap-2 text-sm">
            <input v-model="answers[question.id].optionId" type="radio" :name="`q-${question.id}`" :value="option.id" />
            {{ option.label }}
          </label>
        </div>
        <input v-else-if="question.answerType === 'NUMERIC'" v-model.number="answers[question.id].answerNumeric"
               type="number" :min="question.minNumericValue ?? undefined" :max="question.maxNumericValue ?? undefined"
               class="mt-2 w-32 rounded border border-gray-300 px-2 py-1" />
        <textarea v-else v-model="answers[question.id].answerText" rows="2" maxlength="1000"
                  class="mt-2 w-full rounded border border-gray-300 px-3 py-2" />
      </div>

      <div>
        <label class="block text-sm font-medium" for="notes">{{ t('checkIn.notes') }}</label>
        <textarea id="notes" v-model="notes" rows="2" maxlength="1000"
                  class="mt-1 w-full rounded border border-gray-300 px-3 py-2" />
      </div>

      <button data-testid="save" class="rounded bg-blue-600 px-6 py-2 text-white" @click="save">{{ t('common.save') }}</button>
    </div>
  </section>
</template>
```

- [ ] **Step 4: Implement `frontend/src/views/CheckInListView.vue`**

```vue
<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { symptomApi } from '@/api/symptoms'
import { useApiError } from '@/composables/useApiError'
import type { SymptomCheckInResponse } from '@/types/api'

const { t } = useI18n()
const { message, capture } = useApiError()

function iso(d: Date): string {
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

const today = new Date()
const monthAgo = new Date(today)
monthAgo.setDate(monthAgo.getDate() - 30)

const from = ref(iso(monthAgo))
const to = ref(iso(today))
const checkIns = ref<SymptomCheckInResponse[]>([])
const loading = ref(true)

async function load() {
  loading.value = true
  try {
    checkIns.value = await symptomApi.listCheckIns(from.value, to.value)
  } catch (e) {
    capture(e)
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <section>
    <h1 class="text-2xl font-semibold">{{ t('checkIn.history') }}</h1>
    <div class="mt-4 flex flex-wrap items-end gap-3">
      <label class="text-sm">{{ t('common.from') }}
        <input v-model="from" type="date" class="ml-1 rounded border border-gray-300 px-2 py-1" />
      </label>
      <label class="text-sm">{{ t('common.to') }}
        <input v-model="to" type="date" class="ml-1 rounded border border-gray-300 px-2 py-1" />
      </label>
      <button class="rounded bg-blue-600 px-3 py-1 text-sm text-white" @click="load">{{ t('common.apply') }}</button>
    </div>

    <p v-if="message" class="mt-4 rounded bg-red-50 p-3 text-sm text-red-700">{{ message }}</p>
    <p v-if="loading" class="mt-4">{{ t('common.loading') }}</p>
    <table v-else class="mt-4 w-full border-collapse bg-white text-sm">
      <thead>
        <tr class="border-b text-left">
          <th class="p-2">{{ t('checkIn.title') }}</th>
          <th class="p-2">{{ t('checkIn.flareState') }}</th>
          <th class="p-2">{{ t('checkIn.score') }}</th>
          <th class="p-2"></th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="c in checkIns" :key="c.id" class="border-b">
          <td class="p-2">{{ c.checkInDate }}</td>
          <td class="p-2">{{ t(`checkIn.FlareState.${c.flareState}`) }}</td>
          <td class="p-2">{{ c.totalSymptomScore ?? '—' }}</td>
          <td class="p-2">
            <router-link :to="`/check-ins/${c.checkInDate}`" class="text-blue-600">{{ t('dietLog.open') }}</router-link>
          </td>
        </tr>
      </tbody>
    </table>
  </section>
</template>
```

- [ ] **Step 5: Add routes**

Imports:

```ts
import CheckInEditView from '@/views/CheckInEditView.vue'
import CheckInListView from '@/views/CheckInListView.vue'
```

AppShell `children` additions:

```ts
      { path: 'check-ins', component: CheckInListView, meta: { requiresAuth: true } },
      { path: 'check-ins/:date', component: CheckInEditView, meta: { requiresAuth: true } },
```

- [ ] **Step 6: Run tests, typecheck, build**

Run: `cd frontend && npm run test && npm run typecheck && npm run build`
Expected: all PASS.

- [ ] **Step 7: Commit**

```bash
git add frontend
git commit -m "Add SPA symptom check-in form and history"
```

---

### Task 10: Trends view with charts

**Files:**
- Create: `frontend/src/components/LineChart.vue`
- Create: `frontend/src/views/TrendsView.vue`
- Modify: `frontend/src/router/index.ts` (child route `trends`)
- Test: `frontend/tests/components/LineChart.test.ts`

**Interfaces:**
- Consumes: `symptomApi.dailyTrend(from, to)` (Task 5), `DailyTrendResponse`, `DayTrend`.
- Produces: `LineChart` component with props `{ labels: string[], datasets: { label: string, data: (number | null)[] }[] }` — also used by Task 11 lab trends.

- [ ] **Step 1: Write the failing test `frontend/tests/components/LineChart.test.ts`**

Chart.js needs a canvas 2D context jsdom does not provide; the component must tolerate that in tests. Test the data-mapping prop contract by stubbing vue-chartjs's `Line`:

```ts
import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import LineChart from '@/components/LineChart.vue'

describe('LineChart', () => {
  it('passes labels and datasets to the chart', () => {
    const wrapper = mount(LineChart, {
      props: {
        labels: ['2026-07-01', '2026-07-02'],
        datasets: [{ label: 'Symptom score', data: [3, null] }],
      },
      global: {
        stubs: {
          Line: {
            name: 'Line',
            props: ['data', 'options'],
            template: '<div class="chart-stub" />',
          },
        },
      },
    })
    const line = wrapper.findComponent({ name: 'Line' })
    expect(line.props('data').labels).toEqual(['2026-07-01', '2026-07-02'])
    expect(line.props('data').datasets[0].data).toEqual([3, null])
  })
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && npm run test -- tests/components/LineChart.test.ts`
Expected: FAIL — component does not exist.

- [ ] **Step 3: Implement `frontend/src/components/LineChart.vue`**

```vue
<script setup lang="ts">
import { computed } from 'vue'
import { Line } from 'vue-chartjs'
import {
  CategoryScale,
  Chart as ChartJS,
  Legend,
  LinearScale,
  LineElement,
  PointElement,
  Tooltip,
} from 'chart.js'

ChartJS.register(CategoryScale, LinearScale, PointElement, LineElement, Legend, Tooltip)

const props = defineProps<{
  labels: string[]
  datasets: { label: string; data: (number | null)[] }[]
}>()

const chartData = computed(() => ({
  labels: props.labels,
  datasets: props.datasets.map((d, i) => ({
    label: d.label,
    data: d.data,
    borderColor: ['#2563eb', '#dc2626', '#059669'][i % 3],
    backgroundColor: 'transparent',
    spanGaps: true,
    tension: 0.2,
  })),
}))

const chartOptions = {
  responsive: true,
  maintainAspectRatio: false,
  scales: { y: { beginAtZero: true } },
}
</script>

<template>
  <div class="h-64">
    <Line :data="chartData" :options="chartOptions" />
  </div>
</template>
```

- [ ] **Step 4: Implement `frontend/src/views/TrendsView.vue`**

```vue
<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { symptomApi } from '@/api/symptoms'
import { useApiError } from '@/composables/useApiError'
import LineChart from '@/components/LineChart.vue'
import type { DailyTrendResponse } from '@/types/api'

const { t } = useI18n()
const { message, capture } = useApiError()

function iso(d: Date): string {
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

const today = new Date()
const monthAgo = new Date(today)
monthAgo.setDate(monthAgo.getDate() - 30)

const from = ref(iso(monthAgo))
const to = ref(iso(today))
const trend = ref<DailyTrendResponse | null>(null)
const loading = ref(true)

async function load() {
  loading.value = true
  try {
    trend.value = await symptomApi.dailyTrend(from.value, to.value)
  } catch (e) {
    capture(e)
  } finally {
    loading.value = false
  }
}

onMounted(load)

const labels = computed(() => trend.value?.days.map((d) => d.date) ?? [])

const symptomDataset = computed(() => [
  { label: t('trends.symptomScore'), data: trend.value?.days.map((d) => d.symptomScore) ?? [] },
])

function measurementData(kind: 'glucoseMeasurements' | 'ketoneMeasurements') {
  // Average per day when multiple measurements exist.
  return trend.value?.days.map((d) => {
    const points = d[kind]
    if (points.length === 0) return null
    return points.reduce((sum, p) => sum + p.value, 0) / points.length
  }) ?? []
}
</script>

<template>
  <section>
    <h1 class="text-2xl font-semibold">{{ t('nav.trends') }}</h1>
    <div class="mt-4 flex flex-wrap items-end gap-3">
      <label class="text-sm">{{ t('common.from') }}
        <input v-model="from" type="date" class="ml-1 rounded border border-gray-300 px-2 py-1" />
      </label>
      <label class="text-sm">{{ t('common.to') }}
        <input v-model="to" type="date" class="ml-1 rounded border border-gray-300 px-2 py-1" />
      </label>
      <button class="rounded bg-blue-600 px-3 py-1 text-sm text-white" @click="load">{{ t('common.apply') }}</button>
    </div>

    <p v-if="message" class="mt-4 rounded bg-red-50 p-3 text-sm text-red-700">{{ message }}</p>
    <p v-if="loading" class="mt-4">{{ t('common.loading') }}</p>
    <template v-else-if="trend">
      <div class="mt-6 rounded border bg-white p-4">
        <h2 class="mb-2 font-medium">{{ t('trends.symptomScore') }}</h2>
        <LineChart :labels="labels" :datasets="symptomDataset" />
      </div>
      <div class="mt-6 rounded border bg-white p-4">
        <h2 class="mb-2 font-medium">
          {{ t('trends.glucose') }} ({{ t(`enums.MeasurementUnit.${trend.glucoseUnit}`) }})
        </h2>
        <LineChart :labels="labels" :datasets="[{ label: t('trends.glucose'), data: measurementData('glucoseMeasurements') }]" />
      </div>
      <div class="mt-6 rounded border bg-white p-4">
        <h2 class="mb-2 font-medium">{{ t('trends.ketones') }}</h2>
        <LineChart :labels="labels" :datasets="[{ label: t('trends.ketones'), data: measurementData('ketoneMeasurements') }]" />
      </div>
    </template>
  </section>
</template>
```

Add i18n keys to `en.json`:

```json
  "trends": {
    "symptomScore": "Symptom score",
    "glucose": "Glucose",
    "ketones": "Ketones"
  }
```

and `cs.json`:

```json
  "trends": {
    "symptomScore": "Skóre příznaků",
    "glucose": "Glukóza",
    "ketones": "Ketony"
  }
```

- [ ] **Step 5: Add the route**

Import and AppShell child:

```ts
import TrendsView from '@/views/TrendsView.vue'
```

```ts
      { path: 'trends', component: TrendsView, meta: { requiresAuth: true } },
```

- [ ] **Step 6: Run tests, typecheck, build**

Run: `cd frontend && npm run test && npm run typecheck && npm run build`
Expected: all PASS.

- [ ] **Step 7: Commit**

```bash
git add frontend
git commit -m "Add SPA trends view with charts"
```

---

### Task 11: Labs (result sets, optimistic-lock edit, removal, trends)

**Files:**
- Create: `frontend/src/views/LabResultSetsView.vue`, `frontend/src/views/LabResultSetEditView.vue`, `frontend/src/views/LabTrendsView.vue`
- Modify: `frontend/src/router/index.ts` (child routes `labs`, `labs/new`, `labs/trends`, `labs/:id`)
- Test: `frontend/tests/views/LabResultSetEditView.test.ts`

**Interfaces:**
- Consumes: `labApi` (Task 5), `LineChart` (Task 10), types `LabTestDefinition`, `LabResultSetRequest`, `LabResultSetResponse`.
- Produces: routes `/labs`, `/labs/new`, `/labs/:id`, `/labs/trends`.

Behavior notes (verified against `LabResultController`): create omits `resultSetId`/`version`; update sends both, and the path id must equal `resultSetId` or the backend returns 400. A stale `version` yields 409 `{"error":"conflict"}` → show the conflict error and a reload button. Removal requires the current `version` and an optional reason; only sets with `createdByCurrentPatient === true` are editable/removable by the patient.

- [ ] **Step 1: Write the failing test `frontend/tests/views/LabResultSetEditView.test.ts`**

```ts
import { flushPromises, mount } from '@vue/test-utils'
import { http, HttpResponse } from 'msw'
import { beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { createI18n } from 'vue-i18n'
import { createRouter, createMemoryHistory } from 'vue-router'
import { server } from '../msw/server'
import LabResultSetEditView from '@/views/LabResultSetEditView.vue'
import en from '@/i18n/en.json'

const i18n = createI18n({ legacy: false, locale: 'en', messages: { en } })

const catalog = [
  { code: 'CRP', label: 'C-reactive protein', category: 'INFLAMMATION', canonicalUnit: 'mg/L', displayScale: 1, allowedUnits: ['mg/L'] },
]

const existing = {
  id: 3,
  version: 2,
  patientProfileId: 1,
  collectionDate: '2026-07-10',
  notes: 'note',
  source: 'MANUAL',
  confirmationStatus: 'UNCONFIRMED',
  createdByCurrentPatient: true,
  createdAt: '2026-07-10T08:00:00Z',
  updatedAt: '2026-07-10T08:00:00Z',
  results: [
    { id: 31, testCode: 'CRP', label: 'C-reactive protein', reportedValue: 4.2, reportedUnit: 'mg/L', canonicalValue: 4.2, canonicalUnit: 'mg/L', referenceLower: null, referenceUpper: 5 },
  ],
}

function makeRouter(path: string) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/labs/new', component: LabResultSetEditView },
      { path: '/labs/:id', component: LabResultSetEditView },
    ],
  })
  router.push(path)
  return router
}

describe('LabResultSetEditView', () => {
  beforeEach(() => setActivePinia(createPinia()))

  it('creates a new result set without resultSetId/version', async () => {
    let received: Record<string, unknown> | null = null
    server.use(
      http.get('/api/lab-tests', () => HttpResponse.json(catalog)),
      http.get('/api/csrf', () => HttpResponse.json({ token: 't', headerName: 'X-XSRF-TOKEN' })),
      http.post('/api/lab-result-sets', async ({ request }) => {
        received = (await request.json()) as Record<string, unknown>
        return HttpResponse.json({ ...existing, id: 4 })
      }),
    )
    const router = makeRouter('/labs/new')
    const wrapper = mount(LabResultSetEditView, { global: { plugins: [createPinia(), i18n, router] } })
    await flushPromises()

    await wrapper.find('input[type="date"]').setValue('2026-07-20')
    await wrapper.find('[data-testid="add-result"]').trigger('click')
    await wrapper.find('[data-testid="result-value-0"]').setValue('3.1')
    await wrapper.find('[data-testid="save"]').trigger('click')
    await flushPromises()

    expect(received).not.toBeNull()
    expect(received!.resultSetId ?? null).toBeNull()
    expect(received!.version ?? null).toBeNull()
    expect(received!.collectionDate).toBe('2026-07-20')
    expect((received!.results as unknown[]).length).toBe(1)
  })

  it('shows conflict message and reload button on 409', async () => {
    server.use(
      http.get('/api/lab-tests', () => HttpResponse.json(catalog)),
      http.get('/api/lab-result-sets/3', () => HttpResponse.json(existing)),
      http.get('/api/csrf', () => HttpResponse.json({ token: 't', headerName: 'X-XSRF-TOKEN' })),
      http.put('/api/lab-result-sets/3', () => HttpResponse.json({ error: 'conflict' }, { status: 409 })),
    )
    const router = makeRouter('/labs/3')
    const wrapper = mount(LabResultSetEditView, { global: { plugins: [createPinia(), i18n, router] } })
    await flushPromises()
    expect(wrapper.find('input[type="date"]').element).toHaveProperty('value', '2026-07-10')

    await wrapper.find('[data-testid="save"]').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain(en.errors.conflict)
    expect(wrapper.find('[data-testid="reload"]').exists()).toBe(true)
  })
})
```

Add i18n keys to `en.json`:

```json
  "labs": {
    "title": "Lab results",
    "newResultSet": "New result set",
    "collectionDate": "Collection date",
    "notes": "Notes",
    "results": "Results",
    "addResult": "Add result",
    "test": "Test",
    "value": "Value",
    "unit": "Unit",
    "referenceLower": "Ref. lower",
    "referenceUpper": "Ref. upper",
    "status": "Status",
    "confirmed": "Confirmed",
    "unconfirmed": "Unconfirmed",
    "source": "Source",
    "edit": "Edit",
    "requestRemoval": "Request removal",
    "removalReason": "Reason (optional)",
    "removalRequested": "Removal requested.",
    "reload": "Reload",
    "trendTitle": "Lab trends",
    "selectTest": "Select test"
  }
```

and `cs.json`:

```json
  "labs": {
    "title": "Laboratorní výsledky",
    "newResultSet": "Nová sada výsledků",
    "collectionDate": "Datum odběru",
    "notes": "Poznámky",
    "results": "Výsledky",
    "addResult": "Přidat výsledek",
    "test": "Test",
    "value": "Hodnota",
    "unit": "Jednotka",
    "referenceLower": "Ref. dolní",
    "referenceUpper": "Ref. horní",
    "status": "Stav",
    "confirmed": "Potvrzeno",
    "unconfirmed": "Nepotvrzeno",
    "source": "Zdroj",
    "edit": "Upravit",
    "requestRemoval": "Požádat o odstranění",
    "removalReason": "Důvod (nepovinný)",
    "removalRequested": "Odstranění požadováno.",
    "reload": "Načíst znovu",
    "trendTitle": "Trendy laboratoře",
    "selectTest": "Vyberte test"
  }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && npm run test -- tests/views/LabResultSetEditView.test.ts`
Expected: FAIL — view does not exist.

- [ ] **Step 3: Implement `frontend/src/views/LabResultSetEditView.vue`**

```vue
<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { ApiError } from '@/api/http'
import { labApi } from '@/api/labs'
import { useApiError } from '@/composables/useApiError'
import FieldError from '@/components/FieldError.vue'
import type { LabResultRequest, LabResultSetResponse, LabTestDefinition } from '@/types/api'

const { t } = useI18n()
const route = useRoute()
const { message, fieldErrors, capture, clear } = useApiError()

const id = computed(() => (route.params.id ? Number(route.params.id) : null))
const isNew = computed(() => id.value === null)

const tests = ref<LabTestDefinition[]>([])
const collectionDate = ref('')
const notes = ref('')
const version = ref<number | null>(null)
const results = reactive<LabResultRequest[]>([])
const loading = ref(true)
const saved = ref(false)
const conflict = ref(false)

function newResult(): LabResultRequest {
  const first = tests.value[0]
  return { testCode: first?.code ?? '', value: 0, unit: first?.canonicalUnit ?? '', referenceLower: null, referenceUpper: null }
}

async function loadExisting() {
  if (id.value === null) {
    collectionDate.value = new Date().toISOString().slice(0, 10)
    return
  }
  const set: LabResultSetResponse = await labApi.getResultSet(id.value)
  collectionDate.value = set.collectionDate
  notes.value = set.notes ?? ''
  version.value = set.version
  results.splice(0, results.length, ...set.results.map((r) => ({
    testCode: r.testCode,
    value: r.reportedValue,
    unit: r.reportedUnit,
    referenceLower: r.referenceLower,
    referenceUpper: r.referenceUpper,
  })))
}

async function reload() {
  conflict.value = false
  clear()
  loading.value = true
  try {
    await loadExisting()
  } finally {
    loading.value = false
  }
}

onMounted(async () => {
  tests.value = await labApi.listTests()
  await loadExisting()
  loading.value = false
})

function onTestChange(result: LabResultRequest) {
  const def = tests.value.find((d) => d.code === result.testCode)
  if (def) result.unit = def.canonicalUnit
}

async function save() {
  clear()
  saved.value = false
  conflict.value = false
  try {
    const payload = {
      resultSetId: isNew.value ? null : id.value,
      version: isNew.value ? null : version.value,
      collectionDate: collectionDate.value,
      notes: notes.value || undefined,
      results,
    }
    if (isNew.value) {
      await labApi.createResultSet(payload)
    } else {
      await labApi.updateResultSet(id.value!, payload)
    }
    saved.value = true
  } catch (e) {
    if (e instanceof ApiError && e.status === 409) {
      conflict.value = true
      message.value = t('errors.conflict')
      return
    }
    capture(e)
  }
}
</script>

<template>
  <section class="max-w-3xl">
    <h1 class="text-2xl font-semibold">{{ isNew ? t('labs.newResultSet') : t('labs.edit') }}</h1>
    <p v-if="loading" class="mt-4">{{ t('common.loading') }}</p>
    <div v-else class="mt-4 space-y-4">
      <p v-if="message" class="rounded bg-red-50 p-3 text-sm text-red-700">{{ message }}</p>
      <p v-if="saved" class="rounded bg-green-50 p-3 text-sm text-green-700">{{ t('common.saved') }}</p>
      <button v-if="conflict" data-testid="reload" class="rounded border px-3 py-1 text-sm" @click="reload">
        {{ t('labs.reload') }}
      </button>

      <div class="grid gap-4 rounded border bg-white p-4 sm:grid-cols-2">
        <div>
          <label class="block text-sm font-medium" for="colDate">{{ t('labs.collectionDate') }}</label>
          <input id="colDate" v-model="collectionDate" type="date" required
                 class="mt-1 w-full rounded border border-gray-300 px-3 py-2" />
          <FieldError :message="fieldErrors.collectionDate" />
        </div>
        <div>
          <label class="block text-sm font-medium" for="notes">{{ t('labs.notes') }}</label>
          <input id="notes" v-model="notes" type="text" maxlength="2000"
                 class="mt-1 w-full rounded border border-gray-300 px-3 py-2" />
        </div>
      </div>

      <div class="rounded border bg-white p-4">
        <div class="flex items-center justify-between">
          <h2 class="font-medium">{{ t('labs.results') }}</h2>
          <button data-testid="add-result" class="rounded border px-3 py-1 text-sm"
                  @click="results.push(newResult())">{{ t('labs.addResult') }}</button>
        </div>
        <div v-for="(r, i) in results" :key="i" class="mt-3 grid gap-2 border-t pt-3 sm:grid-cols-[1fr_6rem_6rem_6rem_6rem_auto]">
          <select v-model="r.testCode" class="rounded border border-gray-300 px-2 py-1" @change="onTestChange(r)">
            <option v-for="def in tests" :key="def.code" :value="def.code">{{ def.label }}</option>
          </select>
          <input v-model.number="r.value" :data-testid="`result-value-${i}`" type="number" step="any" min="0"
                 class="rounded border border-gray-300 px-2 py-1" />
          <select v-model="r.unit" class="rounded border border-gray-300 px-2 py-1">
            <option v-for="u in tests.find((d) => d.code === r.testCode)?.allowedUnits ?? [r.unit]" :key="u" :value="u">{{ u }}</option>
          </select>
          <input v-model.number="r.referenceLower" type="number" step="any" min="0" :placeholder="t('labs.referenceLower')"
                 class="rounded border border-gray-300 px-2 py-1" />
          <input v-model.number="r.referenceUpper" type="number" step="any" min="0" :placeholder="t('labs.referenceUpper')"
                 class="rounded border border-gray-300 px-2 py-1" />
          <button class="text-sm text-red-600" @click="results.splice(i, 1)">{{ t('common.remove') }}</button>
        </div>
      </div>

      <button data-testid="save" class="rounded bg-blue-600 px-6 py-2 text-white" @click="save">{{ t('common.save') }}</button>
    </div>
  </section>
</template>
```

- [ ] **Step 4: Run the edit-view tests**

Run: `cd frontend && npm run test -- tests/views/LabResultSetEditView.test.ts`
Expected: 2 tests PASS.

- [ ] **Step 5: Implement `frontend/src/views/LabResultSetsView.vue`**

```vue
<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { labApi } from '@/api/labs'
import { useApiError } from '@/composables/useApiError'
import type { LabResultSetResponse } from '@/types/api'

const { t } = useI18n()
const { message, capture } = useApiError()

function iso(d: Date): string {
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

const today = new Date()
const yearAgo = new Date(today)
yearAgo.setFullYear(yearAgo.getFullYear() - 1)

const from = ref(iso(yearAgo))
const to = ref(iso(today))
const sets = ref<LabResultSetResponse[]>([])
const loading = ref(true)
const removalTarget = ref<LabResultSetResponse | null>(null)
const removalReason = ref('')
const removalDone = ref(false)

async function load() {
  loading.value = true
  try {
    sets.value = await labApi.listResultSets(from.value, to.value)
  } catch (e) {
    capture(e)
  } finally {
    loading.value = false
  }
}

onMounted(load)

async function confirmRemoval() {
  const target = removalTarget.value
  if (!target) return
  try {
    await labApi.requestRemoval(target.id, target.version, removalReason.value)
    removalTarget.value = null
    removalReason.value = ''
    removalDone.value = true
    await load()
  } catch (e) {
    capture(e)
  }
}
</script>

<template>
  <section>
    <div class="flex items-center justify-between">
      <h1 class="text-2xl font-semibold">{{ t('labs.title') }}</h1>
      <div class="flex gap-3 text-sm">
        <router-link to="/labs/trends" class="text-blue-600">{{ t('labs.trendTitle') }}</router-link>
        <router-link to="/labs/new" class="rounded bg-blue-600 px-3 py-1 text-white">{{ t('labs.newResultSet') }}</router-link>
      </div>
    </div>

    <div class="mt-4 flex flex-wrap items-end gap-3">
      <label class="text-sm">{{ t('common.from') }}
        <input v-model="from" type="date" class="ml-1 rounded border border-gray-300 px-2 py-1" />
      </label>
      <label class="text-sm">{{ t('common.to') }}
        <input v-model="to" type="date" class="ml-1 rounded border border-gray-300 px-2 py-1" />
      </label>
      <button class="rounded bg-blue-600 px-3 py-1 text-sm text-white" @click="load">{{ t('common.apply') }}</button>
    </div>

    <p v-if="message" class="mt-4 rounded bg-red-50 p-3 text-sm text-red-700">{{ message }}</p>
    <p v-if="removalDone" class="mt-4 rounded bg-green-50 p-3 text-sm text-green-700">{{ t('labs.removalRequested') }}</p>
    <p v-if="loading" class="mt-4">{{ t('common.loading') }}</p>
    <table v-else class="mt-4 w-full border-collapse bg-white text-sm">
      <thead>
        <tr class="border-b text-left">
          <th class="p-2">{{ t('labs.collectionDate') }}</th>
          <th class="p-2">{{ t('labs.results') }}</th>
          <th class="p-2">{{ t('labs.status') }}</th>
          <th class="p-2"></th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="set in sets" :key="set.id" class="border-b">
          <td class="p-2">{{ set.collectionDate }}</td>
          <td class="p-2">{{ set.results.map((r) => `${r.label}: ${r.reportedValue} ${r.reportedUnit}`).join(', ') }}</td>
          <td class="p-2">{{ set.confirmationStatus === 'CONFIRMED' ? t('labs.confirmed') : t('labs.unconfirmed') }}</td>
          <td class="p-2 text-right">
            <template v-if="set.createdByCurrentPatient">
              <router-link :to="`/labs/${set.id}`" class="mr-3 text-blue-600">{{ t('labs.edit') }}</router-link>
              <button class="text-red-600" @click="removalTarget = set; removalDone = false">{{ t('labs.requestRemoval') }}</button>
            </template>
          </td>
        </tr>
      </tbody>
    </table>

    <div v-if="removalTarget" class="fixed inset-0 flex items-center justify-center bg-black/40">
      <div class="w-full max-w-sm rounded bg-white p-6">
        <h2 class="font-medium">{{ t('labs.requestRemoval') }}</h2>
        <label class="mt-3 block text-sm">{{ t('labs.removalReason') }}
          <input v-model="removalReason" type="text" maxlength="500"
                 class="mt-1 w-full rounded border border-gray-300 px-2 py-1" />
        </label>
        <div class="mt-4 flex justify-end gap-2">
          <button class="rounded border px-3 py-1 text-sm" @click="removalTarget = null">{{ t('common.cancel') }}</button>
          <button class="rounded bg-red-600 px-3 py-1 text-sm text-white" @click="confirmRemoval">{{ t('account.confirm') }}</button>
        </div>
      </div>
    </div>
  </section>
</template>
```

- [ ] **Step 6: Implement `frontend/src/views/LabTrendsView.vue`**

```vue
<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { labApi } from '@/api/labs'
import { useApiError } from '@/composables/useApiError'
import LineChart from '@/components/LineChart.vue'
import type { LabTestDefinition, LabTrendResponse } from '@/types/api'

const { t } = useI18n()
const { message, capture } = useApiError()

function iso(d: Date): string {
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

const today = new Date()
const yearAgo = new Date(today)
yearAgo.setFullYear(yearAgo.getFullYear() - 1)

const tests = ref<LabTestDefinition[]>([])
const selectedTest = ref('')
const from = ref(iso(yearAgo))
const to = ref(iso(today))
const labels = ref<string[]>([])
const values = ref<(number | null)[]>([])
const trend = ref<LabTrendResponse | null>(null)
const loading = ref(false)

onMounted(async () => {
  tests.value = await labApi.listTests()
})

async function load() {
  if (!selectedTest.value) return
  loading.value = true
  try {
    trend.value = await labApi.trend(selectedTest.value, from.value, to.value)
    labels.value = trend.value.points.map((p) => p.collectionDate)
    values.value = trend.value.points.map((p) => p.canonicalValue)
  } catch (e) {
    capture(e)
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <section>
    <h1 class="text-2xl font-semibold">{{ t('labs.trendTitle') }}</h1>
    <div class="mt-4 flex flex-wrap items-end gap-3">
      <label class="text-sm">{{ t('labs.selectTest') }}
        <select v-model="selectedTest" class="ml-1 rounded border border-gray-300 px-2 py-1">
          <option value="" disabled>—</option>
          <option v-for="def in tests" :key="def.code" :value="def.code">{{ def.label }}</option>
        </select>
      </label>
      <label class="text-sm">{{ t('common.from') }}
        <input v-model="from" type="date" class="ml-1 rounded border border-gray-300 px-2 py-1" />
      </label>
      <label class="text-sm">{{ t('common.to') }}
        <input v-model="to" type="date" class="ml-1 rounded border border-gray-300 px-2 py-1" />
      </label>
      <button class="rounded bg-blue-600 px-3 py-1 text-sm text-white" @click="load">{{ t('common.apply') }}</button>
    </div>

    <p v-if="message" class="mt-4 rounded bg-red-50 p-3 text-sm text-red-700">{{ message }}</p>
    <p v-if="loading" class="mt-4">{{ t('common.loading') }}</p>
    <div v-else-if="trend" class="mt-6 rounded border bg-white p-4">
      <h2 class="mb-2 font-medium">{{ trend.label }} ({{ trend.canonicalUnit }})</h2>
      <LineChart :labels="labels" :datasets="[{ label: trend.label, data: values }]" />
    </div>
  </section>
</template>
```

- [ ] **Step 7: Add routes**

Imports:

```ts
import LabResultSetsView from '@/views/LabResultSetsView.vue'
import LabResultSetEditView from '@/views/LabResultSetEditView.vue'
import LabTrendsView from '@/views/LabTrendsView.vue'
```

AppShell `children` additions (order matters — static segments before `:id`):

```ts
      { path: 'labs', component: LabResultSetsView, meta: { requiresAuth: true } },
      { path: 'labs/new', component: LabResultSetEditView, meta: { requiresAuth: true } },
      { path: 'labs/trends', component: LabTrendsView, meta: { requiresAuth: true } },
      { path: 'labs/:id', component: LabResultSetEditView, meta: { requiresAuth: true } },
```

- [ ] **Step 8: Run tests, typecheck, build**

Run: `cd frontend && npm run test && npm run typecheck && npm run build`
Expected: all PASS.

- [ ] **Step 9: Commit**

```bash
git add frontend
git commit -m "Add SPA lab result sets with optimistic-lock handling and trends"
```

---

### Task 12: Onboarding submission form + history

**Files:**
- Create: `frontend/src/views/OnboardingView.vue`
- Modify: `frontend/src/router/index.ts` (child route `onboarding`)

No new test — the form is a mechanical mapping of `OnboardingSubmissionRequest` fields onto inputs; the API boundary (`onboardingApi`) is identical in shape to modules already covered. (Onboarding gets its representative domain coverage indirectly through the shared `http.ts` tests; adding a form-fill test here would duplicate the diet-log editor test's value.)

- [ ] **Step 1: Implement `frontend/src/views/OnboardingView.vue`**

```vue
<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ApiError } from '@/api/http'
import { onboardingApi } from '@/api/onboarding'
import { useApiError } from '@/composables/useApiError'
import FieldError from '@/components/FieldError.vue'
import type {
  AdvancedTherapyExposure,
  DiseaseActivityEstimate,
  IbdDiagnosisType,
  OnboardingSubmissionSummary,
  SteroidUse,
} from '@/types/api'

const { t } = useI18n()
const { message, fieldErrors, capture, clear } = useApiError()

const diagnosisTypes: IbdDiagnosisType[] = ['CROHNS_DISEASE', 'ULCERATIVE_COLITIS', 'IBD_UNCLASSIFIED']
const activityEstimates: DiseaseActivityEstimate[] = ['REMISSION', 'MILD', 'MODERATE', 'SEVERE', 'UNKNOWN']
const steroidUses: SteroidUse[] = ['NONE', 'CURRENT', 'RECENT_LAST_3_MONTHS']
const therapyExposures: AdvancedTherapyExposure[] = ['NEVER_USED', 'CURRENT', 'PAST', 'UNKNOWN']

const form = reactive({
  diagnosisType: 'CROHNS_DISEASE' as IbdDiagnosisType,
  diagnosisYear: null as number | null,
  diseaseLocation: '',
  diseaseBehavior: '',
  activityEstimate: 'UNKNOWN' as DiseaseActivityEstimate,
  currentMedications: '',
  steroidUse: 'NONE' as SteroidUse,
  advancedTherapyExposure: 'UNKNOWN' as AdvancedTherapyExposure,
  medicationNotes: '',
  labsCollectedAt: '',
  crpMgL: null as number | null,
  fecalCalprotectinUgG: null as number | null,
  hemoglobinGDl: null as number | null,
  albuminGDl: null as number | null,
  labNotes: '',
})

const history = ref<OnboardingSubmissionSummary[]>([])
const loading = ref(true)
const saved = ref(false)
const showForm = ref(false)

onMounted(async () => {
  try {
    history.value = await onboardingApi.history()
    showForm.value = history.value.length === 0
  } catch (e) {
    // No submissions yet (404) → show the form.
    if (e instanceof ApiError && e.status === 404) {
      showForm.value = true
    } else {
      capture(e)
    }
  } finally {
    loading.value = false
  }
})

async function submit() {
  clear()
  saved.value = false
  try {
    await onboardingApi.submit({
      diagnosisType: form.diagnosisType,
      diagnosisYear: form.diagnosisYear,
      diseaseLocation: form.diseaseLocation || undefined,
      diseaseBehavior: form.diseaseBehavior || undefined,
      activityEstimate: form.activityEstimate,
      currentMedications: form.currentMedications || undefined,
      steroidUse: form.steroidUse,
      advancedTherapyExposure: form.advancedTherapyExposure,
      medicationNotes: form.medicationNotes || undefined,
      labsCollectedAt: form.labsCollectedAt || null,
      crpMgL: form.crpMgL,
      fecalCalprotectinUgG: form.fecalCalprotectinUgG,
      hemoglobinGDl: form.hemoglobinGDl,
      albuminGDl: form.albuminGDl,
      labNotes: form.labNotes || undefined,
    })
    saved.value = true
    showForm.value = false
    history.value = await onboardingApi.history()
  } catch (e) {
    capture(e)
  }
}
</script>

<template>
  <section class="max-w-2xl">
    <h1 class="text-2xl font-semibold">{{ t('nav.onboarding') }}</h1>
    <p v-if="loading" class="mt-4">{{ t('common.loading') }}</p>
    <template v-else>
      <p v-if="message" class="mt-4 rounded bg-red-50 p-3 text-sm text-red-700">{{ message }}</p>
      <p v-if="saved" class="mt-4 rounded bg-green-50 p-3 text-sm text-green-700">{{ t('common.saved') }}</p>

      <div v-if="history.length > 0" class="mt-4">
        <h2 class="font-medium">{{ t('onboarding.history') }}</h2>
        <ul class="mt-2 space-y-2">
          <li v-for="s in history" :key="s.id" class="rounded border bg-white p-3 text-sm">
            v{{ s.version }} · {{ s.submittedAt.slice(0, 10) }} ·
            {{ t(`onboarding.diagnosis.${s.diagnosisType}`) }} ·
            {{ t(`onboarding.reviewStatus.${s.reviewStatus}`) }}
          </li>
        </ul>
        <button class="mt-3 rounded border px-3 py-1 text-sm" @click="showForm = !showForm">
          {{ showForm ? t('common.cancel') : t('onboarding.newSubmission') }}
        </button>
      </div>

      <form v-if="showForm" class="mt-4 space-y-4 rounded border bg-white p-4" @submit.prevent="submit">
        <div class="grid gap-4 sm:grid-cols-2">
          <div>
            <label class="block text-sm font-medium">{{ t('onboarding.diagnosisType') }}</label>
            <select v-model="form.diagnosisType" class="mt-1 w-full rounded border border-gray-300 px-3 py-2">
              <option v-for="d in diagnosisTypes" :key="d" :value="d">{{ t(`onboarding.diagnosis.${d}`) }}</option>
            </select>
          </div>
          <div>
            <label class="block text-sm font-medium">{{ t('onboarding.diagnosisYear') }}</label>
            <input v-model.number="form.diagnosisYear" type="number" min="1900" :max="new Date().getFullYear()"
                   class="mt-1 w-full rounded border border-gray-300 px-3 py-2" />
            <FieldError :message="fieldErrors.diagnosisYear" />
          </div>
          <div>
            <label class="block text-sm font-medium">{{ t('onboarding.diseaseLocation') }}</label>
            <input v-model="form.diseaseLocation" type="text" maxlength="120"
                   class="mt-1 w-full rounded border border-gray-300 px-3 py-2" />
          </div>
          <div>
            <label class="block text-sm font-medium">{{ t('onboarding.diseaseBehavior') }}</label>
            <input v-model="form.diseaseBehavior" type="text" maxlength="120"
                   class="mt-1 w-full rounded border border-gray-300 px-3 py-2" />
          </div>
          <div>
            <label class="block text-sm font-medium">{{ t('onboarding.activityEstimate') }}</label>
            <select v-model="form.activityEstimate" class="mt-1 w-full rounded border border-gray-300 px-3 py-2">
              <option v-for="a in activityEstimates" :key="a" :value="a">{{ t(`onboarding.activity.${a}`) }}</option>
            </select>
          </div>
          <div>
            <label class="block text-sm font-medium">{{ t('onboarding.steroidUse') }}</label>
            <select v-model="form.steroidUse" class="mt-1 w-full rounded border border-gray-300 px-3 py-2">
              <option v-for="s in steroidUses" :key="s" :value="s">{{ t(`onboarding.steroid.${s}`) }}</option>
            </select>
          </div>
          <div>
            <label class="block text-sm font-medium">{{ t('onboarding.advancedTherapy') }}</label>
            <select v-model="form.advancedTherapyExposure" class="mt-1 w-full rounded border border-gray-300 px-3 py-2">
              <option v-for="x in therapyExposures" :key="x" :value="x">{{ t(`onboarding.therapy.${x}`) }}</option>
            </select>
          </div>
        </div>
        <div>
          <label class="block text-sm font-medium">{{ t('onboarding.currentMedications') }}</label>
          <textarea v-model="form.currentMedications" rows="2" maxlength="1000"
                    class="mt-1 w-full rounded border border-gray-300 px-3 py-2" />
        </div>
        <div>
          <label class="block text-sm font-medium">{{ t('onboarding.medicationNotes') }}</label>
          <textarea v-model="form.medicationNotes" rows="2" maxlength="1000"
                    class="mt-1 w-full rounded border border-gray-300 px-3 py-2" />
        </div>

        <fieldset class="rounded border p-3">
          <legend class="px-1 text-sm font-medium">{{ t('onboarding.labs') }}</legend>
          <div class="grid gap-3 sm:grid-cols-2">
            <label class="text-sm">{{ t('onboarding.labsCollectedAt') }}
              <input v-model="form.labsCollectedAt" type="date" class="mt-1 w-full rounded border border-gray-300 px-2 py-1" />
            </label>
            <label class="text-sm">CRP (mg/L)
              <input v-model.number="form.crpMgL" type="number" step="any" min="0" max="500"
                     class="mt-1 w-full rounded border border-gray-300 px-2 py-1" />
            </label>
            <label class="text-sm">{{ t('onboarding.calprotectin') }} (µg/g)
              <input v-model.number="form.fecalCalprotectinUgG" type="number" step="any" min="0" max="10000"
                     class="mt-1 w-full rounded border border-gray-300 px-2 py-1" />
            </label>
            <label class="text-sm">{{ t('onboarding.hemoglobin') }} (g/dL)
              <input v-model.number="form.hemoglobinGDl" type="number" step="any" min="0" max="25"
                     class="mt-1 w-full rounded border border-gray-300 px-2 py-1" />
            </label>
            <label class="text-sm">{{ t('onboarding.albumin') }} (g/dL)
              <input v-model.number="form.albuminGDl" type="number" step="any" min="0" max="10"
                     class="mt-1 w-full rounded border border-gray-300 px-2 py-1" />
            </label>
          </div>
          <textarea v-model="form.labNotes" rows="2" maxlength="1000" :placeholder="t('labs.notes')"
                    class="mt-3 w-full rounded border border-gray-300 px-3 py-2" />
        </fieldset>

        <button type="submit" class="rounded bg-blue-600 px-6 py-2 text-white">{{ t('common.save') }}</button>
      </form>
    </template>
  </section>
</template>
```

Add i18n keys to `en.json`:

```json
  "onboarding": {
    "history": "Previous submissions",
    "newSubmission": "New submission",
    "diagnosisType": "Diagnosis",
    "diagnosisYear": "Diagnosis year",
    "diseaseLocation": "Disease location",
    "diseaseBehavior": "Disease behavior",
    "activityEstimate": "Disease activity",
    "currentMedications": "Current medications",
    "steroidUse": "Steroid use",
    "advancedTherapy": "Advanced therapy",
    "medicationNotes": "Medication notes",
    "labs": "Recent labs",
    "labsCollectedAt": "Collected at",
    "calprotectin": "Fecal calprotectin",
    "hemoglobin": "Hemoglobin",
    "albumin": "Albumin",
    "diagnosis": { "CROHNS_DISEASE": "Crohn's disease", "ULCERATIVE_COLITIS": "Ulcerative colitis", "IBD_UNCLASSIFIED": "IBD unclassified" },
    "activity": { "REMISSION": "Remission", "MILD": "Mild", "MODERATE": "Moderate", "SEVERE": "Severe", "UNKNOWN": "Unknown" },
    "steroid": { "NONE": "None", "CURRENT": "Current", "RECENT_LAST_3_MONTHS": "In the last 3 months" },
    "therapy": { "NEVER_USED": "Never used", "CURRENT": "Current", "PAST": "Past", "UNKNOWN": "Unknown" },
    "reviewStatus": { "PENDING_REVIEW": "Pending review", "REVIEWED": "Reviewed", "NEEDS_FOLLOW_UP": "Needs follow-up" }
  }
```

and `cs.json`:

```json
  "onboarding": {
    "history": "Předchozí odeslání",
    "newSubmission": "Nové odeslání",
    "diagnosisType": "Diagnóza",
    "diagnosisYear": "Rok diagnózy",
    "diseaseLocation": "Lokalizace onemocnění",
    "diseaseBehavior": "Chování onemocnění",
    "activityEstimate": "Aktivita onemocnění",
    "currentMedications": "Aktuální léčba",
    "steroidUse": "Užívání steroidů",
    "advancedTherapy": "Pokročilá léčba",
    "medicationNotes": "Poznámky k léčbě",
    "labs": "Nedávná laboratorní vyšetření",
    "labsCollectedAt": "Datum odběru",
    "calprotectin": "Fekální kalprotektin",
    "hemoglobin": "Hemoglobin",
    "albumin": "Albumin",
    "diagnosis": { "CROHNS_DISEASE": "Crohnova nemoc", "ULCERATIVE_COLITIS": "Ulcerózní kolitida", "IBD_UNCLASSIFIED": "IBD neklasifikované" },
    "activity": { "REMISSION": "Remise", "MILD": "Mírná", "MODERATE": "Střední", "SEVERE": "Závažná", "UNKNOWN": "Neznámá" },
    "steroid": { "NONE": "Žádné", "CURRENT": "Aktuální", "RECENT_LAST_3_MONTHS": "V posledních 3 měsících" },
    "therapy": { "NEVER_USED": "Nikdy", "CURRENT": "Aktuální", "PAST": "V minulosti", "UNKNOWN": "Neznámé" },
    "reviewStatus": { "PENDING_REVIEW": "Čeká na posouzení", "REVIEWED": "Posouzeno", "NEEDS_FOLLOW_UP": "Vyžaduje doplnění" }
  }
```

- [ ] **Step 2: Add the route**

Import and AppShell child:

```ts
import OnboardingView from '@/views/OnboardingView.vue'
```

```ts
      { path: 'onboarding', component: OnboardingView, meta: { requiresAuth: true } },
```

- [ ] **Step 3: Run tests, typecheck, build**

Run: `cd frontend && npm run test && npm run typecheck && npm run build`
Expected: all PASS.

- [ ] **Step 4: Commit**

```bash
git add frontend
git commit -m "Add SPA onboarding submission form and history"
```

---

### Task 13: Education module list + lesson reader

**Files:**
- Create: `frontend/src/views/EducationListView.vue`, `frontend/src/views/EducationModuleView.vue`
- Modify: `frontend/src/router/index.ts` (child routes `education`, `education/:moduleSlug`)
- Test: `frontend/tests/views/EducationModuleView.test.ts`

**Interfaces:**
- Consumes: `educationApi` (Task 5), `EducationModuleSummary`, `EducationModuleDetail`, `EducationLesson`.
- Produces: routes `/education`, `/education/:moduleSlug`.

Security note: lessons render server-provided `bodyHtml` with `v-html`. The backend produces this HTML from staff-authored, reviewed/published markdown; the authoring pipeline is the trust boundary. Do not inject any patient-provided content into `v-html`.

- [ ] **Step 1: Write the failing test `frontend/tests/views/EducationModuleView.test.ts`**

```ts
import { flushPromises, mount } from '@vue/test-utils'
import { http, HttpResponse } from 'msw'
import { beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { createI18n } from 'vue-i18n'
import { createRouter, createMemoryHistory } from 'vue-router'
import { server } from '../msw/server'
import EducationModuleView from '@/views/EducationModuleView.vue'
import en from '@/i18n/en.json'

const i18n = createI18n({ legacy: false, locale: 'en', messages: { en } })

const moduleDetail = {
  moduleSlug: 'ibd-basics',
  topic: 'IBD',
  sortOrder: 1,
  version: 2,
  requestedLanguage: 'EN',
  contentLanguage: 'EN',
  title: 'IBD Basics',
  summary: 'Intro',
  lessonCount: 1,
  completedLessonCount: 0,
  completed: false,
  publishedAt: '2026-07-01T00:00:00Z',
  lessons: [
    {
      lessonSlug: 'what-is-ibd',
      sortOrder: 1,
      requestedLanguage: 'EN',
      contentLanguage: 'EN',
      title: 'What is IBD?',
      summary: null,
      bodyMarkdown: '# What is IBD?',
      bodyHtml: '<h1>What is IBD?</h1>',
      completed: false,
    },
  ],
}

function makeRouter() {
  return createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/education/:moduleSlug', component: EducationModuleView }],
  })
}

describe('EducationModuleView', () => {
  beforeEach(() => setActivePinia(createPinia()))

  it('renders lesson content and toggles completion', async () => {
    let completed = false
    server.use(
      http.get('/api/education/modules/ibd-basics', () =>
        HttpResponse.json({
          ...moduleDetail,
          completedLessonCount: completed ? 1 : 0,
          lessons: [{ ...moduleDetail.lessons[0], completed }],
        }),
      ),
      http.get('/api/csrf', () => HttpResponse.json({ token: 't', headerName: 'X-XSRF-TOKEN' })),
      http.post('/api/education/modules/ibd-basics/lessons/what-is-ibd/complete', () => {
        completed = true
        return new HttpResponse(null, { status: 200 })
      }),
      http.delete('/api/education/modules/ibd-basics/lessons/what-is-ibd/complete', () => {
        completed = false
        return new HttpResponse(null, { status: 200 })
      }),
    )
    const router = makeRouter()
    await router.push('/education/ibd-basics')
    const wrapper = mount(EducationModuleView, { global: { plugins: [createPinia(), i18n, router] } })
    await flushPromises()

    expect(wrapper.text()).toContain('IBD Basics')
    expect(wrapper.html()).toContain('<h1>What is IBD?</h1>')

    const toggle = wrapper.find('[data-testid="lesson-toggle-what-is-ibd"]')
    expect(toggle.text()).toContain(en.education.markComplete)
    await toggle.trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-testid="lesson-toggle-what-is-ibd"]').text()).toContain(en.education.markIncomplete)
  })
})
```

Add i18n keys to `en.json`:

```json
  "education": {
    "title": "Education",
    "lessons": "Lessons",
    "lessonCount": "{count} lessons",
    "completedCount": "{done} of {count} completed",
    "completedBadge": "Completed",
    "markComplete": "Mark as completed",
    "markIncomplete": "Mark as not completed",
    "backToModules": "All modules"
  }
```

and `cs.json`:

```json
  "education": {
    "title": "Vzdělávání",
    "lessons": "Lekce",
    "lessonCount": "{count} lekcí",
    "completedCount": "{done} z {count} dokončeno",
    "completedBadge": "Dokončeno",
    "markComplete": "Označit jako dokončené",
    "markIncomplete": "Označit jako nedokončené",
    "backToModules": "Všechny moduly"
  }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && npm run test -- tests/views/EducationModuleView.test.ts`
Expected: FAIL — view does not exist.

- [ ] **Step 3: Implement `frontend/src/views/EducationModuleView.vue`**

```vue
<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { educationApi } from '@/api/education'
import { useApiError } from '@/composables/useApiError'
import type { EducationLesson, EducationModuleDetail } from '@/types/api'

const { t } = useI18n()
const route = useRoute()
const { message, capture } = useApiError()

const moduleSlug = route.params.moduleSlug as string
const module = ref<EducationModuleDetail | null>(null)
const loading = ref(true)
const openLesson = ref<string | null>(null)

async function load() {
  module.value = await educationApi.getModule(moduleSlug)
  // Auto-expand the first lesson on initial load (keeps the open lesson stable
  // across post-toggle reloads, since openLesson is only set when null).
  if (openLesson.value === null && module.value.lessons.length > 0) {
    openLesson.value = module.value.lessons[0].lessonSlug
  }
  loading.value = false
}

onMounted(load)

async function toggleLesson(lesson: EducationLesson) {
  try {
    if (lesson.completed) {
      await educationApi.uncompleteLesson(moduleSlug, lesson.lessonSlug)
    } else {
      await educationApi.completeLesson(moduleSlug, lesson.lessonSlug)
    }
    await load()
  } catch (e) {
    capture(e)
  }
}
</script>

<template>
  <section class="max-w-3xl">
    <router-link to="/education" class="text-sm text-blue-600">← {{ t('education.backToModules') }}</router-link>
    <p v-if="loading" class="mt-4">{{ t('common.loading') }}</p>
    <template v-else-if="module">
      <h1 class="mt-2 text-2xl font-semibold">{{ module.title }}</h1>
      <p v-if="module.summary" class="mt-1 text-gray-600">{{ module.summary }}</p>
      <p class="mt-1 text-sm text-gray-500">
        {{ t('education.completedCount', { done: module.completedLessonCount ?? 0, count: module.lessonCount }) }}
      </p>
      <p v-if="message" class="mt-4 rounded bg-red-50 p-3 text-sm text-red-700">{{ message }}</p>

      <h2 class="mt-6 font-medium">{{ t('education.lessons') }}</h2>
      <div class="mt-2 space-y-2">
        <div v-for="lesson in module.lessons" :key="lesson.lessonSlug" class="rounded border bg-white">
          <button class="flex w-full items-center justify-between p-4 text-left"
                  @click="openLesson = openLesson === lesson.lessonSlug ? null : lesson.lessonSlug">
            <span>
              {{ lesson.title }}
              <span v-if="lesson.completed" class="ml-2 rounded bg-green-100 px-2 py-0.5 text-xs text-green-700">
                {{ t('education.completedBadge') }}
              </span>
            </span>
            <span class="text-gray-400">{{ openLesson === lesson.lessonSlug ? '−' : '+' }}</span>
          </button>
          <div v-if="openLesson === lesson.lessonSlug" class="border-t p-4">
            <!-- bodyHtml is server-rendered from staff-authored, reviewed content; safe to render -->
            <div class="prose max-w-none" v-html="lesson.bodyHtml" />
            <button :data-testid="`lesson-toggle-${lesson.lessonSlug}`"
                    class="mt-4 rounded border px-3 py-1 text-sm"
                    @click="toggleLesson(lesson)">
              {{ lesson.completed ? t('education.markIncomplete') : t('education.markComplete') }}
            </button>
          </div>
        </div>
      </div>
    </template>
  </section>
</template>
```

- [ ] **Step 4: Implement `frontend/src/views/EducationListView.vue`**

```vue
<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { educationApi } from '@/api/education'
import { useApiError } from '@/composables/useApiError'
import type { EducationModuleSummary } from '@/types/api'

const { t } = useI18n()
const { message, capture } = useApiError()
const modules = ref<EducationModuleSummary[]>([])
const loading = ref(true)

onMounted(async () => {
  try {
    modules.value = await educationApi.listModules()
  } catch (e) {
    capture(e)
  } finally {
    loading.value = false
  }
})
</script>

<template>
  <section>
    <h1 class="text-2xl font-semibold">{{ t('education.title') }}</h1>
    <p v-if="message" class="mt-4 rounded bg-red-50 p-3 text-sm text-red-700">{{ message }}</p>
    <p v-if="loading" class="mt-4">{{ t('common.loading') }}</p>
    <div v-else class="mt-4 grid gap-4 sm:grid-cols-2">
      <router-link v-for="m in modules" :key="m.moduleSlug" :to="`/education/${m.moduleSlug}`"
                   class="rounded border bg-white p-4 hover:border-blue-400">
        <h2 class="font-medium">{{ m.title }}</h2>
        <p v-if="m.summary" class="mt-1 text-sm text-gray-600">{{ m.summary }}</p>
        <p class="mt-2 text-sm text-gray-500">
          {{ t('education.completedCount', { done: m.completedLessonCount ?? 0, count: m.lessonCount }) }}
          <span v-if="m.completed" class="ml-2 rounded bg-green-100 px-2 py-0.5 text-xs text-green-700">
            {{ t('education.completedBadge') }}
          </span>
        </p>
      </router-link>
    </div>
  </section>
</template>
```

- [ ] **Step 5: Add routes**

Imports:

```ts
import EducationListView from '@/views/EducationListView.vue'
import EducationModuleView from '@/views/EducationModuleView.vue'
```

AppShell `children` additions:

```ts
      { path: 'education', component: EducationListView, meta: { requiresAuth: true } },
      { path: 'education/:moduleSlug', component: EducationModuleView, meta: { requiresAuth: true } },
```

- [ ] **Step 6: Run tests, typecheck, build**

Run: `cd frontend && npm run test && npm run typecheck && npm run build`
Expected: all PASS.

- [ ] **Step 7: Commit**

```bash
git add frontend
git commit -m "Add SPA education modules and lesson reader"
```

---

### Task 14: Final polish — i18n parity, README, full verification

**Files:**
- Modify: `frontend/src/i18n/en.json`, `frontend/src/i18n/cs.json` (parity check)
- Create: `frontend/README.md`
- Modify: `.gitignore` (add `frontend/node_modules/`, `frontend/dist/`)

- [ ] **Step 1: Check i18n key parity**

Run this Node one-liner from `frontend/`:

```bash
node -e "
const en = require('./src/i18n/en.json');
const cs = require('./src/i18n/cs.json');
const flat = (o, p = '') => Object.entries(o).flatMap(([k, v]) =>
  v && typeof v === 'object' ? flat(v, p + k + '.') : [p + k]);
const missing = flat(en).filter(k => !flat(cs).includes(k));
const extra = flat(cs).filter(k => !flat(en).includes(k));
if (missing.length || extra.length) { console.log({ missing, extra }); process.exit(1); }
console.log('i18n parity OK');
"
```

Expected: `i18n parity OK`. Fix any missing keys by translating them before continuing.

- [ ] **Step 2: Add `.gitignore` entries**

Append to the repository-root `.gitignore`:

```text
frontend/node_modules/
frontend/dist/
```

Verify `git status` no longer lists `frontend/node_modules`.

- [ ] **Step 3: Create `frontend/README.md`**

```markdown
# Metabion Patient SPA

Vue 3 + TypeScript patient client for the Metabion REST API. Session-authenticated;
talks to the Spring Boot backend through the Vite dev proxy.

## Prerequisites

- Node 22 (e.g. `brew install node@22`)
- Metabion backend running on `http://localhost:8080` (`./gradlew bootRun` from the repo root)

## Commands

| Command | Description |
|---|---|
| `npm install` | Install dependencies. |
| `npm run dev` | Dev server on http://localhost:5173, proxying `/api` to :8080. |
| `npm run test` | Vitest + MSW unit tests. |
| `npm run typecheck` | `vue-tsc --noEmit`. |
| `npm run build` | Production build into `dist/`. |

## Notes

- Auth is the backend's session cookie (`POST /api/auth/login`); the fetch wrapper in
  `src/api/http.ts` bootstraps the CSRF token lazily from `GET /api/csrf`.
- UI strings live in `src/i18n/en.json` and `src/i18n/cs.json` — keep keys in sync.
- The SPA covers patient flows only. Staff users are redirected to `/staff-notice`,
  which links to the Thymeleaf staff app at `/app`.
```

- [ ] **Step 4: Full verification**

Run from `frontend/`:

```bash
npm run test && npm run typecheck && npm run build
```

Expected: all tests PASS, typecheck clean, `dist/` built.

Then a manual smoke test (requires the backend running):

```bash
cd .. && ./gradlew bootRun   # in another terminal, if not already running
cd frontend && npm run dev
```

Open http://localhost:5173, register/log in with a verified patient account, and confirm: dashboard loads, diet log saves, check-in saves, trends render. Record the outcome in the commit message or PR description.

- [ ] **Step 5: Commit**

```bash
git add frontend .gitignore
git commit -m "Add SPA README, i18n parity check, and gitignore entries"
```

---

## Manual acceptance checklist (after Task 14)

- [ ] `npm run test` — all green
- [ ] `npm run typecheck` — clean
- [ ] `npm run build` — `dist/` produced
- [ ] Dev proxy: login via SPA against `./gradlew bootRun` backend works end to end
- [ ] Locale switch toggles en/cs without reload
- [ ] 401 from expired session redirects to `/login` and back after re-login

## Post-execution amendments (2026-07-25)

Applied during subagent-driven execution; the task texts above predate some of these:

1. **Task 3/4:** `FieldError` takes `message?: string` (the Task 4 "Interfaces" one-liner describing a `field` prop was stale).
2. **Tasks 7+9 (user-approved):** non-404 load failures in both upsert editors show the error and withhold the editor (`loadFailed`), so a blind save cannot wipe server data.
3. **Task 11:** test helper awaits `router.push` (vue-router 4.6 cancels un-awaited pushes).
4. **Task 13:** the module view auto-expands the first lesson on load (resolved a test/template inconsistency; adjudicated by the reviewer).
5. **Final-review fix (commit e4f62fa) — mealIndex contract:** the backend (`DietLogRequestMapper`, `DietLogPhotoService`) requires a non-null, in-range `mealIndex` for every deviation and photo reference; the DTO records' nullable `Integer` was misleading. The shipped `DietLogEditView` therefore has meal selectors on deviation rows and photo figures (default last meal), gates add-deviation/photo-upload on meals existing, maps `mealId → index` on load (fallback 0), and cascades/re-indexes dependents on meal removal. The Task 7 code above (with `mealIndex: null`) is superseded.
6. **Final-review fix (same commit):** `numOrNull` coercion for cleared numeric inputs (Onboarding/CheckIn/Lab forms), lab `version` threaded from update response, `router.replace('/labs/:id')` after create, `clear()` on list-view reloads, guarded datetime-local handler, try/catch+capture on initial loads, `exact-active-class` on the AppShell `/` link.
