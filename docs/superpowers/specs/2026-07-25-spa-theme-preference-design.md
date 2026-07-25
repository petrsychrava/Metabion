# SPA Theme Preference (Light/Dark/System) — Design

Date: 2026-07-25
Status: Approved (design phase)

## Goal

Expose the existing backend theme preference API in the patient-facing SPA
(`frontend/`, Vue 3 + TS + Vite + Tailwind v4): a Light/Dark/System selector
that applies instantly, persists to the backend, and drives a complete
dark-mode styling of the whole SPA.

## Backend contract (already implemented)

- `GET /api/account/preferences/theme` → `{ "theme": "SYSTEM" | "LIGHT" | "DARK" }`
- `PUT /api/account/preferences/theme` with body `{ "theme": "<value>" }` → `{ "status": "ok" }`
- Enum: `com.metabion.domain.ThemePreference` (`SYSTEM`, `LIGHT`, `DARK`)
- Controller: `src/main/java/com/metabion/controller/api/AccountController.java`

No backend changes are part of this work.

## Approach

Mirror the existing language-preference pattern already used in the SPA
(`i18n/index.ts` + `AppShell.vue` language selector + `stores/auth.ts`
`syncLanguagePreference`). Alternatives considered and rejected:

- A shared Pinia "preferences" store for language + theme — more refactoring
  than this feature needs.
- Local-only theme without backend sync — ignores the new API endpoint.

## Components

### 1. `frontend/src/theme.ts` (new module)

- `export type ThemePreference = 'SYSTEM' | 'LIGHT' | 'DARK'`
- `export const THEME_STORAGE_KEY = 'metabion.theme'`
- `initTheme()`: read localStorage (default `SYSTEM`), apply immediately.
- `setTheme(pref)`: persist to localStorage and apply.
- Internal `applyTheme()`: resolve `SYSTEM` via
  `window.matchMedia('(prefers-color-scheme: dark)')`, toggle the `dark`
  class on `document.documentElement`, and subscribe to media-query changes
  so `SYSTEM` follows the OS live.
- Called from `main.ts` before `app.mount()` (alongside `initLocale()`) so
  there is no theme flash on load.

### 2. `frontend/src/api/account.ts`

Add:

- `getThemePreference: () => apiFetch<{ theme: ThemePreference }>('/api/account/preferences/theme')`
- `updateThemePreference: (theme: ThemePreference) => apiFetch<{ status: string }>('/api/account/preferences/theme', { method: 'PUT', body: { theme } })`

The `ThemePreference` type lives in `frontend/src/theme.ts`, mirroring how
`AppLocale` lives in `i18n/index.ts`.

### 3. `frontend/src/stores/auth.ts`

Add `syncThemePreference()` next to `syncLanguagePreference()`: fetch the
preference and `setTheme()` it, best-effort (catch and keep current theme on
failure). Called from `login()` and `fetchMe()` exactly where the language
sync is called.

### 4. `frontend/src/components/AppShell.vue`

Add a theme `<select>` (System / Light / Dark, labels via i18n) next to the
existing language selector in the header. On change:

1. `setTheme(next)` — applies instantly and stores locally.
2. `await accountApi.updateThemePreference(next)` — best-effort, failure
   ignored (same comment style as the language handler).

### 5. `frontend/src/style.css`

Enable class-based dark mode for Tailwind v4:

```css
@import "tailwindcss";

@custom-variant dark (&:where(.dark, .dark *));
```

so `dark:` utilities follow the `<html class="dark">` toggle instead of only
the OS media query.

### 6. Dark pass over all views and components

Add `dark:` variants across every view in `frontend/src/views/`, all
components in `frontend/src/components/`, and `App.vue`: backgrounds
(`bg-gray-50` → `dark:bg-gray-900` etc.), surfaces (`bg-white` →
`dark:bg-gray-800`), text (`text-gray-700/900` → `dark:text-gray-200/100`),
borders, inputs, buttons, and error/success banners (`bg-red-50` →
`dark:bg-red-950` + readable text, etc.). Chart colors in `LineChart.vue`
(and any hardcoded chart colors in trend views) must adapt too — read the
resolved theme (or a reactive flag from `theme.ts`) when building Chart.js
dataset/grid colors.

This is a mechanical but broad pass (~20 files); each file gets the minimal
set of `dark:` classes to look correct in dark mode.

### 7. i18n

Add keys to `frontend/src/i18n/en.json` and `cs.json`, mirroring the backend
message keys (`messages.properties` / `messages_cs.properties`):

- `theme.label` (EN "Theme", CS "Vzhled")
- `theme.system` (EN "System", CS "Podle systému")
- `theme.light` (EN "Light", CS "Světlý")
- `theme.dark` (EN "Dark", CS "Tmavý")

### 8. Tests (vitest + msw, under `frontend/tests/`)

Following the existing test layout:

- `theme.ts` unit tests: init from empty localStorage defaults to SYSTEM and
  follows media query; init from stored value applies it; `setTheme`
  persists and toggles the `dark` class; SYSTEM re-resolves on media-query
  change.
- `api/account` test: new endpoints called with correct method/body (msw).
- `AppShell` test: selector renders with current theme, change applies theme
  and calls the PUT endpoint.
- Auth store test: `syncThemePreference` applies fetched preference on
  login/fetchMe and swallows failures.

## Data flow

- App start: `initTheme()` applies localStorage/system theme before mount.
- Login / session restore (`fetchMe`): `GET` preference → `setTheme()` →
  apply + store.
- Selector change: apply instantly → `PUT` best-effort.
- Logged-out pages: keep localStorage/system theme (no fetch).

## Error handling

- All backend sync is best-effort: fetch or update failures never break auth
  flows or the selector; the local choice still applies.
- Invalid localStorage values fall back to `SYSTEM`.

## Out of scope

- Backend changes (endpoint already exists).
- Theme selector on logged-out pages (login/register) — header selector lives
  in `AppShell`, which wraps authenticated routes.
- Thymeleaf web app (already has theme support).
