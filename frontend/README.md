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
