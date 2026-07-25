# SPA Theme Preference Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a Light/Dark/System theme selector to the patient SPA, persisted through the existing `GET|PUT /api/account/preferences/theme` endpoints, with a full dark-mode styling pass.

**Architecture:** Mirror the SPA's language-preference pattern: a plain `frontend/src/theme.ts` module (localStorage-first, applied before mount, best-effort backend sync on login), a selector in `AppShell.vue`, Tailwind v4 class-based dark mode via `@custom-variant`, and `dark:` variants across all views/components.

**Tech Stack:** Vue 3, TypeScript, Pinia, vue-i18n, Tailwind CSS v4 (`@tailwindcss/vite`), Vitest + msw, vue-chartjs/Chart.js.

**Spec:** `docs/superpowers/specs/2026-07-25-spa-theme-preference-design.md`

## Global Constraints

- Backend contract (already implemented, do not change): `GET /api/account/preferences/theme` → `{ "theme": "SYSTEM" | "LIGHT" | "DARK" }`; `PUT` same path with body `{ "theme": "<value>" }` → `{ "status": "ok" }`.
- All backend sync is best-effort: fetch/update failures never break auth flows or the selector; the local choice still applies.
- Invalid localStorage values fall back to `SYSTEM`.
- All frontend commands run from `frontend/`: `npm run test`, `npm run typecheck`, `npm run build`.
- No new dependencies. Do not modify the backend (`src/main/**`).
- i18n keys go in both `frontend/src/i18n/en.json` and `frontend/src/i18n/cs.json`, mirroring the backend message keys (`theme.system/light/dark`).

## Dark-mode class mapping (used by Tasks 7–9)

Apply these transformations wherever the left-hand class appears. Keep the original class and append the `dark:` variant.

| Light class | Add |
|---|---|
| `bg-gray-50` (page background) | `dark:bg-gray-900` |
| `bg-white` | `dark:bg-gray-800` |
| `border-gray-300` | `dark:border-gray-600` |
| `text-gray-700` | `dark:text-gray-300` |
| `text-gray-600` | `dark:text-gray-400` |
| `text-gray-500` | `dark:text-gray-400` |
| `bg-red-50` / `text-red-700` (error banner) | `dark:bg-red-950` / `dark:text-red-300` |
| `bg-green-50` / `text-green-700` (success banner) | `dark:bg-green-950` / `dark:text-green-300` |
| `bg-amber-50` / `border-amber-400` / `text-amber-800` | `dark:bg-amber-950` / `dark:border-amber-700` / `dark:text-amber-200` |
| `bg-green-100` | `dark:bg-green-900` |
| `text-red-600` | `dark:text-red-400` |
| `text-blue-600` | `dark:text-blue-400` |
| `text-blue-700` / `hover:text-blue-700` | `dark:text-blue-300` / `dark:hover:text-blue-300` |

Leave unchanged (already dark-safe): `bg-blue-600 text-white`, `bg-red-600 text-white`, `bg-black/40`, `border-collapse`, layout/typography utilities.

Any additional light-only surface/text/border class found during the pass (e.g. `bg-gray-100`, `border-gray-200`, `divide-gray-...`, `hover:bg-gray-...`) gets the analogous gray-shifted `dark:` variant (`gray-100→gray-800`, `gray-200→gray-700`).

---

### Task 1: `theme.ts` module + init in `main.ts`

**Files:**
- Create: `frontend/src/theme.ts`
- Modify: `frontend/src/main.ts`
- Test: `frontend/tests/theme.test.ts`

**Interfaces:**
- Produces: `ThemePreference` (`'SYSTEM' | 'LIGHT' | 'DARK'`), `THEME_STORAGE_KEY` (`'metabion.theme'`), `initTheme(): void`, `setTheme(pref: ThemePreference): void`, `currentTheme(): ThemePreference`, `isDark: Ref<boolean>`. Used by Tasks 2–5 and 7.

- [ ] **Step 1: Write the failing test**

Create `frontend/tests/theme.test.ts`:

```ts
import { beforeEach, describe, expect, it, vi } from 'vitest'

type ChangeCallback = (e: { matches: boolean }) => void

function stubMatchMedia(matches: boolean) {
  const listeners = new Set<ChangeCallback>()
  Object.defineProperty(window, 'matchMedia', {
    writable: true,
    configurable: true,
    value: (query: string) => ({
      matches,
      media: query,
      addEventListener: (_: string, cb: ChangeCallback) => listeners.add(cb),
      removeEventListener: (_: string, cb: ChangeCallback) => listeners.delete(cb),
    }),
  })
  return { fire: (m: boolean) => listeners.forEach((cb) => cb({ matches: m })) }
}

describe('theme', () => {
  beforeEach(() => {
    vi.resetModules()
    localStorage.clear()
    document.documentElement.classList.remove('dark')
  })

  it('defaults to SYSTEM and follows the OS when nothing is stored', async () => {
    stubMatchMedia(true)
    const { initTheme, THEME_STORAGE_KEY, currentTheme, isDark } = await import('@/theme')
    initTheme()
    expect(currentTheme()).toBe('SYSTEM')
    expect(document.documentElement.classList.contains('dark')).toBe(true)
    expect(isDark.value).toBe(true)
    expect(localStorage.getItem(THEME_STORAGE_KEY)).toBeNull()
  })

  it('restores a stored preference', async () => {
    stubMatchMedia(true)
    localStorage.setItem('metabion.theme', 'LIGHT')
    const { initTheme, currentTheme } = await import('@/theme')
    initTheme()
    expect(currentTheme()).toBe('LIGHT')
    expect(document.documentElement.classList.contains('dark')).toBe(false)
  })

  it('falls back to SYSTEM for an unknown stored value', async () => {
    stubMatchMedia(false)
    localStorage.setItem('metabion.theme', 'neon')
    const { initTheme, currentTheme } = await import('@/theme')
    initTheme()
    expect(currentTheme()).toBe('SYSTEM')
    expect(document.documentElement.classList.contains('dark')).toBe(false)
  })

  it('setTheme persists and applies immediately', async () => {
    stubMatchMedia(false)
    const { setTheme, currentTheme, isDark } = await import('@/theme')
    setTheme('DARK')
    expect(currentTheme()).toBe('DARK')
    expect(localStorage.getItem('metabion.theme')).toBe('DARK')
    expect(document.documentElement.classList.contains('dark')).toBe(true)
    expect(isDark.value).toBe(true)
  })

  it('SYSTEM re-resolves when the OS preference changes', async () => {
    const media = stubMatchMedia(false)
    const { initTheme, setTheme } = await import('@/theme')
    initTheme()
    expect(document.documentElement.classList.contains('dark')).toBe(false)
    media.fire(true)
    expect(document.documentElement.classList.contains('dark')).toBe(true)
    setTheme('LIGHT')
    media.fire(false)
    expect(document.documentElement.classList.contains('dark')).toBe(false)
  })
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && npx vitest run tests/theme.test.ts`
Expected: FAIL — `@/theme` module not found. (jsdom has no `matchMedia`; the stub above is required.)

- [ ] **Step 3: Write the implementation**

Create `frontend/src/theme.ts`:

```ts
import { ref } from 'vue'

export type ThemePreference = 'SYSTEM' | 'LIGHT' | 'DARK'

export const THEME_STORAGE_KEY = 'metabion.theme'

/** Reactive dark-state for non-CSS consumers (e.g. Chart.js colors). */
export const isDark = ref(false)

let current: ThemePreference = 'SYSTEM'
let listening = false

function resolve(pref: ThemePreference): boolean {
  if (pref === 'DARK') return true
  if (pref === 'LIGHT') return false
  return window.matchMedia('(prefers-color-scheme: dark)').matches
}

function apply(pref: ThemePreference): void {
  current = pref
  const dark = resolve(pref)
  document.documentElement.classList.toggle('dark', dark)
  isDark.value = dark
  if (listening) return
  listening = true
  window.matchMedia('(prefers-color-scheme: dark)').addEventListener('change', () => {
    if (current === 'SYSTEM') apply('SYSTEM')
  })
}

export function setTheme(pref: ThemePreference): void {
  localStorage.setItem(THEME_STORAGE_KEY, pref)
  apply(pref)
}

export function initTheme(): void {
  const stored = localStorage.getItem(THEME_STORAGE_KEY)
  apply(stored === 'SYSTEM' || stored === 'LIGHT' || stored === 'DARK' ? stored : 'SYSTEM')
}

export function currentTheme(): ThemePreference {
  return current
}
```

In `frontend/src/main.ts`, add the import and call after `initLocale()`:

```ts
import { i18n, initLocale } from './i18n'
import { initTheme } from './theme'
```

```ts
initLocale()
initTheme()
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd frontend && npx vitest run tests/theme.test.ts`
Expected: PASS (5 tests)

- [ ] **Step 5: Commit**

```bash
git add frontend/src/theme.ts frontend/src/main.ts frontend/tests/theme.test.ts
git commit -m "Add SPA theme module with system/light/dark resolution"
```

---

### Task 2: Account API endpoints for theme

**Files:**
- Modify: `frontend/src/api/account.ts`
- Test: `frontend/tests/api/account.test.ts` (new)

**Interfaces:**
- Consumes: `ThemePreference` from `@/theme` (Task 1).
- Produces: `accountApi.getThemePreference(): Promise<{ theme: ThemePreference }>`, `accountApi.updateThemePreference(theme: ThemePreference): Promise<{ status: string }>`. Used by Tasks 3 and 4.

- [ ] **Step 1: Write the failing test**

Create `frontend/tests/api/account.test.ts`:

```ts
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import { server } from '../msw/server'
import { accountApi } from '@/api/account'

describe('accountApi theme preference', () => {
  it('fetches the current theme preference', async () => {
    server.use(http.get('/api/account/preferences/theme', () => HttpResponse.json({ theme: 'DARK' })))
    const pref = await accountApi.getThemePreference()
    expect(pref.theme).toBe('DARK')
  })

  it('updates the theme preference', async () => {
    let putBody: unknown
    server.use(
      http.get('/api/csrf', () => HttpResponse.json({ token: 't', headerName: 'X-XSRF-TOKEN' })),
      http.put('/api/account/preferences/theme', async ({ request }) => {
        putBody = await request.json()
        return HttpResponse.json({ status: 'ok' })
      }),
    )
    const res = await accountApi.updateThemePreference('DARK')
    expect(res.status).toBe('ok')
    expect(putBody).toEqual({ theme: 'DARK' })
  })
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && npx vitest run tests/api/account.test.ts`
Expected: FAIL — `accountApi.getThemePreference` is not a function.

- [ ] **Step 3: Add the endpoints**

In `frontend/src/api/account.ts`, add the import and two methods inside `accountApi`:

```ts
import { apiFetch } from './http'
import type { ThemePreference } from '@/theme'
```

```ts
  getThemePreference: () => apiFetch<{ theme: ThemePreference }>('/api/account/preferences/theme'),
  updateThemePreference: (theme: ThemePreference) =>
    apiFetch<{ status: string }>('/api/account/preferences/theme', { method: 'PUT', body: { theme } }),
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd frontend && npx vitest run tests/api/account.test.ts`
Expected: PASS (2 tests)

- [ ] **Step 5: Commit**

```bash
git add frontend/src/api/account.ts frontend/tests/api/account.test.ts
git commit -m "Add theme preference endpoints to SPA account API"
```

---

### Task 3: Auth store syncs theme preference on login/session restore

**Files:**
- Modify: `frontend/src/stores/auth.ts`
- Modify: `frontend/tests/msw/server.ts`
- Test: `frontend/tests/stores/auth.test.ts`

**Interfaces:**
- Consumes: `accountApi.getThemePreference` (Task 2), `setTheme` from `@/theme` (Task 1).
- Produces: `syncThemePreference()` called from `login()` and `fetchMe()` next to the existing `syncLanguagePreference()` calls. No exported API change.

The msw default server needs a theme handler because `onUnhandledRequest: 'error'` is set in `tests/setup.ts` and every existing login/fetchMe test will now hit the theme endpoint too.

- [ ] **Step 1: Write the failing tests**

Append to `frontend/tests/stores/auth.test.ts` inside the existing `describe('auth store')`:

```ts
  it('login applies the persisted theme preference', async () => {
    server.use(
      http.post('/api/auth/login', () => HttpResponse.json({ status: 'AUTHENTICATED', email: 'p@example.com', roles: ['PATIENT'], challengeId: null, methods: null })),
      http.get('/api/account/preferences/theme', () => HttpResponse.json({ theme: 'DARK' })),
    )
    const { THEME_STORAGE_KEY } = await import('@/theme')
    const auth = useAuthStore()
    await auth.login('p@example.com', 'password-123')
    expect(localStorage.getItem(THEME_STORAGE_KEY)).toBe('DARK')
    expect(document.documentElement.classList.contains('dark')).toBe(true)
  })

  it('login still succeeds when the theme preference fetch fails', async () => {
    server.use(
      http.post('/api/auth/login', () => HttpResponse.json({ status: 'AUTHENTICATED', email: 'p@example.com', roles: ['PATIENT'], challengeId: null, methods: null })),
      http.get('/api/account/preferences/theme', () => new HttpResponse(null, { status: 500 })),
    )
    const { THEME_STORAGE_KEY } = await import('@/theme')
    const auth = useAuthStore()
    const res = await auth.login('p@example.com', 'password-123')
    expect(res.status).toBe('AUTHENTICATED')
    expect(auth.status).toBe('authenticated')
    expect(localStorage.getItem(THEME_STORAGE_KEY)).toBeNull()
  })
```

Also add `matchMedia` stubbing at the top of the file (jsdom lacks it), right after the imports:

```ts
Object.defineProperty(window, 'matchMedia', {
  writable: true,
  configurable: true,
  value: (query: string) => ({
    matches: false,
    media: query,
    addEventListener: () => undefined,
    removeEventListener: () => undefined,
  }),
})
```

And extend the existing `beforeEach` to reset the theme side effects:

```ts
  beforeEach(() => {
    setActivePinia(createPinia())
    localStorage.clear()
    i18n.global.locale.value = 'en'
    document.documentElement.classList.remove('dark')
  })
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd frontend && npx vitest run tests/stores/auth.test.ts`
Expected: the two new tests FAIL (`localStorage.getItem(THEME_STORAGE_KEY)` is null in the first). Note: the first new test will fail with an msw unhandled-request error for `/api/account/preferences/theme` until Step 3 — that is the expected failure mode.

- [ ] **Step 3: Implement the sync**

In `frontend/tests/msw/server.ts`, add a default theme handler:

```ts
// Default preferences so auth flows (login/fetchMe) can sync locale/theme without each test stubbing them.
export const server = setupServer(
  http.get('/api/account/preferences/language', () => HttpResponse.json({ language: 'EN' })),
  http.get('/api/account/preferences/theme', () => HttpResponse.json({ theme: 'SYSTEM' })),
)
```

In `frontend/src/stores/auth.ts`, add the import and the sync function, and call it next to `syncLanguagePreference()` in both `fetchMe()` and `login()`:

```ts
import { setLocale } from '@/i18n'
import { setTheme } from '@/theme'
```

```ts
  /** Best-effort sync of the persisted theme preference; failures never break auth flows. */
  async function syncThemePreference(): Promise<void> {
    try {
      const pref = await accountApi.getThemePreference()
      setTheme(pref.theme)
    } catch {
      // Keep the current theme when the preference cannot be fetched.
    }
  }
```

In `fetchMe()`: after `await syncLanguagePreference()` add `await syncThemePreference()`.
In `login()`: after `await syncLanguagePreference()` add `await syncThemePreference()`.

- [ ] **Step 4: Run the full auth store suite**

Run: `cd frontend && npx vitest run tests/stores/auth.test.ts`
Expected: PASS (all tests, old and new)

- [ ] **Step 5: Commit**

```bash
git add frontend/src/stores/auth.ts frontend/tests/msw/server.ts frontend/tests/stores/auth.test.ts
git commit -m "Sync theme preference on SPA login and session restore"
```

---

### Task 4: Theme selector in AppShell + i18n keys

**Files:**
- Modify: `frontend/src/components/AppShell.vue`
- Modify: `frontend/src/i18n/en.json`
- Modify: `frontend/src/i18n/cs.json`
- Test: `frontend/tests/components/AppShell.test.ts`

**Interfaces:**
- Consumes: `setTheme`, `currentTheme`, `ThemePreference` from `@/theme` (Task 1); `accountApi.updateThemePreference` (Task 2).
- Produces: i18n keys `theme.label`, `theme.system`, `theme.light`, `theme.dark` in both bundles.

- [ ] **Step 1: Write the failing test**

Append to `frontend/tests/components/AppShell.test.ts` inside `describe('AppShell')`. Add `matchMedia` stubbing at the top of the file (after imports), same helper as Task 3:

```ts
Object.defineProperty(window, 'matchMedia', {
  writable: true,
  configurable: true,
  value: (query: string) => ({
    matches: false,
    media: query,
    addEventListener: () => undefined,
    removeEventListener: () => undefined,
  }),
})
```

New test:

```ts
  it('persists the chosen theme through the account API', async () => {
    let putBody: unknown
    server.use(
      http.get('/api/csrf', () => HttpResponse.json({ token: 't', headerName: 'X-XSRF-TOKEN' })),
      http.put('/api/account/preferences/theme', async ({ request }) => {
        putBody = await request.json()
        return HttpResponse.json({ status: 'ok' })
      }),
    )
    const router = makeRouter()
    await router.push('/')
    const wrapper = mount(AppShell, { global: { plugins: [createPinia(), makeI18n(), router] } })
    await wrapper.find('select[aria-label="Theme"]').setValue('DARK')
    await flushPromises()
    expect(putBody).toEqual({ theme: 'DARK' })
    expect(localStorage.getItem('metabion.theme')).toBe('DARK')
    expect(document.documentElement.classList.contains('dark')).toBe(true)
  })
```

Also extend the existing `beforeEach` in this file with `document.documentElement.classList.remove('dark')`.

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && npx vitest run tests/components/AppShell.test.ts`
Expected: FAIL — `select[aria-label="Theme"]` not found.

- [ ] **Step 3: Implement the selector and i18n keys**

In `frontend/src/i18n/en.json`, add after the `"app"` block:

```json
  "theme": { "label": "Theme", "system": "System", "light": "Light", "dark": "Dark" },
```

In `frontend/src/i18n/cs.json`, add after the `"app"` block:

```json
  "theme": { "label": "Vzhled", "system": "Podle systému", "light": "Světlý", "dark": "Tmavý" },
```

In `frontend/src/components/AppShell.vue`:

Script — add imports and state:

```ts
import { computed, ref } from 'vue'
import { setTheme, currentTheme, type ThemePreference } from '@/theme'
```

```ts
const theme = ref<ThemePreference>(currentTheme())

async function switchTheme() {
  setTheme(theme.value)
  try {
    await accountApi.updateThemePreference(theme.value)
  } catch {
    // Preference persistence is best-effort; the local choice still applies.
  }
}
```

Template — add this select immediately after the existing locale select:

```html
        <select v-model="theme" :aria-label="t('theme.label')"
                class="rounded border border-gray-300 px-2 py-1 text-sm"
                @change="switchTheme">
          <option value="SYSTEM">{{ t('theme.system') }}</option>
          <option value="LIGHT">{{ t('theme.light') }}</option>
          <option value="DARK">{{ t('theme.dark') }}</option>
        </select>
```

(The dark styling of the shell itself, including this select, is Task 7.)

- [ ] **Step 4: Run test to verify it passes**

Run: `cd frontend && npx vitest run tests/components/AppShell.test.ts`
Expected: PASS (both tests)

- [ ] **Step 5: Commit**

```bash
git add frontend/src/components/AppShell.vue frontend/src/i18n/en.json frontend/src/i18n/cs.json frontend/tests/components/AppShell.test.ts
git commit -m "Add theme selector to SPA shell"
```

---

### Task 5: Dark-aware chart colors in LineChart

**Files:**
- Modify: `frontend/src/components/LineChart.vue`
- Test: `frontend/tests/components/LineChart.test.ts`

**Interfaces:**
- Consumes: `isDark: Ref<boolean>` from `@/theme` (Task 1).
- Produces: `chartOptions` becomes a computed that adapts tick/grid/legend colors to the active theme. Chart dataset colors (`#2563eb`, `#dc2626`, `#059669`) are readable on both themes and stay unchanged.

- [ ] **Step 1: Write the failing test**

Append to `frontend/tests/components/LineChart.test.ts`:

```ts
import { isDark } from '@/theme'
```

```ts
  it('adapts grid and tick colors to the dark theme', () => {
    isDark.value = true
    const wrapper = mount(LineChart, {
      props: {
        labels: ['2026-07-01'],
        datasets: [{ label: 'Symptom score', data: [3] }],
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
    const options = wrapper.findComponent({ name: 'Line' }).props('options')
    expect(options.scales.y.ticks.color).toBe('#d1d5db')
    expect(options.scales.y.grid.color).toBe('#374151')
  })
```

Reset `isDark.value = false` in a `beforeEach` (or after the test) so it does not leak into the first test.

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && npx vitest run tests/components/LineChart.test.ts`
Expected: FAIL — `options.scales.y.ticks` is undefined.

- [ ] **Step 3: Implement**

In `frontend/src/components/LineChart.vue`, replace the static `chartOptions` with:

```ts
import { isDark } from '@/theme'

const chartOptions = computed(() => {
  const text = isDark.value ? '#d1d5db' : '#4b5563'
  const grid = isDark.value ? '#374151' : '#e5e7eb'
  return {
    responsive: true,
    maintainAspectRatio: false,
    scales: {
      x: { ticks: { color: text }, grid: { color: grid } },
      y: { beginAtZero: true, ticks: { color: text }, grid: { color: grid } },
    },
    plugins: { legend: { labels: { color: text } } },
  }
})
```

(`computed` is already imported in this file.)

- [ ] **Step 4: Run test to verify it passes**

Run: `cd frontend && npx vitest run tests/components/LineChart.test.ts`
Expected: PASS (2 tests)

- [ ] **Step 5: Commit**

```bash
git add frontend/src/components/LineChart.vue frontend/tests/components/LineChart.test.ts
git commit -m "Adapt SPA line chart colors to the active theme"
```

---

### Task 6: Enable class-based dark mode in Tailwind

**Files:**
- Modify: `frontend/src/style.css`

**Interfaces:**
- Consumes: the `dark` class toggled on `<html>` by `theme.ts` (Task 1).
- Produces: the `dark:` variant for Tasks 7–9; base page background/text colors for light and dark.

- [ ] **Step 1: Update `frontend/src/style.css`**

Full new content:

```css
@import "tailwindcss";

@custom-variant dark (&:where(.dark, .dark *));

@layer base {
  html {
    @apply bg-white text-gray-900;
  }
  html.dark {
    @apply bg-gray-900 text-gray-100;
  }
}
```

- [ ] **Step 2: Verify the build compiles the variant**

Run: `cd frontend && npm run build`
Expected: build succeeds. Then confirm the variant was generated:
Run: `grep -c 'dark' frontend/dist/assets/*.css | head -1`
Expected: a non-zero count once Task 7+ classes exist; for now the build succeeding is the gate (the `@custom-variant` line compiles without error).

- [ ] **Step 3: Commit**

```bash
git add frontend/src/style.css
git commit -m "Enable class-based dark mode in the SPA"
```

---

### Task 7: Dark pass — shell and auth views

**Files:**
- Modify: `frontend/src/components/AppShell.vue`
- Modify: `frontend/src/views/LoginView.vue`
- Modify: `frontend/src/views/RegisterView.vue`
- Modify: `frontend/src/views/ForgotPasswordView.vue`
- Modify: `frontend/src/views/ResetPasswordView.vue`
- Modify: `frontend/src/views/VerifyEmailView.vue`
- Modify: `frontend/src/views/StaffNoticeView.vue`
- Modify: `frontend/src/views/NotFoundView.vue`

**Interfaces:**
- Consumes: the class mapping table at the top of this plan and the `dark:` variant (Task 6).
- Produces: dark-correct chrome and unauthenticated pages.

- [ ] **Step 1: Apply the mapping**

In each listed file, apply the "Dark-mode class mapping" table to every matching class. In `AppShell.vue` specifically:

- Root: `<div class="min-h-screen bg-gray-50 dark:bg-gray-900">`
- Header: `<header class="border-b bg-white dark:border-gray-700 dark:bg-gray-800">`
- Nav links: `class="text-gray-700 hover:text-blue-700 dark:text-gray-300 dark:hover:text-blue-300"`
- Active classes: `:active-class="... 'font-semibold text-blue-700 dark:text-blue-300'"` and `exact-active-class="font-semibold text-blue-700 dark:text-blue-300"`
- Both selects (locale + theme): `class="rounded border border-gray-300 px-2 py-1 text-sm dark:border-gray-600 dark:bg-gray-800"`
- Logout button: `class="text-sm text-gray-700 hover:text-blue-700 dark:text-gray-300 dark:hover:text-blue-300"`

In the auth views, apply the mapping to form inputs (`border-gray-300` → add `dark:border-gray-600 dark:bg-gray-800`), error banners (`bg-red-50`/`text-red-700`), success/info banners (`bg-green-50`/`text-green-700`), links (`text-blue-600`), and any `bg-white`/`bg-gray-50` page wrappers.

- [ ] **Step 2: Verify no unmigrated light classes remain in these files**

Run: `cd frontend && grep -nE 'class="[^"]*(bg-white|bg-gray-50|border-gray-300|bg-red-50|bg-green-50|bg-amber-50)[^"]*"' src/components/AppShell.vue src/views/{Login,Register,ForgotPassword,ResetPassword,VerifyEmail,StaffNotice,NotFound}View.vue | grep -v 'dark:' || echo OK`
Expected: `OK` (every occurrence of those classes on the same element now has a `dark:` companion).

- [ ] **Step 3: Run typecheck and tests**

Run: `cd frontend && npm run typecheck && npm run test`
Expected: PASS (class changes must not break existing tests)

- [ ] **Step 4: Commit**

```bash
git add frontend/src/components/AppShell.vue frontend/src/views/
git commit -m "Add dark mode styles to SPA shell and auth views"
```

---

### Task 8: Dark pass — tracking views (dashboard, diet logs, check-ins, trends, labs)

**Files:**
- Modify: `frontend/src/views/DashboardView.vue`
- Modify: `frontend/src/views/DietLogEditView.vue`
- Modify: `frontend/src/views/DietLogHistoryView.vue`
- Modify: `frontend/src/views/CheckInEditView.vue`
- Modify: `frontend/src/views/CheckInListView.vue`
- Modify: `frontend/src/views/TrendsView.vue`
- Modify: `frontend/src/views/LabTrendsView.vue`
- Modify: `frontend/src/views/LabResultSetsView.vue`
- Modify: `frontend/src/views/LabResultSetEditView.vue`

**Interfaces:** same as Task 7.

- [ ] **Step 1: Apply the mapping**

Apply the "Dark-mode class mapping" table to every matching class in each listed file: cards and panels (`bg-white`), tables (header rows, `border-gray-300` cells, any `divide-gray-*`/`hover:bg-gray-*` rows), inputs/selects, banners, links, and muted text (`text-gray-500/600/700`).

- [ ] **Step 2: Verify no unmigrated light classes remain in these files**

Run: `cd frontend && grep -nE 'class="[^"]*(bg-white|bg-gray-50|bg-gray-100|border-gray-300|border-gray-200|bg-red-50|bg-green-50|bg-amber-50|bg-green-100)[^"]*"' src/views/{Dashboard,DietLogEdit,DietLogHistory,CheckInEdit,CheckInList,Trends,LabTrends,LabResultSets,LabResultSetEdit}View.vue | grep -v 'dark:' || echo OK`
Expected: `OK`

- [ ] **Step 3: Run typecheck and tests**

Run: `cd frontend && npm run typecheck && npm run test`
Expected: PASS

- [ ] **Step 4: Commit**

```bash
git add frontend/src/views/
git commit -m "Add dark mode styles to SPA tracking views"
```

---

### Task 9: Dark pass — remaining views and shared components

**Files:**
- Modify: `frontend/src/views/OnboardingView.vue`
- Modify: `frontend/src/views/EducationListView.vue`
- Modify: `frontend/src/views/EducationModuleView.vue`
- Modify: `frontend/src/views/AccountProfileView.vue`
- Modify: `frontend/src/views/AccessTokensView.vue`
- Modify: `frontend/src/components/FieldError.vue`
- Modify: `frontend/src/components/PhotoUpload.vue`

**Interfaces:** same as Task 7.

- [ ] **Step 1: Apply the mapping**

Apply the "Dark-mode class mapping" table to every matching class in each listed file, including modal overlays (keep `bg-black/40`, migrate the panel `bg-white`), token list rows, form inputs, and banners.

- [ ] **Step 2: Verify no unmigrated light classes remain anywhere**

Run: `cd frontend && grep -rnE 'class="[^"]*(bg-white|bg-gray-50|bg-gray-100|border-gray-300|border-gray-200|bg-red-50|bg-green-50|bg-amber-50|bg-green-100)[^"]*"' src/ | grep -v 'dark:' || echo OK`
Expected: `OK`

- [ ] **Step 3: Run the full verification**

Run: `cd frontend && npm run typecheck && npm run test && npm run build`
Expected: all PASS; build succeeds.

- [ ] **Step 4: Commit**

```bash
git add frontend/src/views/ frontend/src/components/
git commit -m "Add dark mode styles to remaining SPA views and components"
```

---

## Self-Review Notes

- Spec coverage: theme module (Task 1), API (2), auth sync (3), selector + i18n (4), charts (5), Tailwind variant (6), full dark pass (7–9), tests in every task. Data flow, error handling, and out-of-scope items match the spec.
- Type consistency: `ThemePreference`, `THEME_STORAGE_KEY`, `initTheme`, `setTheme`, `currentTheme`, `isDark`, `getThemePreference`, `updateThemePreference` are spelled identically across tasks.
- Known test-env caveat recorded in Task 1: jsdom lacks `matchMedia`; every test file touching theme stubs it.
