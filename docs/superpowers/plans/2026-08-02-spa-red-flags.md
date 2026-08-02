# SPA Red-Flag Display Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Surface patient red flags in the Vue SPA: an app-wide urgent/emergency banner, a dashboard banner for all severities, and a `/red-flags` view with the current snapshot and cursor-paginated history.

**Architecture:** A Pinia `redFlags` store caches the `GET /api/red-flags/current` snapshot (last-known state, refreshed on shell/view mount and after check-in/lab saves). A reusable `RedFlagBanner` component renders a severity-colored strip filtered by a `severities` prop. `RedFlagsView` consumes the store for current flags and fetches `GET /api/red-flags/history` with view-local cursor pagination. No backend changes.

**Tech Stack:** Vue 3 `<script setup lang="ts">`, TypeScript, Pinia, vue-router, vue-i18n, Tailwind v4 utility classes, Vitest + @vue/test-utils + MSW.

**Spec:** `docs/superpowers/specs/2026-08-02-spa-red-flags-design.md` (authoritative; this plan implements it).

## Global Constraints

- All work is in `frontend/`; commands run from `frontend/` unless noted.
- No backend (`src/`) changes; `./gradlew test` is unaffected.
- No safety/medical guidance text anywhere — only neutral labels.
- Superseded history entries are labeled "superseded", never "resolved".
- No polling, no focus listeners, no acknowledgement/dismiss state.
- `en.json` and `cs.json` keys must stay aligned.
- Follow the copy-paste Tailwind idiom; no new shared CSS, no scoped `<style>` blocks.
- GET endpoints need no CSRF handling; `apiFetch` handles session auth and 401.
- Verification per task: `npm run test` and `npm run typecheck` from `frontend/`.

---

### Task 1: Red-flag types and API module

**Files:**
- Modify: `frontend/src/types/api.ts` (append after the last interface)
- Create: `frontend/src/api/redFlags.ts`
- Test: `frontend/tests/api/redFlags.test.ts`

**Interfaces:**
- Consumes: `apiFetch` from `frontend/src/api/http.ts` (`apiFetch<T>(path: string, options?): Promise<T>`).
- Produces:
  - `type RedFlagSeverity = 'ROUTINE_REVIEW' | 'URGENT_REVIEW' | 'EMERGENCY'`
  - `type RedFlagSourceType = 'SYMPTOM_CHECK_IN' | 'LAB_RESULT_SET'`
  - `interface PatientRedFlagEvent`, `interface PatientRedFlagSnapshot`, `interface PatientRedFlagHistoryPage`, `interface RedFlagHistoryParams`
  - `redFlagApi.getCurrent(): Promise<PatientRedFlagSnapshot>`
  - `redFlagApi.getHistory(params: RedFlagHistoryParams): Promise<PatientRedFlagHistoryPage>`

- [ ] **Step 1: Write the failing test**

Create `frontend/tests/api/redFlags.test.ts`:

```ts
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import { server } from '../msw/server'
import { redFlagApi } from '@/api/redFlags'

describe('redFlagApi', () => {
  it('getCurrent requests the current snapshot', async () => {
    server.use(
      http.get('/api/red-flags/current', () =>
        HttpResponse.json({ highestSeverity: null, flags: [] }),
      ),
    )
    const snapshot = await redFlagApi.getCurrent()
    expect(snapshot).toEqual({ highestSeverity: null, flags: [] })
  })

  it('getHistory appends only the provided parameters', async () => {
    let capturedUrl = ''
    server.use(
      http.get('/api/red-flags/history', ({ request }) => {
        capturedUrl = request.url
        return HttpResponse.json({ items: [], nextCursor: null })
      }),
    )
    await redFlagApi.getHistory({ from: '2026-07-01', to: '2026-07-31', severity: 'EMERGENCY', size: 50 })
    const url = new URL(capturedUrl)
    expect(url.searchParams.get('from')).toBe('2026-07-01')
    expect(url.searchParams.get('to')).toBe('2026-07-31')
    expect(url.searchParams.get('severity')).toBe('EMERGENCY')
    expect(url.searchParams.get('size')).toBe('50')
    expect(url.searchParams.has('cursor')).toBe(false)
  })

  it('getHistory forwards the cursor and sends no query string without params', async () => {
    const captured: string[] = []
    server.use(
      http.get('/api/red-flags/history', ({ request }) => {
        captured.push(request.url)
        return HttpResponse.json({ items: [], nextCursor: null })
      }),
    )
    await redFlagApi.getHistory({})
    await redFlagApi.getHistory({ cursor: 'opaque-cursor-1' })
    expect(new URL(captured[0]).search).toBe('')
    expect(new URL(captured[1]).searchParams.get('cursor')).toBe('opaque-cursor-1')
  })
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && npm run test -- tests/api/redFlags.test.ts`
Expected: FAIL — cannot resolve `@/api/redFlags`.

- [ ] **Step 3: Add the types**

Append to `frontend/src/types/api.ts` (after the last existing interface):

```ts
// Red flags (mirror src/main/java/com/metabion/dto/redflag/)
export type RedFlagSeverity = 'ROUTINE_REVIEW' | 'URGENT_REVIEW' | 'EMERGENCY'
export type RedFlagSourceType = 'SYMPTOM_CHECK_IN' | 'LAB_RESULT_SET'

export interface PatientRedFlagEvent {
  eventId: number
  ruleKey: string
  severity: RedFlagSeverity
  detectedAt: string // ISO instant
  sourceType: RedFlagSourceType
  sourceId: number
  current: boolean
  supersededAt: string | null // ISO instant
}

export interface PatientRedFlagSnapshot {
  highestSeverity: RedFlagSeverity | null
  flags: PatientRedFlagEvent[]
}

export interface PatientRedFlagHistoryPage {
  items: PatientRedFlagEvent[]
  nextCursor: string | null
}

export interface RedFlagHistoryParams {
  from?: string // yyyy-MM-dd, inclusive
  to?: string // yyyy-MM-dd, inclusive
  severity?: RedFlagSeverity
  cursor?: string
  size?: number
}
```

Do not add rule versions, matched-input facts, evaluation-run IDs, source operations, or matched-group keys — the patient projection never exposes them.

- [ ] **Step 4: Write the API module**

Create `frontend/src/api/redFlags.ts`:

```ts
import { apiFetch } from './http'
import type {
  PatientRedFlagHistoryPage,
  PatientRedFlagSnapshot,
  RedFlagHistoryParams,
} from '@/types/api'

export const redFlagApi = {
  getCurrent: () => apiFetch<PatientRedFlagSnapshot>('/api/red-flags/current'),
  getHistory: (params: RedFlagHistoryParams) => {
    const query = new URLSearchParams()
    if (params.from) query.set('from', params.from)
    if (params.to) query.set('to', params.to)
    if (params.severity) query.set('severity', params.severity)
    if (params.cursor) query.set('cursor', params.cursor)
    if (params.size) query.set('size', String(params.size))
    const qs = query.toString()
    return apiFetch<PatientRedFlagHistoryPage>(`/api/red-flags/history${qs ? `?${qs}` : ''}`)
  },
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `cd frontend && npm run test -- tests/api/redFlags.test.ts`
Expected: PASS (3 tests).

- [ ] **Step 6: Commit**

```bash
git add frontend/src/types/api.ts frontend/src/api/redFlags.ts frontend/tests/api/redFlags.test.ts
git commit -m "Add red-flag API types and client to the SPA"
```

---

### Task 2: Date-time and severity-class utilities

**Files:**
- Create: `frontend/src/utils/dateTime.ts`
- Create: `frontend/src/utils/redFlags.ts`
- Test: `frontend/tests/utils/dateTime.test.ts`
- Test: `frontend/tests/utils/redFlags.test.ts`

**Interfaces:**
- Consumes: `RedFlagSeverity` from Task 1.
- Produces:
  - `formatDateTime(iso: string, locale: string): string`
  - `severityBadgeClass(severity: RedFlagSeverity): string`

- [ ] **Step 1: Write the failing tests**

Create `frontend/tests/utils/dateTime.test.ts`:

```ts
import { describe, expect, it } from 'vitest'
import { formatDateTime } from '@/utils/dateTime'

describe('formatDateTime', () => {
  it('formats an ISO instant for the given locale', () => {
    const result = formatDateTime('2026-08-01T10:15:30Z', 'en')
    expect(result).toContain('2026')
    expect(result).not.toBe('2026-08-01T10:15:30Z')
  })
})
```

Create `frontend/tests/utils/redFlags.test.ts`:

```ts
import { describe, expect, it } from 'vitest'
import { severityBadgeClass } from '@/utils/redFlags'

describe('severityBadgeClass', () => {
  it('maps each severity to a distinct severity-colored class set', () => {
    expect(severityBadgeClass('EMERGENCY')).toContain('red')
    expect(severityBadgeClass('URGENT_REVIEW')).toContain('amber')
    expect(severityBadgeClass('ROUTINE_REVIEW')).toContain('blue')
  })
})
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd frontend && npm run test -- tests/utils/dateTime.test.ts tests/utils/redFlags.test.ts`
Expected: FAIL — modules do not exist.

- [ ] **Step 3: Implement the utilities**

Create `frontend/src/utils/dateTime.ts`:

```ts
/** Formats an ISO instant for display in the active UI locale. */
export function formatDateTime(iso: string, locale: string): string {
  return new Date(iso).toLocaleString(locale)
}
```

Create `frontend/src/utils/redFlags.ts`:

```ts
import type { RedFlagSeverity } from '@/types/api'

const BADGE_CLASSES: Record<RedFlagSeverity, string> = {
  EMERGENCY: 'bg-red-100 text-red-800 dark:bg-red-950 dark:text-red-200',
  URGENT_REVIEW: 'bg-amber-100 text-amber-800 dark:bg-amber-950 dark:text-amber-200',
  ROUTINE_REVIEW: 'bg-blue-100 text-blue-800 dark:bg-blue-950 dark:text-blue-200',
}

/** Tailwind class set for a severity-colored strip or badge. */
export function severityBadgeClass(severity: RedFlagSeverity): string {
  return BADGE_CLASSES[severity]
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd frontend && npm run test -- tests/utils/dateTime.test.ts tests/utils/redFlags.test.ts`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add frontend/src/utils/dateTime.ts frontend/src/utils/redFlags.ts frontend/tests/utils/dateTime.test.ts frontend/tests/utils/redFlags.test.ts
git commit -m "Add date-time and red-flag severity utilities"
```

---

### Task 3: Red-flags Pinia store with logout wiring

**Files:**
- Create: `frontend/src/stores/redFlags.ts`
- Modify: `frontend/src/stores/auth.ts` (import + one line in `expire()`)
- Test: `frontend/tests/stores/redFlags.test.ts`

**Interfaces:**
- Consumes: `redFlagApi.getCurrent()` from Task 1.
- Produces:
  - `useRedFlagsStore()` with state `snapshot: PatientRedFlagSnapshot | null`, `loading: boolean`, `loadFailed: boolean`
  - `refreshCurrent(): Promise<void>` — never throws; concurrent calls collapse
  - `clear(): void`

- [ ] **Step 1: Write the failing test**

Create `frontend/tests/stores/redFlags.test.ts`:

```ts
import { http, HttpResponse } from 'msw'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { server } from '../msw/server'
import { useRedFlagsStore } from '@/stores/redFlags'

const snapshot = {
  highestSeverity: 'URGENT_REVIEW',
  flags: [
    {
      eventId: 701,
      ruleKey: 'LAB_CRP_HIGH',
      severity: 'URGENT_REVIEW',
      detectedAt: '2026-08-01T10:15:30Z',
      sourceType: 'LAB_RESULT_SET',
      sourceId: 91,
      current: true,
      supersededAt: null,
    },
  ],
}

describe('redFlags store', () => {
  beforeEach(() => setActivePinia(createPinia()))

  it('refreshCurrent stores the snapshot', async () => {
    server.use(http.get('/api/red-flags/current', () => HttpResponse.json(snapshot)))
    const store = useRedFlagsStore()
    await store.refreshCurrent()
    expect(store.snapshot).toEqual(snapshot)
    expect(store.loadFailed).toBe(false)
  })

  it('refreshCurrent sets loadFailed instead of throwing', async () => {
    server.use(http.get('/api/red-flags/current', () => new HttpResponse(null, { status: 500 })))
    const store = useRedFlagsStore()
    await store.refreshCurrent()
    expect(store.loadFailed).toBe(true)
    expect(store.snapshot).toBeNull()
  })

  it('skips a second refresh while one is in flight', async () => {
    let calls = 0
    let release!: () => void
    server.use(
      http.get('/api/red-flags/current', async () => {
        calls += 1
        await new Promise<void>((resolve) => { release = resolve })
        return HttpResponse.json(snapshot)
      }),
    )
    const store = useRedFlagsStore()
    const first = store.refreshCurrent()
    const second = store.refreshCurrent()
    // Wait until the MSW handler has actually started before releasing it.
    await vi.waitFor(() => expect(calls).toBe(1))
    release()
    await Promise.all([first, second])
    expect(calls).toBe(1)
  })

  it('discards a refresh result when clear() runs while it is in flight', async () => {
    let calls = 0
    let release!: () => void
    server.use(
      http.get('/api/red-flags/current', async () => {
        calls += 1
        await new Promise<void>((resolve) => { release = resolve })
        return HttpResponse.json(snapshot)
      }),
    )
    const store = useRedFlagsStore()
    const pending = store.refreshCurrent()
    // Wait until the MSW handler has actually started before clearing/releasing.
    await vi.waitFor(() => expect(calls).toBe(1))
    store.clear()
    release()
    await pending
    expect(store.snapshot).toBeNull()
    expect(store.loading).toBe(false)
    expect(store.loadFailed).toBe(false)
  })

  it('clear resets all state', async () => {
    server.use(http.get('/api/red-flags/current', () => HttpResponse.json(snapshot)))
    const store = useRedFlagsStore()
    await store.refreshCurrent()
    store.clear()
    expect(store.snapshot).toBeNull()
    expect(store.loadFailed).toBe(false)
    expect(store.loading).toBe(false)
  })
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && npm run test -- tests/stores/redFlags.test.ts`
Expected: FAIL — cannot resolve `@/stores/redFlags`.

- [ ] **Step 3: Implement the store**

Create `frontend/src/stores/redFlags.ts`:

```ts
import { ref } from 'vue'
import { defineStore } from 'pinia'
import { redFlagApi } from '@/api/redFlags'
import type { PatientRedFlagSnapshot } from '@/types/api'

export const useRedFlagsStore = defineStore('redFlags', () => {
  const snapshot = ref<PatientRedFlagSnapshot | null>(null)
  const loading = ref(false)
  const loadFailed = ref(false)
  let generation = 0

  /**
   * Refreshes the current snapshot. A failure only hides the banner via
   * loadFailed — it never throws, so save flows are unaffected. A result
   * that was in flight when clear() ran is discarded, so a previous
   * patient's flags cannot reappear after logout.
   */
  async function refreshCurrent(): Promise<void> {
    if (loading.value) return
    loading.value = true
    const gen = generation
    try {
      const result = await redFlagApi.getCurrent()
      if (gen !== generation) return // a clear() happened while in flight; discard
      snapshot.value = result
      loadFailed.value = false
    } catch {
      loadFailed.value = true
    } finally {
      loading.value = false
    }
  }

  function clear(): void {
    generation += 1
    snapshot.value = null
    loading.value = false
    loadFailed.value = false
  }

  return { snapshot, loading, loadFailed, refreshCurrent, clear }
})
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd frontend && npm run test -- tests/stores/redFlags.test.ts`
Expected: PASS (5 tests).

- [ ] **Step 5: Clear the store on logout/session expiry**

In `frontend/src/stores/auth.ts`, add the import at the top:

```ts
import { useRedFlagsStore } from '@/stores/redFlags'
```

and inside `expire()`, as the first statement after `resetCsrfToken()`:

```ts
  /** Local-only reset, e.g. when a request fails with 401 mid-session. */
  function expire(): void {
    resetCsrfToken()
    useRedFlagsStore().clear()
    email.value = null
    roles.value = []
    status.value = 'anonymous'
    mfaRequired.value = false
  }
```

This covers both explicit logout and the mid-session 401 handler in `main.ts`, since both go through `expire()`. There is no circular import: `stores/redFlags.ts` does not import `stores/auth.ts`.

- [ ] **Step 6: Run the store test suites and typecheck**

Run: `cd frontend && npm run test -- tests/stores/ && npm run typecheck`
Expected: PASS (existing auth store tests still green, new red-flags tests green; typecheck clean).

- [ ] **Step 7: Commit**

```bash
git add frontend/src/stores/redFlags.ts frontend/src/stores/auth.ts frontend/tests/stores/redFlags.test.ts
git commit -m "Add red-flags store cleared on session expiry"
```

---

### Task 4: RedFlagBanner component and red-flag i18n keys

**Files:**
- Create: `frontend/src/components/RedFlagBanner.vue`
- Modify: `frontend/src/i18n/en.json` (add `redFlags` block after the `education` block)
- Modify: `frontend/src/i18n/cs.json` (same position)
- Test: `frontend/tests/components/RedFlagBanner.test.ts`

**Interfaces:**
- Consumes: `useRedFlagsStore()` (Task 3), `severityBadgeClass` (Task 2), `RedFlagSeverity` (Task 1).
- Produces: `<RedFlagBanner :severities="RedFlagSeverity[]" />` — renders a strip only when the snapshot's `highestSeverity` is in `severities` and `loadFailed` is false. Used by Tasks 5 and 6.
- Produces i18n keys: `redFlags.severity.*`, `redFlags.bannerCount`, `redFlags.viewDetails`, plus the full `redFlags.*` block consumed by Task 7 (all keys are added here in one pass).

- [ ] **Step 1: Write the failing test**

Create `frontend/tests/components/RedFlagBanner.test.ts`:

```ts
import { mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it } from 'vitest'
import { createPinia, getActivePinia, setActivePinia } from 'pinia'
import { createI18n } from 'vue-i18n'
import { createRouter, createMemoryHistory } from 'vue-router'
import RedFlagBanner from '@/components/RedFlagBanner.vue'
import { useRedFlagsStore } from '@/stores/redFlags'
import type { PatientRedFlagSnapshot, RedFlagSeverity } from '@/types/api'
import en from '@/i18n/en.json'

const i18n = createI18n({ legacy: false, locale: 'en', messages: { en } })

function makeRouter() {
  return createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/red-flags', component: { template: '<div />' } }],
  })
}

const urgentSnapshot: PatientRedFlagSnapshot = {
  highestSeverity: 'URGENT_REVIEW',
  flags: [
    {
      eventId: 701,
      ruleKey: 'LAB_CRP_HIGH',
      severity: 'URGENT_REVIEW',
      detectedAt: '2026-08-01T10:15:30Z',
      sourceType: 'LAB_RESULT_SET',
      sourceId: 91,
      current: true,
      supersededAt: null,
    },
  ],
}

function mountBanner(severities: RedFlagSeverity[]) {
  // Reuse the active pinia: tests seed the store via setActivePinia(), and a
  // second createPinia() plugin would give the component a different instance.
  return mount(RedFlagBanner, {
    props: { severities },
    global: { plugins: [getActivePinia()!, i18n, makeRouter()] },
  })
}

describe('RedFlagBanner', () => {
  beforeEach(() => setActivePinia(createPinia()))

  it('renders nothing without a snapshot', () => {
    const wrapper = mountBanner(['ROUTINE_REVIEW', 'URGENT_REVIEW', 'EMERGENCY'])
    expect(wrapper.find('[data-testid="red-flag-banner"]').exists()).toBe(false)
  })

  it('renders nothing when the last load failed', () => {
    const store = useRedFlagsStore()
    store.snapshot = urgentSnapshot
    store.loadFailed = true
    const wrapper = mountBanner(['URGENT_REVIEW'])
    expect(wrapper.find('[data-testid="red-flag-banner"]').exists()).toBe(false)
  })

  it('renders nothing when the highest severity is excluded by the prop', () => {
    const store = useRedFlagsStore()
    store.snapshot = urgentSnapshot
    const wrapper = mountBanner(['EMERGENCY'])
    expect(wrapper.find('[data-testid="red-flag-banner"]').exists()).toBe(false)
  })

  it('shows the severity label, flag count, and detail link when visible', () => {
    const store = useRedFlagsStore()
    store.snapshot = urgentSnapshot
    const wrapper = mountBanner(['URGENT_REVIEW', 'EMERGENCY'])
    const banner = wrapper.find('[data-testid="red-flag-banner"]')
    expect(banner.exists()).toBe(true)
    expect(banner.text()).toContain(en.redFlags.severity.URGENT_REVIEW)
    expect(banner.text()).toContain('1')
    expect(banner.html()).toContain('href="/red-flags"')
    expect(banner.classes().join(' ')).toContain('amber')
  })
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && npm run test -- tests/components/RedFlagBanner.test.ts`
Expected: FAIL — cannot resolve `@/components/RedFlagBanner.vue`.

- [ ] **Step 3: Add the i18n keys**

In `frontend/src/i18n/en.json`, insert this block immediately after the closing `}` of the `education` block (before the `enums` block), keeping valid JSON:

```json
  "redFlags": {
    "title": "Red flags",
    "currentTitle": "Current red flags",
    "historyTitle": "History",
    "noCurrent": "No active red flags.",
    "bannerCount": "Active red flags: {count}",
    "viewDetails": "View details",
    "rule": "Red flag",
    "severityHeader": "Severity",
    "detected": "Detected",
    "source": "Source",
    "status": "Status",
    "statusCurrent": "Current",
    "statusSuperseded": "Superseded",
    "severityAll": "All severities",
    "loadMore": "Load more",
    "severity": { "ROUTINE_REVIEW": "Routine review", "URGENT_REVIEW": "Urgent review", "EMERGENCY": "Emergency" },
    "sourceType": { "SYMPTOM_CHECK_IN": "Symptom check-in", "LAB_RESULT_SET": "Lab results" },
    "rules": {
      "SYM_SEVERE_ABDOMINAL_PAIN": "Severe abdominal pain",
      "SYM_SIGNIFICANT_BLEEDING": "Significant bleeding",
      "SYM_ACTIVE_FLARE": "Active flare",
      "SYM_HIGH_STOOL_FREQUENCY": "High stool frequency",
      "SYM_COMBINED_SEVERE_ACTIVITY": "Combined severe symptom activity",
      "SYM_SUSPECTED_FLARE": "Suspected flare",
      "SYM_MODERATE_DETERIORATION": "Moderate symptom deterioration",
      "LAB_SODIUM_CRITICAL": "Critical sodium",
      "LAB_POTASSIUM_CRITICAL": "Critical potassium",
      "LAB_CRP_CRITICAL": "Critical CRP",
      "LAB_CRP_HIGH": "High CRP",
      "LAB_CRP_SYMPTOM_CONTEXT": "Elevated CRP with symptom context",
      "LAB_HEMOGLOBIN_CRITICAL_LOW": "Critically low haemoglobin",
      "LAB_MAGNESIUM_CRITICAL_LOW": "Critically low magnesium",
      "LAB_UREA_CRITICAL_HIGH": "Critically high urea",
      "LAB_CREATININE_CRITICAL_HIGH": "Critically high creatinine",
      "LAB_TRANSAMINASE_CRITICAL_HIGH": "Critically high transaminase",
      "LAB_ALBUMIN_CRITICAL_LOW": "Critically low albumin",
      "LAB_CALPROTECTIN_HIGH": "High faecal calprotectin",
      "LAB_CRP_ELEVATED": "Elevated CRP",
      "LAB_ALBUMIN_LOW": "Low albumin",
      "LAB_HEMOGLOBIN_LOW_MALE": "Low haemoglobin",
      "LAB_HEMOGLOBIN_LOW_FEMALE": "Low haemoglobin",
      "LAB_CALPROTECTIN_BORDERLINE": "Borderline faecal calprotectin"
    }
  },
```

In `frontend/src/i18n/cs.json`, insert at the same position:

```json
  "redFlags": {
    "title": "Varovné signály",
    "currentTitle": "Aktuální varovné signály",
    "historyTitle": "Historie",
    "noCurrent": "Žádné aktivní varovné signály.",
    "bannerCount": "Aktivní varovné signály: {count}",
    "viewDetails": "Zobrazit podrobnosti",
    "rule": "Signál",
    "severityHeader": "Závažnost",
    "detected": "Zjištěno",
    "source": "Zdroj",
    "status": "Stav",
    "statusCurrent": "Aktuální",
    "statusSuperseded": "Nahrazeno",
    "severityAll": "Všechny závažnosti",
    "loadMore": "Načíst další",
    "severity": { "ROUTINE_REVIEW": "K běžné kontrole", "URGENT_REVIEW": "K urgentnímu posouzení", "EMERGENCY": "Akutní stav" },
    "sourceType": { "SYMPTOM_CHECK_IN": "Kontrola příznaků", "LAB_RESULT_SET": "Laboratorní výsledky" },
    "rules": {
      "SYM_SEVERE_ABDOMINAL_PAIN": "Silná bolest břicha",
      "SYM_SIGNIFICANT_BLEEDING": "Výrazné krvácení",
      "SYM_ACTIVE_FLARE": "Aktivní vzplanutí",
      "SYM_HIGH_STOOL_FREQUENCY": "Vysoká frekvence stolice",
      "SYM_COMBINED_SEVERE_ACTIVITY": "Kombinace závažných příznaků",
      "SYM_SUSPECTED_FLARE": "Podezření na vzplanutí",
      "SYM_MODERATE_DETERIORATION": "Mírné zhoršení příznaků",
      "LAB_SODIUM_CRITICAL": "Kritická hodnota sodíku",
      "LAB_POTASSIUM_CRITICAL": "Kritická hodnota draslíku",
      "LAB_CRP_CRITICAL": "Kritická hodnota CRP",
      "LAB_CRP_HIGH": "Vysoká hodnota CRP",
      "LAB_CRP_SYMPTOM_CONTEXT": "Zvýšené CRP v souvislosti s příznaky",
      "LAB_HEMOGLOBIN_CRITICAL_LOW": "Kriticky nízký hemoglobin",
      "LAB_MAGNESIUM_CRITICAL_LOW": "Kriticky nízký hořčík",
      "LAB_UREA_CRITICAL_HIGH": "Kriticky vysoká močovina",
      "LAB_CREATININE_CRITICAL_HIGH": "Kriticky vysoký kreatinin",
      "LAB_TRANSAMINASE_CRITICAL_HIGH": "Kriticky vysoké transaminázy",
      "LAB_ALBUMIN_CRITICAL_LOW": "Kriticky nízký albumin",
      "LAB_CALPROTECTIN_HIGH": "Vysoký fekální kalprotektin",
      "LAB_CRP_ELEVATED": "Zvýšené CRP",
      "LAB_ALBUMIN_LOW": "Nízký albumin",
      "LAB_HEMOGLOBIN_LOW_MALE": "Nízký hemoglobin",
      "LAB_HEMOGLOBIN_LOW_FEMALE": "Nízký hemoglobin",
      "LAB_CALPROTECTIN_BORDERLINE": "Hraniční fekální kalprotektin"
    }
  },
```

The rule keys and English names mirror the seeded catalogue in `src/main/resources/db/migration/V21__red_flag_detection_foundation.sql` (the two sex-specific haemoglobin rules intentionally share one patient-facing label).

- [ ] **Step 4: Implement the component**

Create `frontend/src/components/RedFlagBanner.vue`:

```vue
<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRedFlagsStore } from '@/stores/redFlags'
import { severityBadgeClass } from '@/utils/redFlags'
import type { RedFlagSeverity } from '@/types/api'

const props = defineProps<{ severities: RedFlagSeverity[] }>()

const { t } = useI18n()
const redFlags = useRedFlagsStore()

const visibleSeverity = computed<RedFlagSeverity | null>(() => {
  const highest = redFlags.snapshot?.highestSeverity
  if (!highest || redFlags.loadFailed || !props.severities.includes(highest)) return null
  return highest
})

const bannerClass = computed(() =>
  visibleSeverity.value ? severityBadgeClass(visibleSeverity.value) : '',
)
const severityLabel = computed(() =>
  visibleSeverity.value ? t(`redFlags.severity.${visibleSeverity.value}`) : '',
)
const count = computed(() => redFlags.snapshot?.flags.length ?? 0)
</script>

<template>
  <div v-if="visibleSeverity" data-testid="red-flag-banner"
       class="flex items-center gap-2 rounded p-3 text-sm" :class="bannerClass">
    <span class="font-medium">{{ severityLabel }}</span>
    <span>{{ t('redFlags.bannerCount', { count }) }}</span>
    <router-link to="/red-flags" class="ml-auto underline">{{ t('redFlags.viewDetails') }}</router-link>
  </div>
</template>
```

- [ ] **Step 5: Run test to verify it passes**

Run: `cd frontend && npm run test -- tests/components/RedFlagBanner.test.ts`
Expected: PASS (4 tests).

- [ ] **Step 6: Commit**

```bash
git add frontend/src/components/RedFlagBanner.vue frontend/src/i18n/en.json frontend/src/i18n/cs.json frontend/tests/components/RedFlagBanner.test.ts
git commit -m "Add red-flag banner component with i18n labels"
```

---

### Task 5: AppShell banner and navigation entry

**Files:**
- Modify: `frontend/src/components/AppShell.vue`
- Modify: `frontend/src/i18n/en.json` (add `nav.redFlags`)
- Modify: `frontend/src/i18n/cs.json` (add `nav.redFlags`)
- Test: `frontend/tests/components/AppShell.test.ts`

**Interfaces:**
- Consumes: `<RedFlagBanner>` (Task 4), `useRedFlagsStore().refreshCurrent()` (Task 3).
- Produces: banner rendered below the header with `severities = ['URGENT_REVIEW', 'EMERGENCY']` on every authenticated page except `/` and `/red-flags`; nav link to `/red-flags`; snapshot refreshed when the shell mounts.

- [ ] **Step 1: Write the failing tests**

In `frontend/tests/components/AppShell.test.ts`, first update `makeRouter()` so the new route and banner target resolve (replace the existing routes array):

```ts
function makeRouter() {
  const stub = { template: '<div />' }
  return createRouter({
    history: createMemoryHistory(),
    routes: ['/', '/diet-logs', '/check-ins', '/trends', '/labs', '/red-flags', '/onboarding', '/education', '/account', '/login']
      .map((path) => ({ path, component: stub })),
  })
}
```

Then add the two imports and the `urgentSnapshot` fixture below at the top of the file, and append the four new tests inside the existing `describe('AppShell')` block. The visibility tests stub `GET /api/red-flags/current` to return the seeded snapshot, so the shell's own mount-time refresh preserves it:

```ts
import type { PatientRedFlagSnapshot } from '@/types/api'

const urgentSnapshot: PatientRedFlagSnapshot = {
  highestSeverity: 'URGENT_REVIEW',
  flags: [
    {
      eventId: 701,
      ruleKey: 'LAB_CRP_HIGH',
      severity: 'URGENT_REVIEW',
      detectedAt: '2026-08-01T10:15:30Z',
      sourceType: 'LAB_RESULT_SET',
      sourceId: 91,
      current: true,
      supersededAt: null,
    },
  ],
}

  it('shows the urgent banner outside the dashboard and red-flags pages', async () => {
    server.use(http.get('/api/red-flags/current', () => HttpResponse.json(urgentSnapshot)))
    const router = makeRouter()
    await router.push('/labs')
    const wrapper = mount(AppShell, { global: { plugins: [createPinia(), makeI18n(), router] } })
    await flushPromises()
    expect(wrapper.find('[data-testid="red-flag-banner"]').exists()).toBe(true)
  })

  it('hides the shell banner on the dashboard and on /red-flags', async () => {
    server.use(http.get('/api/red-flags/current', () => HttpResponse.json(urgentSnapshot)))
    for (const path of ['/', '/red-flags']) {
      setActivePinia(createPinia())
      const router = makeRouter()
      await router.push(path)
      const wrapper = mount(AppShell, { global: { plugins: [createPinia(), makeI18n(), router] } })
      await flushPromises()
      expect(wrapper.find('[data-testid="red-flag-banner"]').exists()).toBe(false)
      wrapper.unmount()
    }
  })

  it('does not show the shell banner for routine-only flags', async () => {
    server.use(
      http.get('/api/red-flags/current', () =>
        HttpResponse.json({ ...urgentSnapshot, highestSeverity: 'ROUTINE_REVIEW' }),
      ),
    )
    const router = makeRouter()
    await router.push('/labs')
    const wrapper = mount(AppShell, { global: { plugins: [createPinia(), makeI18n(), router] } })
    await flushPromises()
    expect(wrapper.find('[data-testid="red-flag-banner"]').exists()).toBe(false)
  })

  it('refreshes the current snapshot on mount and renders the red-flags nav link', async () => {
    let currentCalls = 0
    server.use(
      http.get('/api/red-flags/current', () => {
        currentCalls += 1
        return HttpResponse.json({ highestSeverity: null, flags: [] })
      }),
    )
    const router = makeRouter()
    await router.push('/')
    const wrapper = mount(AppShell, { global: { plugins: [createPinia(), makeI18n(), router] } })
    await flushPromises()
    expect(currentCalls).toBe(1)
    expect(wrapper.html()).toContain('href="/red-flags"')
    expect(wrapper.text()).toContain(en.nav.redFlags)
  })
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd frontend && npm run test -- tests/components/AppShell.test.ts`
Expected: FAIL — no `red-flag-banner` testid exists, no `/red-flags` nav link, `currentCalls` stays 0.

- [ ] **Step 3: Modify AppShell.vue**

In `frontend/src/components/AppShell.vue`:

Replace the script-setup imports and setup section (lines 1-23) with:

```ts
<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { useAuthStore } from '@/stores/auth'
import { useRedFlagsStore } from '@/stores/redFlags'
import { setLocale, type AppLocale } from '@/i18n'
import { setTheme, currentTheme, type ThemePreference } from '@/theme'
import { accountApi } from '@/api/account'
import RedFlagBanner from '@/components/RedFlagBanner.vue'
import type { RedFlagSeverity } from '@/types/api'

const { t, locale } = useI18n()
const auth = useAuthStore()
const redFlags = useRedFlagsStore()
const router = useRouter()
const route = useRoute()

const URGENT_PLUS: RedFlagSeverity[] = ['URGENT_REVIEW', 'EMERGENCY']

const links = computed(() => [
  { to: '/', label: t('nav.dashboard') },
  { to: '/diet-logs', label: t('nav.dietLogs') },
  { to: '/check-ins', label: t('nav.checkIns') },
  { to: '/trends', label: t('nav.trends') },
  { to: '/labs', label: t('nav.labs') },
  { to: '/red-flags', label: t('nav.redFlags') },
  { to: '/onboarding', label: t('nav.onboarding') },
  { to: '/education', label: t('nav.education') },
  { to: '/account', label: t('nav.account') },
])

// The dashboard shows its own all-severity banner; /red-flags is the detail.
const showShellBanner = computed(() => route.path !== '/' && route.path !== '/red-flags')

onMounted(() => {
  void redFlags.refreshCurrent()
})
```

Leave the rest of the script (`switchLocale`, `theme`, `switchTheme`, `logout`) unchanged.

In the template, render the banner inside `<main>` above the router view:

```html
    <main class="mx-auto max-w-5xl px-4 py-6">
      <RedFlagBanner v-if="showShellBanner" :severities="URGENT_PLUS" class="mb-4" />
      <router-view />
    </main>
```

Add the nav key to `frontend/src/i18n/en.json` (inside the `nav` object):

```json
    "education": "Education", "redFlags": "Red flags", "account": "Account", "logout": "Log out",
```

and to `frontend/src/i18n/cs.json`:

```json
    "education": "Vzdělávání", "redFlags": "Varovné signály", "account": "Účet", "logout": "Odhlásit se",
```

- [ ] **Step 4: Run the full AppShell suite**

Run: `cd frontend && npm run test -- tests/components/`
Expected: PASS. The three pre-existing AppShell tests do not stub `/api/red-flags/current`; the shell's mount refresh fails silently into `loadFailed` (it never throws), so they stay green. MSW may log an unhandled-request error line for those tests — that is expected noise, not a failure.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/components/AppShell.vue frontend/src/i18n/en.json frontend/src/i18n/cs.json frontend/tests/components/AppShell.test.ts
git commit -m "Show urgent red-flag banner app-wide and add red-flags nav entry"
```

---

### Task 6: Dashboard banner for all severities

**Files:**
- Modify: `frontend/src/views/DashboardView.vue`
- Test: `frontend/tests/views/DashboardView.test.ts`

**Interfaces:**
- Consumes: `<RedFlagBanner>` (Task 4), `useRedFlagsStore().refreshCurrent()` (Task 3).
- Produces: dashboard renders the banner with all three severities and refreshes the snapshot on mount.

- [ ] **Step 1: Write the failing test**

Append inside the existing `describe('DashboardView')` block in `frontend/tests/views/DashboardView.test.ts`, plus the import `import { useRedFlagsStore } from '@/stores/redFlags'` at the top:

```ts
  it('shows the red-flag banner for any severity, including routine', async () => {
    server.use(
      http.get(`/api/diet-logs/${todayIso()}`, () => HttpResponse.json({ error: 'not_found' }, { status: 404 })),
      http.get(`/api/symptom-check-ins/${todayIso()}`, () => HttpResponse.json({ error: 'not_found' }, { status: 404 })),
      http.get('/api/red-flags/current', () => HttpResponse.json({
        highestSeverity: 'ROUTINE_REVIEW',
        flags: [
          {
            eventId: 702,
            ruleKey: 'LAB_CALPROTECTIN_BORDERLINE',
            severity: 'ROUTINE_REVIEW',
            detectedAt: '2026-08-01T09:00:00Z',
            sourceType: 'LAB_RESULT_SET',
            sourceId: 92,
            current: true,
            supersededAt: null,
          },
        ],
      })),
    )
    const wrapper = mount(DashboardView, { global: { plugins: [createPinia(), i18n] } })
    await flushPromises()
    expect(wrapper.find('[data-testid="red-flag-banner"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="red-flag-banner"]').text()).toContain(en.redFlags.severity.ROUTINE_REVIEW)
  })
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && npm run test -- tests/views/DashboardView.test.ts`
Expected: FAIL — no `red-flag-banner` testid.

- [ ] **Step 3: Modify DashboardView.vue**

In `frontend/src/views/DashboardView.vue`, add to the imports:

```ts
import { useRedFlagsStore } from '@/stores/redFlags'
import RedFlagBanner from '@/components/RedFlagBanner.vue'
import type { RedFlagSeverity } from '@/types/api'
```

Add a module-level constant in the script (above `onMounted`):

```ts
const ALL_SEVERITIES: RedFlagSeverity[] = ['ROUTINE_REVIEW', 'URGENT_REVIEW', 'EMERGENCY']
```

Inside `onMounted`, as the first statement, add:

```ts
  void useRedFlagsStore().refreshCurrent()
```

In the template, render the banner directly under the `<h1>`:

```html
    <h1 class="text-2xl font-semibold">{{ t('dashboard.title') }}</h1>
    <RedFlagBanner :severities="ALL_SEVERITIES" class="mt-4" />
```

(When the dashboard runs inside AppShell, the shell banner is suppressed on `/` by Task 5, so this is the only banner on the page.)

- [ ] **Step 4: Run the dashboard suite**

Run: `cd frontend && npm run test -- tests/views/DashboardView.test.ts`
Expected: PASS (4 tests). The three pre-existing tests do not stub `/api/red-flags/current`; the refresh fails silently into `loadFailed`, hiding the banner — expected MSW unhandled-request log noise, not a failure.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/views/DashboardView.vue frontend/tests/views/DashboardView.test.ts
git commit -m "Show all-severity red-flag banner on the dashboard"
```

---

### Task 7: RedFlagsView with current snapshot and paginated history

**Files:**
- Create: `frontend/src/views/RedFlagsView.vue`
- Modify: `frontend/src/router/index.ts` (import + one child route at the marked comment line)
- Test: `frontend/tests/views/RedFlagsView.test.ts`

**Interfaces:**
- Consumes: `redFlagApi.getHistory` (Task 1), `useRedFlagsStore()` (Task 3), `severityBadgeClass`/`formatDateTime` (Task 2), `useApiError`, `dateRangeError`, and the `redFlags.*` i18n keys (Task 4).
- Produces: `/red-flags` route (child of the `AppShell` layout, `meta: { requiresAuth: true }`).

- [ ] **Step 1: Write the failing tests**

Create `frontend/tests/views/RedFlagsView.test.ts`:

```ts
import { flushPromises, mount } from '@vue/test-utils'
import { http, HttpResponse } from 'msw'
import { beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { createI18n } from 'vue-i18n'
import { server } from '../msw/server'
import RedFlagsView from '@/views/RedFlagsView.vue'
import en from '@/i18n/en.json'

const i18n = createI18n({ legacy: false, locale: 'en', messages: { en } })

const currentSnapshot = {
  highestSeverity: 'URGENT_REVIEW',
  flags: [
    {
      eventId: 701,
      ruleKey: 'LAB_CRP_HIGH',
      severity: 'URGENT_REVIEW',
      detectedAt: '2026-08-01T10:15:30Z',
      sourceType: 'LAB_RESULT_SET',
      sourceId: 91,
      current: true,
      supersededAt: null,
    },
  ],
}

const historyEvent = {
  eventId: 700,
  ruleKey: 'SYM_ACTIVE_FLARE',
  severity: 'URGENT_REVIEW',
  detectedAt: '2026-07-30T08:00:00Z',
  sourceType: 'SYMPTOM_CHECK_IN',
  sourceId: 55,
  current: true,
  supersededAt: null,
}

function mountView() {
  return mount(RedFlagsView, { global: { plugins: [createPinia(), i18n] } })
}

describe('RedFlagsView', () => {
  beforeEach(() => setActivePinia(createPinia()))

  it('shows current flags and history rows with localized labels', async () => {
    server.use(
      http.get('/api/red-flags/current', () => HttpResponse.json(currentSnapshot)),
      http.get('/api/red-flags/history', () => HttpResponse.json({ items: [historyEvent], nextCursor: null })),
    )
    const wrapper = mountView()
    await flushPromises()
    expect(wrapper.text()).toContain(en.redFlags.rules.LAB_CRP_HIGH)
    expect(wrapper.text()).toContain(en.redFlags.rules.SYM_ACTIVE_FLARE)
    expect(wrapper.text()).toContain(en.redFlags.sourceType.SYMPTOM_CHECK_IN)
    expect(wrapper.text()).toContain(en.redFlags.statusCurrent)
    expect(wrapper.text()).not.toContain(en.redFlags.noCurrent)
  })

  it('falls back to the raw rule key for unknown rules', async () => {
    server.use(
      http.get('/api/red-flags/current', () => HttpResponse.json({ highestSeverity: null, flags: [] })),
      http.get('/api/red-flags/history', () =>
        HttpResponse.json({ items: [{ ...historyEvent, ruleKey: 'LAB_FUTURE_RULE' }], nextCursor: null }),
      ),
    )
    const wrapper = mountView()
    await flushPromises()
    expect(wrapper.text()).toContain('LAB_FUTURE_RULE')
    expect(wrapper.text()).toContain(en.redFlags.noCurrent)
  })

  it('labels superseded history entries as superseded', async () => {
    server.use(
      http.get('/api/red-flags/current', () => HttpResponse.json({ highestSeverity: null, flags: [] })),
      http.get('/api/red-flags/history', () =>
        HttpResponse.json({
          items: [{ ...historyEvent, current: false, supersededAt: '2026-07-31T08:00:00Z' }],
          nextCursor: null,
        }),
      ),
    )
    const wrapper = mountView()
    await flushPromises()
    expect(wrapper.text()).toContain(en.redFlags.statusSuperseded)
    expect(wrapper.text()).not.toContain(en.redFlags.statusCurrent)
  })

  it('blocks a range over 370 days without calling the history API again', async () => {
    let historyCalls = 0
    server.use(
      http.get('/api/red-flags/current', () => HttpResponse.json({ highestSeverity: null, flags: [] })),
      http.get('/api/red-flags/history', () => {
        historyCalls += 1
        return HttpResponse.json({ items: [], nextCursor: null })
      }),
    )
    const wrapper = mountView()
    await flushPromises()
    expect(historyCalls).toBe(1)

    const [fromInput, toInput] = wrapper.findAll('input[type="date"]')
    await fromInput.setValue('2024-01-01')
    await toInput.setValue('2025-01-06')
    await wrapper.find('[data-testid="apply-history"]').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain(en.errors.date_range_too_long)
    expect(historyCalls).toBe(1)
  })

  it('applies the severity filter to history requests', async () => {
    const capturedUrls: string[] = []
    server.use(
      http.get('/api/red-flags/current', () => HttpResponse.json({ highestSeverity: null, flags: [] })),
      http.get('/api/red-flags/history', ({ request }) => {
        capturedUrls.push(request.url)
        return HttpResponse.json({ items: [], nextCursor: null })
      }),
    )
    const wrapper = mountView()
    await flushPromises()
    expect(new URL(capturedUrls[0]).searchParams.has('severity')).toBe(false)

    await wrapper.find('[data-testid="severity-filter"]').setValue('EMERGENCY')
    await wrapper.find('[data-testid="apply-history"]').trigger('click')
    await flushPromises()

    expect(new URL(capturedUrls[1]).searchParams.get('severity')).toBe('EMERGENCY')
  })

  it('appends the next page on Load more and hides the button at the end', async () => {
    server.use(
      http.get('/api/red-flags/current', () => HttpResponse.json({ highestSeverity: null, flags: [] })),
      http.get('/api/red-flags/history', ({ request }) => {
        const cursor = new URL(request.url).searchParams.get('cursor')
        if (!cursor) {
          return HttpResponse.json({ items: [historyEvent], nextCursor: 'cursor-2' })
        }
        return HttpResponse.json({
          items: [{ ...historyEvent, eventId: 699, ruleKey: 'LAB_CRP_ELEVATED' }],
          nextCursor: null,
        })
      }),
    )
    const wrapper = mountView()
    await flushPromises()
    expect(wrapper.find('[data-testid="history-table"]').findAll('tbody tr')).toHaveLength(1)
    const loadMore = wrapper.find('[data-testid="load-more"]')
    expect(loadMore.exists()).toBe(true)

    await loadMore.trigger('click')
    await flushPromises()

    expect(wrapper.find('[data-testid="history-table"]').findAll('tbody tr')).toHaveLength(2)
    expect(wrapper.find('[data-testid="load-more"]').exists()).toBe(false)
  })
})
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd frontend && npm run test -- tests/views/RedFlagsView.test.ts`
Expected: FAIL — cannot resolve `@/views/RedFlagsView.vue`.

- [ ] **Step 3: Implement the view**

Create `frontend/src/views/RedFlagsView.vue`:

```vue
<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { redFlagApi } from '@/api/redFlags'
import { useRedFlagsStore } from '@/stores/redFlags'
import { useApiError } from '@/composables/useApiError'
import { dateRangeError } from '@/utils/dateRange'
import { formatDateTime } from '@/utils/dateTime'
import { severityBadgeClass } from '@/utils/redFlags'
import type { PatientRedFlagEvent, RedFlagSeverity } from '@/types/api'

const { t, te, locale } = useI18n()
const { message, capture, clear } = useApiError()
const redFlags = useRedFlagsStore()

function iso(d: Date): string {
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

const today = new Date()
const monthAgo = new Date(today)
monthAgo.setDate(monthAgo.getDate() - 30)

const from = ref(iso(monthAgo))
const to = ref(iso(today))
const severity = ref<RedFlagSeverity | ''>('')
const items = ref<PatientRedFlagEvent[]>([])
const nextCursor = ref<string | null>(null)
const loading = ref(true)
const loadingMore = ref(false)

const severityOptions: RedFlagSeverity[] = ['ROUTINE_REVIEW', 'URGENT_REVIEW', 'EMERGENCY']

function ruleLabel(ruleKey: string): string {
  const key = `redFlags.rules.${ruleKey}`
  return te(key) ? t(key) : ruleKey
}

async function load() {
  clear()
  const rangeError = dateRangeError(from.value, to.value)
  if (rangeError) {
    message.value = t(`errors.date_range_${rangeError === 'too_long' ? 'too_long' : 'invalid'}`)
    return
  }
  loading.value = true
  items.value = []
  nextCursor.value = null
  try {
    const page = await redFlagApi.getHistory({
      from: from.value,
      to: to.value,
      severity: severity.value || undefined,
      size: 25,
    })
    items.value = page.items
    nextCursor.value = page.nextCursor
  } catch (e) {
    capture(e)
  } finally {
    loading.value = false
  }
}

async function loadMore() {
  if (!nextCursor.value) return
  clear()
  loadingMore.value = true
  try {
    const page = await redFlagApi.getHistory({
      from: from.value,
      to: to.value,
      severity: severity.value || undefined,
      cursor: nextCursor.value,
      size: 25,
    })
    items.value = [...items.value, ...page.items]
    nextCursor.value = page.nextCursor
  } catch (e) {
    capture(e)
  } finally {
    loadingMore.value = false
  }
}

onMounted(() => {
  void redFlags.refreshCurrent()
  void load()
})
</script>

<template>
  <section>
    <h1 class="text-2xl font-semibold">{{ t('redFlags.title') }}</h1>

    <h2 class="mt-6 text-lg font-medium">{{ t('redFlags.currentTitle') }}</h2>
    <p v-if="!redFlags.snapshot || redFlags.snapshot.flags.length === 0" class="mt-2 text-sm">
      {{ t('redFlags.noCurrent') }}
    </p>
    <table v-else data-testid="current-table" class="mt-2 w-full border-collapse bg-white text-sm dark:bg-gray-800">
      <thead>
        <tr class="border-b text-left">
          <th class="p-2">{{ t('redFlags.rule') }}</th>
          <th class="p-2">{{ t('redFlags.severityHeader') }}</th>
          <th class="p-2">{{ t('redFlags.detected') }}</th>
          <th class="p-2">{{ t('redFlags.source') }}</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="f in redFlags.snapshot.flags" :key="f.eventId" class="border-b">
          <td class="p-2">{{ ruleLabel(f.ruleKey) }}</td>
          <td class="p-2">
            <span class="rounded px-2 py-0.5" :class="severityBadgeClass(f.severity)">
              {{ t(`redFlags.severity.${f.severity}`) }}
            </span>
          </td>
          <td class="p-2">{{ formatDateTime(f.detectedAt, locale) }}</td>
          <td class="p-2">{{ t(`redFlags.sourceType.${f.sourceType}`) }}</td>
        </tr>
      </tbody>
    </table>

    <h2 class="mt-6 text-lg font-medium">{{ t('redFlags.historyTitle') }}</h2>
    <div class="mt-2 flex flex-wrap items-end gap-3">
      <label class="text-sm">{{ t('common.from') }}
        <input v-model="from" type="date" class="ml-1 rounded border border-gray-300 px-2 py-1 dark:border-gray-600 dark:bg-gray-800" />
      </label>
      <label class="text-sm">{{ t('common.to') }}
        <input v-model="to" type="date" class="ml-1 rounded border border-gray-300 px-2 py-1 dark:border-gray-600 dark:bg-gray-800" />
      </label>
      <label class="text-sm">{{ t('redFlags.severityHeader') }}
        <select v-model="severity" data-testid="severity-filter"
                class="ml-1 rounded border border-gray-300 px-2 py-1 dark:border-gray-600 dark:bg-gray-800">
          <option value="">{{ t('redFlags.severityAll') }}</option>
          <option v-for="s in severityOptions" :key="s" :value="s">{{ t(`redFlags.severity.${s}`) }}</option>
        </select>
      </label>
      <button data-testid="apply-history" class="rounded bg-blue-600 px-3 py-1 text-sm text-white" @click="load">
        {{ t('common.apply') }}
      </button>
    </div>

    <p v-if="message" class="mt-4 rounded bg-red-50 p-3 text-sm text-red-700 dark:bg-red-950 dark:text-red-300">{{ message }}</p>
    <p v-if="loading" class="mt-4">{{ t('common.loading') }}</p>
    <template v-else>
      <table data-testid="history-table" class="mt-4 w-full border-collapse bg-white text-sm dark:bg-gray-800">
        <thead>
          <tr class="border-b text-left">
            <th class="p-2">{{ t('redFlags.rule') }}</th>
            <th class="p-2">{{ t('redFlags.severityHeader') }}</th>
            <th class="p-2">{{ t('redFlags.detected') }}</th>
            <th class="p-2">{{ t('redFlags.source') }}</th>
            <th class="p-2">{{ t('redFlags.status') }}</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="f in items" :key="f.eventId" class="border-b">
            <td class="p-2">{{ ruleLabel(f.ruleKey) }}</td>
            <td class="p-2">
              <span class="rounded px-2 py-0.5" :class="severityBadgeClass(f.severity)">
                {{ t(`redFlags.severity.${f.severity}`) }}
              </span>
            </td>
            <td class="p-2">{{ formatDateTime(f.detectedAt, locale) }}</td>
            <td class="p-2">{{ t(`redFlags.sourceType.${f.sourceType}`) }}</td>
            <td class="p-2">{{ f.current ? t('redFlags.statusCurrent') : t('redFlags.statusSuperseded') }}</td>
          </tr>
        </tbody>
      </table>
      <button v-if="nextCursor" data-testid="load-more" :disabled="loadingMore"
              class="mt-3 rounded border px-3 py-1 text-sm" @click="loadMore">
        {{ t('redFlags.loadMore') }}
      </button>
    </template>
  </section>
</template>
```

- [ ] **Step 4: Register the route**

In `frontend/src/router/index.ts`, add the import with the other view imports:

```ts
import RedFlagsView from '@/views/RedFlagsView.vue'
```

and add the child route at the marked comment line (inside the `AppShell` children array):

```ts
      { path: 'red-flags', component: RedFlagsView, meta: { requiresAuth: true } },
```

- [ ] **Step 5: Run the view tests**

Run: `cd frontend && npm run test -- tests/views/RedFlagsView.test.ts && npm run typecheck`
Expected: PASS (6 tests); typecheck clean.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/views/RedFlagsView.vue frontend/src/router/index.ts frontend/tests/views/RedFlagsView.test.ts
git commit -m "Add red-flags view with current snapshot and paginated history"
```

---

### Task 8: Refresh the snapshot after check-in and lab saves

**Files:**
- Modify: `frontend/src/views/CheckInEditView.vue`
- Modify: `frontend/src/views/LabResultSetEditView.vue`
- Test: `frontend/tests/views/CheckInEditView.test.ts`
- Test: `frontend/tests/views/LabResultSetEditView.test.ts`

**Interfaces:**
- Consumes: `useRedFlagsStore().refreshCurrent()` (Task 3).
- Produces: both save flows trigger a fire-and-forget snapshot refresh after a successful save. (The labs *removal request* flow in `LabResultSetsView.vue` is intentionally untouched: it only requests staff removal and does not change the patient's data, so no red-flag evaluation is superseded at that moment.)

- [ ] **Step 1: Write the failing tests**

Append inside `describe('CheckInEditView')` in `frontend/tests/views/CheckInEditView.test.ts`:

```ts
  it('refreshes the current red-flag snapshot after a successful save', async () => {
    let currentCalls = 0
    server.use(
      http.get('/api/symptom-questionnaires/active', () => HttpResponse.json(questionnaire)),
      http.get('/api/symptom-check-ins/2026-07-24', () => HttpResponse.json({ error: 'not_found' }, { status: 404 })),
      http.get('/api/csrf', () => HttpResponse.json({ token: 't', headerName: 'X-XSRF-TOKEN' })),
      http.post('/api/symptom-check-ins', () => HttpResponse.json({ id: 1 })),
      http.get('/api/red-flags/current', () => {
        currentCalls += 1
        return HttpResponse.json({ highestSeverity: null, flags: [] })
      }),
    )
    const router = makeRouter()
    await router.push('/check-ins/2026-07-24')
    const wrapper = mount(CheckInEditView, { global: { plugins: [createPinia(), i18n, router] } })
    await flushPromises()
    expect(currentCalls).toBe(0)

    await wrapper.find('[data-testid="save"]').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain(en.common.saved)
    expect(currentCalls).toBe(1)
  })
```

Append inside `describe('LabResultSetEditView')` in `frontend/tests/views/LabResultSetEditView.test.ts`:

```ts
  it('refreshes the current red-flag snapshot after a successful update', async () => {
    let currentCalls = 0
    server.use(
      http.get('/api/lab-tests', () => HttpResponse.json(catalog)),
      http.get('/api/lab-result-sets/3', () => HttpResponse.json(existing)),
      http.get('/api/csrf', () => HttpResponse.json({ token: 't', headerName: 'X-XSRF-TOKEN' })),
      http.put('/api/lab-result-sets/3', () => HttpResponse.json({ ...existing, version: 3 })),
      http.get('/api/red-flags/current', () => {
        currentCalls += 1
        return HttpResponse.json({ highestSeverity: null, flags: [] })
      }),
    )
    const router = await makeRouter('/labs/3')
    const wrapper = mount(LabResultSetEditView, { global: { plugins: [createPinia(), i18n, router] } })
    await flushPromises()
    expect(currentCalls).toBe(0)

    await wrapper.find('[data-testid="save"]').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain(en.common.saved)
    expect(currentCalls).toBe(1)
  })
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd frontend && npm run test -- tests/views/CheckInEditView.test.ts tests/views/LabResultSetEditView.test.ts`
Expected: FAIL — `currentCalls` stays 0 after the save in both new tests.

- [ ] **Step 3: Wire the refresh into both save flows**

In `frontend/src/views/CheckInEditView.vue`, add the import:

```ts
import { useRedFlagsStore } from '@/stores/redFlags'
```

and in `save()`, immediately after `saved.value = true`:

```ts
    saved.value = true
    void useRedFlagsStore().refreshCurrent()
```

In `frontend/src/views/LabResultSetEditView.vue`, add the same import and, after `saved.value = true`:

```ts
    saved.value = true
    void useRedFlagsStore().refreshCurrent()
```

Both are fire-and-forget: `refreshCurrent()` never throws, so a failed refresh cannot break or delay the save confirmation.

- [ ] **Step 4: Run both view suites**

Run: `cd frontend && npm run test -- tests/views/CheckInEditView.test.ts tests/views/LabResultSetEditView.test.ts`
Expected: PASS. Pre-existing save tests in these files do not stub `/api/red-flags/current`; the refresh fails silently — expected MSW unhandled-request log noise, not a failure.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/views/CheckInEditView.vue frontend/src/views/LabResultSetEditView.vue frontend/tests/views/CheckInEditView.test.ts frontend/tests/views/LabResultSetEditView.test.ts
git commit -m "Refresh red-flag snapshot after check-in and lab saves"
```

---

### Task 9: Final verification

**Files:** none (verification only).

- [ ] **Step 1: Run the full frontend suite and typecheck**

Run: `cd frontend && npm run test && npm run typecheck`
Expected: all tests PASS, typecheck clean.

- [ ] **Step 2: Verify the production build compiles**

Run: `cd frontend && npm run build`
Expected: build completes without errors (catches any template/type issue Vitest missed).

- [ ] **Step 3: Confirm no backend changes**

Run: `git status --short && git diff --stat master...HEAD -- src/`
Expected: no modifications under `src/`; only `frontend/` files changed by this plan.

- [ ] **Step 4: Final commit (if anything was fixed during verification)**

```bash
git add -A frontend/
git commit -m "Fix issues found in final red-flags SPA verification"
```

Only run this commit if Steps 1-2 required fixes; otherwise the work is already committed task by task.
