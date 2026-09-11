# Clinical SPA Content Management — Design

Date: 2026-09-11
Status: Approved design (brainstorming complete, pending implementation plan)

## Context

Education content management (authoring, review, approval, publishing of versioned,
localized modules and lessons) is available only in the Thymeleaf workspace at
`/app/content/education`. The clinical SPA (`/clinical`, roles `NUTRITION_SPECIALIST`,
`PHYSICIAN`, `ADMIN`) has no content management: its education routes reuse the read-only
patient views.

The backend already exposes a staff-facing REST API under `/api/content/education/**`
(`EducationContentController`, `EducationContentService`), but the SPA uses none of it and
the API has gaps for a full management UI: no management-detail GET, no full-form update,
and management DTOs carry EN content only (no Czech localization to round-trip).

## Decisions

| Question | Decision |
|---|---|
| Scope | Full parity with the Thymeleaf management UI: list, create module, edit draft content, full lifecycle (submit-review, approve/reject, publish), copy new version |
| Thymeleaf UI | Keep unchanged; coordinators keep managing content there. No Thymeleaf changes in this work |
| Editing model | Full-form editor: load whole draft, edit module fields + all lessons (EN/CS), single save per draft |
| Markdown preview | Server-side Edit/Preview tabs via a new preview endpoint using the existing `EducationMarkdownService` — identical to patient rendering |
| SPA structure | Dedicated management section under `/clinical` with its own routes, nav item, API client, and types; patient education views untouched |

## Backend Changes

All changes are in `EducationContentController` (+ new DTO records under `dto/`). The
service layer (`EducationContentService`) already supports every operation; no service
logic changes beyond what detail assembly requires. No `SecurityConfig` changes: all
endpoints stay session-authenticated under `/api/**` with the existing service-level
`requireContentManager()` check (`NUTRITION_SPECIALIST`, `PHYSICIAN`, `COORDINATOR`,
`ADMIN`). No database migration.

Existing endpoints (list, create module, per-lesson upsert, submit-review, approve,
reject, publish, copy) stay as-is.

### New endpoints

1. `GET /api/content/education/modules/{moduleSlug}/versions/{version}`
   Management detail. Returns full metadata (status, author/reviewedBy/publishedBy with
   user ids and display names, timestamps, review notes) and all lessons with **both EN and CS
   localizations** (titles, summaries, body markdown) — today's management DTOs are
   EN-only and cannot round-trip through an editor. New DTO records, e.g.
   `EducationManagedVersionDetail`. 404 when the version does not exist; 403 for
   non-content-managers.

2. `PUT /api/content/education/modules/{moduleSlug}/versions/{version}`
   Full-form draft update. Request body reuses the existing `EducationContentForm`
   (module topic/sortOrder, EN title/summary required, CS optional, complete lesson set
   with per-lesson slug/sortOrder/EN/CS title/summary/bodyMarkdown, uniqueness and
   completeness validation already on the form). Delegates to the existing
   `updateDraft`, which enforces `isEditable()` (DRAFT/REJECTED only, 403 otherwise)
   and validates localization consistency. The module slug is read-only: the path
   value wins; a body slug mismatching the path is a 400.

3. `POST /api/content/education/markdown-preview`
   Body `{ "markdown": "..." }`, returns `{ "html": "..." }` rendered by the existing
   `EducationMarkdownService` so SPA preview is byte-identical to patient rendering.
   Content-manager roles only. Input length-capped (same 20000 limit as lesson bodies).

### Business rules (unchanged, server-side authoritative)

- Lifecycle transitions live in `EducationModuleVersion`: submit-review (DRAFT/REJECTED),
  approve/reject (IN_REVIEW), publish (APPROVED), copy (any status, new draft at
  maxVersion+1). Publishing archives the previously published version automatically.
- Author cannot approve own content unless the author is ADMIN.
- `validatePublishable` requires ≥1 lesson, an EN module localization, and an EN
  localization on every lesson.
- The SPA computes the author-approve rule and publishability client-side for button
  state only; the server remains the backstop (403 / error responses).

## Frontend Changes

New code lives in `frontend/src`; patient education views and the patient education API
client are untouched.

### Routes (children of `/clinical`, inheriting the existing `CLINICAL_ROLES` guard)

- `/clinical/content` — `ContentListView`
- `/clinical/content/new` — `ContentNewView`
- `/clinical/content/:moduleSlug/:version` — `ContentDetailView`
- `/clinical/content/:moduleSlug/:version/edit` — `ContentEditView`

`ClinicalShell` gains a "Content" nav item. The existing patient-derived
`/clinical/education` routes (read-only published catalog) stay.

### API client and types

- `frontend/src/api/contentEducation.ts` — mirrors `api/education.ts` conventions via
  the `apiFetch` wrapper: `listModules`, `createModule`, `getVersion`, `updateVersion`,
  `submitReview`, `approve`, `reject`, `publish`, `copy`, `previewMarkdown`.
- Management types added to `types/api.ts` (managed version detail/summary, lesson form
  rows, preview response).

### Views (local `ref` state, no new Pinia store — same convention as other views)

- `ContentListView` — table of all module versions: module slug/topic, version, status
  badge, author, last-updated. Client-side filters (module, status). Row → detail.
  Header action "New module"; per-row "New version" (copy) for every version, matching
  the detail view's always-available copy.
- `ContentDetailView` — metadata card (author, reviewer, publisher, timestamps, review
  notes), lifecycle action bar conditioned on status **and** current user
  (submit-review for DRAFT/REJECTED; approve/reject for IN_REVIEW, hidden for own
  content unless ADMIN; publish for APPROVED, disabled with reason tooltip when not
  publishable; copy always), rendered lesson previews (server HTML, same trust model as
  the patient view), "Edit" button when editable.
- `ContentEditView` — full-form editor: module fields (topic, sortOrder, EN/CS
  title/summary) and a lesson list with add/remove/reorder (sortOrder), each lesson with
  EN/CS title/summary + markdown textarea with Edit/Preview tabs (preview via the new
  endpoint) and a short markdown syntax hint. Single "Save" (PUT); cancel returns to
  detail. Dirty-state guard on route leave.
- `ContentNewView` — slug/topic/sortOrder + EN title/summary (+ optional CS), then
  routes straight into the editor.

Management screens are language-agnostic (editors work on both locales at once), so
unlike the patient views they do **not** refetch on locale change.

### i18n

New `clinical.content.*` block in both `frontend/src/i18n/en.json` and `cs.json`:
status names, action labels, filter labels, editor strings (incl. preview tab and
syntax hint), publish-disabled reason, race/banner messages. Keys kept aligned across
both files.

## Error Handling

Reuses existing SPA conventions: `useApiError` maps `ApiError` codes to i18n strings;
`FieldError` renders per-field messages from the 400 `validation_failed` field map
(bean-validation errors on create/update/preview payloads).

Feature-specific behaviors:

- **Lifecycle races** (detail shows a state another manager just changed): transition
  call returns an error → banner shows the generic message and the detail auto-refreshes
  to the new state instead of leaving stale action buttons.
- **Publish pre-check** is client-side convenience (button disabled + reason tooltip);
  server `validatePublishable` remains authoritative and its failure renders as a
  banner.
- **Editor save conflicts**: saving a draft that left the editable state meanwhile
  returns 403; the banner offers "Reload" to fetch current state.

## Testing

- **Backend** (`src/test/java/com/metabion/`): extend `EducationContentControllerTest` —
  detail GET (EN+CS localizations present, 404, non-manager 403), PUT full-form (happy
  path, REJECTED re-edit, IN_REVIEW 403, validation field errors, lesson replace
  semantics, slug mismatch 400), markdown-preview (rendered HTML matches
  `EducationMarkdownService`, role check, length validation). Service lifecycle
  coverage already exists (`EducationContentServiceLifecycleTest`); add service tests
  only if detail assembly introduces new logic.
- **Frontend** (`frontend/tests/`): Vitest + MSW view tests mirroring
  `frontend/tests/views/Education*.test.ts` — list rendering/filters, detail action
  visibility per status and role (including author-cannot-approve), editor save payload
  shape and preview tab, create flow, race-refresh behavior. Run `npm run test`,
  `npm run typecheck`, `npm run build` from `frontend/`.
- **Full suite**: `./gradlew test` for backend changes.

## Out of Scope

- Coordinator access to the clinical SPA (coordinators keep using the Thymeleaf UI).
- Any changes to the Thymeleaf management UI or its controllers/templates.
- Education `ARCHIVED` status / archive action — the entity supports it but no UI or
  REST endpoint exposes it today; adding it is a separate decision.
- MCP clinician education tools.
- Lesson media/images (markdown links remain external URLs only).
- Education assignment (does not exist anywhere in the codebase).
