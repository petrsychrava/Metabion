# Clinical SPA Content Management Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Bring full education content management (list, create, edit drafts, lifecycle actions, markdown preview) into the clinical SPA at `/clinical/content`, reusing the existing `EducationContentService` backend.

**Architecture:** Three new/four new REST endpoints in `EducationContentController` delegate to existing service methods (`getManagedVersion`, `getManagedVersionForm`, `updateDraft`, plus a new thin `previewMarkdown`). The SPA gets a dedicated API client, four views under the existing `ClinicalShell`, and i18n keys in both locales. The Thymeleaf UI, patient views, and security config are untouched.

**Tech Stack:** Spring Boot 4.0.6 / Java 25 (records, `@Valid` Jakarta validation, MockMvc + Mockito tests), Vue 3 + TypeScript + Vite + Pinia + Vue I18n + Tailwind, Vitest + MSW.

**Spec:** `docs/superpowers/specs/2026-09-11-clinical-spa-content-management-design.md` (approved).

**Spec refinements (deliberate, intent-preserving):**
- The editor loads EN+CS content from a dedicated `GET .../form` endpoint returning the existing `EducationContentForm` instead of extending the detail DTO with Czech localizations — `EducationContentService.getManagedVersionForm` already assembles exactly this payload, so no new assembly code and no change to the existing management response shape. The detail endpoint stays EN-only with rendered previews, which is all the detail page needs.
- The client-side author-approval rule compares `authorEmail` with the auth store's email rather than adding author ids to the DTO — the SPA auth model is email-based (no user id is exposed to the client) and emails are normalized server-side.

## Global Constraints

- Do NOT modify `SecurityConfig`; endpoints stay session-authenticated under `/api/**` with the service-level `requireContentManager()` role check (`NUTRITION_SPECIALIST`, `PHYSICIAN`, `COORDINATOR`, `ADMIN`).
- No new dependencies (backend or frontend).
- No database migration; no domain entity changes.
- Java: 4-space indent, records for DTOs, constructor injection, standard conventions.
- SPA: `<script setup lang="ts">`, `apiFetch` for HTTP, `useApiError` for errors, `data-testid` attributes on interactive elements, Tailwind utility classes.
- i18n: every key added to `frontend/src/i18n/en.json` must also be added to `frontend/src/i18n/cs.json` with identical shape — enforced by `frontend/tests/i18n/locale.test.ts` parity test.
- Slug and version number are immutable; a PUT body whose `slug` does not normalize to the path slug is a 400.
- `requireEditable()` throws **400** (not 403) for non-editable versions — match this in tests.
- Commit style: concise imperative ("Add clinical content list view"), one logical change per commit.
- Verification: focused `./gradlew test --tests '...'` for backend tasks; `npm run test`, `npm run typecheck` from `frontend/` for frontend tasks.

---

### Task 1: Backend management REST endpoints

**Files:**
- Modify: `src/main/java/com/metabion/controller/api/EducationContentController.java`
- Modify: `src/main/java/com/metabion/service/EducationContentService.java`
- Create: `src/main/java/com/metabion/dto/EducationMarkdownPreviewRequest.java`
- Create: `src/main/java/com/metabion/dto/EducationMarkdownPreviewResponse.java`
- Test: `src/test/java/com/metabion/controller/api/EducationContentControllerTest.java`
- Test: `src/test/java/com/metabion/service/EducationContentServiceLifecycleTest.java`

**Interfaces:**
- Consumes: existing service methods `getManagedVersion(Authentication, String moduleSlug, int versionNumber)` → `EducationManagementDetailResponse`; `getManagedVersionForm(Authentication, String, int)` → `EducationContentForm`; `updateDraft(Authentication, String, int, EducationContentForm)` → `EducationManagementDetailResponse` (all present at lines 239/247/255 of `EducationContentService.java`).
- Produces (used by Task 2+):
  - `GET /api/content/education/modules/{moduleSlug}/versions/{version}` → `EducationManagementDetailResponse`
  - `GET /api/content/education/modules/{moduleSlug}/versions/{version}/form` → `EducationContentForm` (JSON: `{slug, topic, sortOrder, englishTitle, englishSummary, czechTitle, czechSummary, lessons: [{slug, sortOrder, englishTitle, englishSummary, englishBodyMarkdown, czechTitle, czechSummary, czechBodyMarkdown}]}`)
  - `PUT /api/content/education/modules/{moduleSlug}/versions/{version}` with `EducationContentForm` body → `EducationManagementDetailResponse`
  - `POST /api/content/education/markdown-preview` with `{"markdown": "..."}` (≤20000 chars) → `{"html": "..."}`

- [ ] **Step 1: Write the failing tests**

Append to `EducationContentControllerTest.java`. Add these imports: `com.metabion.dto.EducationContentForm`, `com.metabion.dto.EducationMarkdownPreviewRequest`, `static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get`, `static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put`.

```java
    @Test
    void staffCanGetManagedVersion() throws Exception {
        mvc.perform(get("/api/content/education/modules/ibd-basics/versions/2")
                        .with(user("physician@example.com").roles(RoleName.PHYSICIAN.name())))
                .andExpect(status().isOk());

        verify(educationContentService).getManagedVersion(any(), eq("ibd-basics"), eq(2));
    }

    @Test
    void staffCanGetManagedVersionForm() throws Exception {
        mvc.perform(get("/api/content/education/modules/ibd-basics/versions/2/form")
                        .with(user("physician@example.com").roles(RoleName.PHYSICIAN.name())))
                .andExpect(status().isOk());

        verify(educationContentService).getManagedVersionForm(any(), eq("ibd-basics"), eq(2));
    }

    @Test
    void staffCanUpdateDraftWithCsrf() throws Exception {
        var form = new EducationContentForm();
        form.setSlug("ibd-basics");
        form.setTopic("IBD");
        form.setSortOrder(10);
        form.setEnglishTitle("IBD Basics");
        form.setEnglishSummary("Overview.");

        mvc.perform(put("/api/content/education/modules/ibd-basics/versions/2")
                        .with(user("physician@example.com").roles(RoleName.PHYSICIAN.name()))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(form)))
                .andExpect(status().isOk());

        verify(educationContentService).updateDraft(any(), eq("ibd-basics"), eq(2), any(EducationContentForm.class));
    }

    @Test
    void staffCanPreviewMarkdownWithCsrf() throws Exception {
        mvc.perform(post("/api/content/education/markdown-preview")
                        .with(user("physician@example.com").roles(RoleName.PHYSICIAN.name()))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new EducationMarkdownPreviewRequest("# Hello"))))
                .andExpect(status().isOk());

        verify(educationContentService).previewMarkdown(any(), eq("# Hello"));
    }

    @Test
    void markdownPreviewRejectsOversizedInput() throws Exception {
        mvc.perform(post("/api/content/education/markdown-preview")
                        .with(user("physician@example.com").roles(RoleName.PHYSICIAN.name()))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new EducationMarkdownPreviewRequest("x".repeat(20001)))))
                .andExpect(status().isBadRequest());
    }
```

Append to `EducationContentServiceLifecycleTest.java`:

```java
    @Test
    void updateDraftRejectsSlugMismatchWithBadRequest() {
        var staff = user(1L, "staff@example.com", RoleName.NUTRITION_SPECIALIST);
        when(users.findByEmail("staff@example.com")).thenReturn(Optional.of(staff));
        var module = new EducationModule("ibd-basics", "IBD", 10);
        var version = new EducationModuleVersion(module, 1, staff);
        when(versions.findByModuleSlugAndVersion("ibd-basics", 1)).thenReturn(Optional.of(version));

        var form = new EducationContentForm();
        form.setSlug("other-module");
        form.setTopic("IBD");
        form.setEnglishTitle("Title");
        form.setEnglishSummary("Summary");

        assertThatThrownBy(() -> service.updateDraft(auth("staff@example.com"), "ibd-basics", 1, form))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests 'com.metabion.controller.api.EducationContentControllerTest' --tests 'com.metabion.service.EducationContentServiceLifecycleTest'`
Expected: FAIL — compilation errors: `EducationMarkdownPreviewRequest` cannot be found, controller has no `getManagedVersion`/`getManagedVersionForm`/`updateDraft`/`previewMarkdown` handlers, and (once those exist) the slug-mismatch test fails because no 400 is thrown.

- [ ] **Step 3: Implement the DTO records, controller endpoints, service method, and slug guard**

`src/main/java/com/metabion/dto/EducationMarkdownPreviewRequest.java`:

```java
package com.metabion.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record EducationMarkdownPreviewRequest(
        @NotNull @Size(max = 20000) String markdown
) {
}
```

`src/main/java/com/metabion/dto/EducationMarkdownPreviewResponse.java`:

```java
package com.metabion.dto;

public record EducationMarkdownPreviewResponse(String html) {
}
```

Additions to `EducationContentController.java` (plus imports for the two new DTOs, `EducationContentForm`, and `org.springframework.web.bind.annotation.PutMapping`):

```java
    @GetMapping("/api/content/education/modules/{moduleSlug}/versions/{version}")
    public EducationManagementDetailResponse getManagedVersion(@PathVariable String moduleSlug,
                                                               @PathVariable int version,
                                                               Authentication authentication) {
        return educationContentService.getManagedVersion(authentication, moduleSlug, version);
    }

    @GetMapping("/api/content/education/modules/{moduleSlug}/versions/{version}/form")
    public EducationContentForm getManagedVersionForm(@PathVariable String moduleSlug,
                                                      @PathVariable int version,
                                                      Authentication authentication) {
        return educationContentService.getManagedVersionForm(authentication, moduleSlug, version);
    }

    @PutMapping("/api/content/education/modules/{moduleSlug}/versions/{version}")
    public EducationManagementDetailResponse updateDraft(@PathVariable String moduleSlug,
                                                         @PathVariable int version,
                                                         @Valid @RequestBody EducationContentForm form,
                                                         Authentication authentication) {
        return educationContentService.updateDraft(authentication, moduleSlug, version, form);
    }

    @PostMapping("/api/content/education/markdown-preview")
    public EducationMarkdownPreviewResponse previewMarkdown(@Valid @RequestBody EducationMarkdownPreviewRequest request,
                                                            Authentication authentication) {
        return educationContentService.previewMarkdown(authentication, request.markdown());
    }
```

In `EducationContentService.java`: add the import for `EducationMarkdownPreviewResponse`, add this method after `updateDraft`:

```java
    public EducationMarkdownPreviewResponse previewMarkdown(Authentication authentication, String source) {
        var user = currentUser(authentication);
        requireContentManager(user);
        return new EducationMarkdownPreviewResponse(markdown.render(trim(source)));
    }
```

(`markdown.render` returns `""` for null/blank input; `trim` maps null to `""`, so no extra null handling is needed.)

In `EducationContentService.updateDraft`, insert the slug guard immediately after `requireEditable(version);`:

```java
        if (!version.getModule().getSlug().equals(normalizeSlug(form.getSlug()))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Module slug does not match the request path");
        }
```

(`normalizeSlug` lowercases/strips the body slug, so `"IBD Basics"` matches path `ibd-basics`; blank throws its own 400 "Slug is required", which is also correct here.)

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests 'com.metabion.controller.api.EducationContentControllerTest' --tests 'com.metabion.service.EducationContentServiceLifecycleTest'`
Expected: BUILD SUCCESSFUL (all tests pass).

- [ ] **Step 5: Run the full backend suite**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL. (Guards against regressions in the Thymeleaf controller and service tests that share these code paths.)

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/metabion/controller/api/EducationContentController.java \
        src/main/java/com/metabion/service/EducationContentService.java \
        src/main/java/com/metabion/dto/EducationMarkdownPreviewRequest.java \
        src/main/java/com/metabion/dto/EducationMarkdownPreviewResponse.java \
        src/test/java/com/metabion/controller/api/EducationContentControllerTest.java \
        src/test/java/com/metabion/service/EducationContentServiceLifecycleTest.java
git commit -m "Add staff education content management REST endpoints"
```

---

### Task 2: Frontend management API client, types, and i18n keys

**Files:**
- Create: `frontend/src/api/contentEducation.ts`
- Modify: `frontend/src/types/api.ts` (append after the `// Education` block, line ~349)
- Modify: `frontend/src/i18n/en.json` (add `content` block inside `clinical`)
- Modify: `frontend/src/i18n/cs.json` (add identical-shape `content` block inside `clinical`)
- Test: `frontend/tests/api/contentEducation.test.ts`

**Interfaces:**
- Consumes: Task 1 endpoints.
- Produces (used by Tasks 3–6):
  - `contentEducationApi` with methods: `listVersions()`, `createModule(request)`, `getVersion(moduleSlug, version)`, `getVersionForm(moduleSlug, version)`, `updateVersion(moduleSlug, version, form)`, `submitReview(moduleSlug, version)`, `review(moduleSlug, version, decision: 'approve' | 'reject', notes)`, `publish(moduleSlug, version)`, `copyVersion(moduleSlug, version)`, `previewMarkdown(markdown)`.
  - Types: `EducationContentStatus`, `EducationManagementSummary`, `EducationManagementDetail` (lessons reuse existing `EducationLesson`), `EducationContentLessonRow`, `EducationContentFormData`, `EducationModuleCreateRequest`, `MarkdownPreviewResponse`.
  - i18n namespace `clinical.content.*` (full key set in Step 3).

- [ ] **Step 1: Write the failing test**

Create `frontend/tests/api/contentEducation.test.ts`:

```ts
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import { server } from '../msw/server'
import { contentEducationApi } from '@/api/contentEducation'

describe('contentEducationApi', () => {
  it('requests the version form for the editor', async () => {
    let seenUrl = ''
    server.use(
      http.get('/api/content/education/modules/ibd-basics/versions/2/form', ({ request }) => {
        seenUrl = request.url
        return HttpResponse.json({
          slug: 'ibd-basics', topic: 'IBD', sortOrder: 10,
          englishTitle: 'T', englishSummary: 'S', czechTitle: '', czechSummary: '', lessons: [],
        })
      }),
    )
    await contentEducationApi.getVersionForm('ibd-basics', 2)
    expect(seenUrl).toContain('/api/content/education/modules/ibd-basics/versions/2/form')
  })

  it('puts the full draft form', async () => {
    let received: unknown
    const form = {
      slug: 'ibd-basics', topic: 'IBD', sortOrder: 10,
      englishTitle: 'T', englishSummary: 'S', czechTitle: '', czechSummary: '',
      lessons: [{
        slug: 'intro', sortOrder: 10, englishTitle: 'I', englishSummary: 'IS', englishBodyMarkdown: 'B',
        czechTitle: '', czechSummary: '', czechBodyMarkdown: '',
      }],
    }
    server.use(
      http.get('/api/csrf', () => HttpResponse.json({ token: 't', headerName: 'X-XSRF-TOKEN' })),
      http.put('/api/content/education/modules/ibd-basics/versions/2', async ({ request }) => {
        received = await request.json()
        return HttpResponse.json({})
      }),
    )
    await contentEducationApi.updateVersion('ibd-basics', 2, form)
    expect(received).toEqual(form)
  })

  it('posts review decisions and markdown previews', async () => {
    let reviewUrl = ''
    let previewBody: unknown
    server.use(
      http.get('/api/csrf', () => HttpResponse.json({ token: 't', headerName: 'X-XSRF-TOKEN' })),
      http.post('/api/content/education/modules/ibd-basics/versions/2/approve', ({ request }) => {
        reviewUrl = request.url
        return HttpResponse.json({})
      }),
      http.post('/api/content/education/markdown-preview', async ({ request }) => {
        previewBody = await request.json()
        return HttpResponse.json({ html: '<h1>Hi</h1>' })
      }),
    )
    await contentEducationApi.review('ibd-basics', 2, 'approve', 'ok')
    expect(reviewUrl).toContain('/approve')
    const preview = await contentEducationApi.previewMarkdown('# Hi')
    expect(previewBody).toEqual({ markdown: '# Hi' })
    expect(preview.html).toBe('<h1>Hi</h1>')
  })
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && npm run test -- tests/api/contentEducation.test.ts`
Expected: FAIL — cannot resolve `@/api/contentEducation` (module does not exist).

- [ ] **Step 3: Implement the types, API client, and i18n keys**

Append to `frontend/src/types/api.ts` after the `EducationModuleDetail` block:

```ts
// Education content management (staff)
export type EducationContentStatus = 'DRAFT' | 'IN_REVIEW' | 'APPROVED' | 'PUBLISHED' | 'ARCHIVED' | 'REJECTED'

export interface EducationManagementSummary {
  moduleSlug: string
  topic: string
  version: number
  status: EducationContentStatus
  title: string | null
  authorEmail: string | null
  reviewedByEmail: string | null
  publishedByEmail: string | null
  createdAt: string
  submittedAt: string | null
  reviewedAt: string | null
  publishedAt: string | null
}

export interface EducationManagementDetail {
  moduleSlug: string
  topic: string
  sortOrder: number
  version: number
  status: EducationContentStatus
  reviewNotes: string | null
  reviewBypassed: boolean
  authorEmail: string | null
  reviewedByEmail: string | null
  publishedByEmail: string | null
  createdAt: string
  submittedAt: string | null
  reviewedAt: string | null
  publishedAt: string | null
  lessons: EducationLesson[]
}

export interface EducationContentLessonRow {
  slug: string
  sortOrder: number
  englishTitle: string
  englishSummary: string
  englishBodyMarkdown: string
  czechTitle: string
  czechSummary: string
  czechBodyMarkdown: string
}

export interface EducationContentFormData {
  slug: string
  topic: string
  sortOrder: number
  englishTitle: string
  englishSummary: string
  czechTitle: string
  czechSummary: string
  lessons: EducationContentLessonRow[]
}

export interface EducationModuleCreateRequest {
  slug: string
  topic: string
  sortOrder: number
  englishTitle: string
  englishSummary: string
  czechTitle: string | null
  czechSummary: string | null
}

export interface MarkdownPreviewResponse {
  html: string
}
```

Create `frontend/src/api/contentEducation.ts`:

```ts
import { apiFetch } from './http'
import type {
  EducationContentFormData,
  EducationManagementDetail,
  EducationManagementSummary,
  EducationModuleCreateRequest,
  MarkdownPreviewResponse,
} from '@/types/api'

function versionPath(moduleSlug: string, version: number): string {
  return `/api/content/education/modules/${encodeURIComponent(moduleSlug)}/versions/${version}`
}

export const contentEducationApi = {
  listVersions: () => apiFetch<EducationManagementSummary[]>('/api/content/education/modules'),

  createModule: (request: EducationModuleCreateRequest) =>
    apiFetch<EducationManagementDetail>('/api/content/education/modules', { method: 'POST', body: request }),

  getVersion: (moduleSlug: string, version: number) =>
    apiFetch<EducationManagementDetail>(versionPath(moduleSlug, version)),

  getVersionForm: (moduleSlug: string, version: number) =>
    apiFetch<EducationContentFormData>(`${versionPath(moduleSlug, version)}/form`),

  updateVersion: (moduleSlug: string, version: number, form: EducationContentFormData) =>
    apiFetch<EducationManagementDetail>(versionPath(moduleSlug, version), { method: 'PUT', body: form }),

  submitReview: (moduleSlug: string, version: number) =>
    apiFetch<EducationManagementDetail>(`${versionPath(moduleSlug, version)}/submit-review`, { method: 'POST' }),

  review: (moduleSlug: string, version: number, decision: 'approve' | 'reject', notes: string) =>
    apiFetch<EducationManagementDetail>(`${versionPath(moduleSlug, version)}/${decision}`, { method: 'POST', body: { notes } }),

  publish: (moduleSlug: string, version: number) =>
    apiFetch<EducationManagementDetail>(`${versionPath(moduleSlug, version)}/publish`, { method: 'POST' }),

  copyVersion: (moduleSlug: string, version: number) =>
    apiFetch<EducationManagementDetail>(`${versionPath(moduleSlug, version)}/copy`, { method: 'POST' }),

  previewMarkdown: (markdown: string) =>
    apiFetch<MarkdownPreviewResponse>('/api/content/education/markdown-preview', { method: 'POST', body: { markdown } }),
}
```

Add the `content` block inside the `clinical` object in `frontend/src/i18n/en.json` (after the `col*` keys; JSON key order is not significant):

```json
    "content": {
      "nav": "Content",
      "title": "Content management",
      "newModule": "New module",
      "newVersion": "New version",
      "empty": "No module versions yet.",
      "filterModule": "Module",
      "allModules": "All modules",
      "filterStatus": "Status",
      "allStatuses": "All statuses",
      "colModule": "Module",
      "colVersion": "Version",
      "colStatus": "Status",
      "colTitle": "Title",
      "colAuthor": "Author",
      "colCreated": "Created",
      "status": {
        "DRAFT": "Draft",
        "IN_REVIEW": "In review",
        "APPROVED": "Approved",
        "PUBLISHED": "Published",
        "ARCHIVED": "Archived",
        "REJECTED": "Rejected"
      },
      "detailTitle": "Module version",
      "backToList": "All versions",
      "lessonsTitle": "Lessons",
      "reviewNotes": "Review notes",
      "notesPlaceholder": "Notes for the author (optional)",
      "reviewBypassed": "Review bypassed",
      "notPublishable": "Publishing requires at least one lesson with an English title, summary, and body, plus an English module title and summary.",
      "meta": {
        "author": "Author",
        "reviewedBy": "Reviewer",
        "publishedBy": "Published by",
        "createdAt": "Created",
        "submittedAt": "Submitted",
        "reviewedAt": "Reviewed",
        "publishedAt": "Published"
      },
      "actions": {
        "submitReview": "Submit for review",
        "approve": "Approve",
        "reject": "Reject",
        "publish": "Publish",
        "copy": "Copy as new version",
        "edit": "Edit content",
        "reload": "Reload"
      },
      "notEditable": "This version is no longer editable.",
      "fields": {
        "slug": "Slug",
        "topic": "Topic",
        "sortOrder": "Sort order",
        "englishTitle": "English title",
        "englishSummary": "English summary",
        "czechTitle": "Czech title",
        "czechSummary": "Czech summary",
        "englishBody": "English body",
        "czechBody": "Czech body"
      },
      "create": {
        "title": "New education module",
        "submit": "Create and edit content"
      },
      "editor": {
        "title": "Edit content",
        "lessons": "Lessons",
        "addLesson": "Add lesson",
        "lessonSlug": "Lesson slug",
        "editTab": "Edit",
        "previewTab": "Preview",
        "markdownHint": "Markdown: # heading, **bold**, *italic*, - list item, [link](https://…)",
        "unsaved": "You have unsaved changes. Leave anyway?",
        "lessonIncomplete": "Populated lessons need a slug, a sort order of 1 or more, and an English title, summary, and body."
      }
    },
```

Add the same block in `frontend/src/i18n/cs.json` with Czech values (identical key shape):

```json
    "content": {
      "nav": "Obsah",
      "title": "Správa obsahu",
      "newModule": "Nový modul",
      "newVersion": "Nová verze",
      "empty": "Zatím nejsou žádné verze modulů.",
      "filterModule": "Modul",
      "allModules": "Všechny moduly",
      "filterStatus": "Stav",
      "allStatuses": "Všechny stavy",
      "colModule": "Modul",
      "colVersion": "Verze",
      "colStatus": "Stav",
      "colTitle": "Název",
      "colAuthor": "Autor",
      "colCreated": "Vytvořeno",
      "status": {
        "DRAFT": "Koncept",
        "IN_REVIEW": "V recenzi",
        "APPROVED": "Schváleno",
        "PUBLISHED": "Publikováno",
        "ARCHIVED": "Archivováno",
        "REJECTED": "Zamítnuto"
      },
      "detailTitle": "Verze modulu",
      "backToList": "Všechny verze",
      "lessonsTitle": "Lekce",
      "reviewNotes": "Poznámky k recenzi",
      "notesPlaceholder": "Poznámky pro autora (nepovinné)",
      "reviewBypassed": "Recenze vynechána",
      "notPublishable": "Publikování vyžaduje alespoň jednu lekci s anglickým názvem, shrnutím a textem a anglický název a shrnutí modulu.",
      "meta": {
        "author": "Autor",
        "reviewedBy": "Recenzent",
        "publishedBy": "Publikoval",
        "createdAt": "Vytvořeno",
        "submittedAt": "Odesláno",
        "reviewedAt": "Recenzováno",
        "publishedAt": "Publikováno"
      },
      "actions": {
        "submitReview": "Odeslat k recenzi",
        "approve": "Schválit",
        "reject": "Zamítnout",
        "publish": "Publikovat",
        "copy": "Kopírovat jako novou verzi",
        "edit": "Upravit obsah",
        "reload": "Znovu načíst"
      },
      "notEditable": "Tato verze již není upravitelná.",
      "fields": {
        "slug": "Slug",
        "topic": "Téma",
        "sortOrder": "Pořadí",
        "englishTitle": "Anglický název",
        "englishSummary": "Anglické shrnutí",
        "czechTitle": "Český název",
        "czechSummary": "České shrnutí",
        "englishBody": "Anglický text",
        "czechBody": "Český text"
      },
      "create": {
        "title": "Nový vzdělávací modul",
        "submit": "Vytvořit a upravit obsah"
      },
      "editor": {
        "title": "Úprava obsahu",
        "lessons": "Lekce",
        "addLesson": "Přidat lekci",
        "lessonSlug": "Slug lekce",
        "editTab": "Upravit",
        "previewTab": "Náhled",
        "markdownHint": "Markdown: # nadpis, **tučné**, *kurzíva*, - položka seznamu, [odkaz](https://…)",
        "unsaved": "Máte neuložené změny. Opravdu chcete odejít?",
        "lessonIncomplete": "Vyplněné lekce vyžadují slug, pořadí alespoň 1 a anglický název, shrnutí a text."
      }
    },
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd frontend && npm run test -- tests/api/contentEducation.test.ts`
Expected: PASS (3 tests).
Then run the parity check: `cd frontend && npm run test -- tests/i18n/locale.test.ts`
Expected: PASS (key parity holds between en and cs).

- [ ] **Step 5: Commit**

```bash
git add frontend/src/api/contentEducation.ts frontend/src/types/api.ts frontend/src/i18n/en.json frontend/src/i18n/cs.json frontend/tests/api/contentEducation.test.ts
git commit -m "Add clinical content management API client and i18n keys"
```

---

### Task 3: Clinical content list view (route + nav)

**Files:**
- Create: `frontend/src/views/clinical/ClinicalContentListView.vue`
- Modify: `frontend/src/router/index.ts:26` (import) and `:72` (children of `/clinical`)
- Modify: `frontend/src/components/ClinicalShell.vue:14-18` (links)
- Test: `frontend/tests/views/clinical/ClinicalContentListView.test.ts`

**Interfaces:**
- Consumes: `contentEducationApi.listVersions()`, `contentEducationApi.copyVersion(moduleSlug, version)` from Task 2.
- Produces: route `/clinical/content`; nav item labeled `t('clinical.content.nav')`.

- [ ] **Step 1: Write the failing test**

Create `frontend/tests/views/clinical/ClinicalContentListView.test.ts`:

```ts
import { flushPromises, mount } from '@vue/test-utils'
import { http, HttpResponse } from 'msw'
import { beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { createI18n } from 'vue-i18n'
import { createRouter, createMemoryHistory } from 'vue-router'
import { server } from '../../msw/server'
import ClinicalContentListView from '@/views/clinical/ClinicalContentListView.vue'
import en from '@/i18n/en.json'
import cs from '@/i18n/cs.json'

const i18n = createI18n({ legacy: false, locale: 'en', messages: { en, cs } })

function versions() {
  return [
    {
      moduleSlug: 'ibd-basics', topic: 'IBD', version: 2, status: 'PUBLISHED', title: 'IBD Basics',
      authorEmail: 'author@example.com', reviewedByEmail: null, publishedByEmail: 'author@example.com',
      createdAt: '2026-08-01T10:00:00Z', submittedAt: null, reviewedAt: null, publishedAt: '2026-08-02T10:00:00Z',
    },
    {
      moduleSlug: 'ibd-basics', topic: 'IBD', version: 3, status: 'DRAFT', title: 'IBD Basics v3',
      authorEmail: 'me@example.com', reviewedByEmail: null, publishedByEmail: null,
      createdAt: '2026-09-01T10:00:00Z', submittedAt: null, reviewedAt: null, publishedAt: null,
    },
    {
      moduleSlug: 'keto-guide', topic: 'Keto', version: 1, status: 'IN_REVIEW', title: 'Keto Guide',
      authorEmail: 'other@example.com', reviewedByEmail: null, publishedByEmail: null,
      createdAt: '2026-09-05T10:00:00Z', submittedAt: '2026-09-06T10:00:00Z', reviewedAt: null, publishedAt: null,
    },
  ]
}

function makeRouter() {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/clinical/content', component: ClinicalContentListView },
      { path: '/clinical/content/new', component: { template: '<div />' } },
      { path: '/clinical/content/:moduleSlug/:version', component: { template: '<div />' } },
    ],
  })
}

async function mountAt(path: string) {
  const router = makeRouter()
  await router.push(path)
  const wrapper = mount(ClinicalContentListView, {
    global: { plugins: [createPinia(), i18n, router] },
  })
  await flushPromises()
  return { wrapper, router }
}

describe('ClinicalContentListView', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    server.use(
      http.get('/api/content/education/modules', () => HttpResponse.json(versions())),
    )
  })

  it('renders all versions with localized status badges', async () => {
    const { wrapper } = await mountAt('/clinical/content')
    expect(wrapper.findAll('[data-testid="content-row"]')).toHaveLength(3)
    expect(wrapper.text()).toContain('IBD Basics v3')
    expect(wrapper.text()).toContain('Draft')
    expect(wrapper.text()).toContain('In review')
    expect(wrapper.text()).toContain('Published')
  })

  it('filters rows by status', async () => {
    const { wrapper } = await mountAt('/clinical/content')
    await wrapper.find('[data-testid="status-filter"]').setValue('DRAFT')
    expect(wrapper.findAll('[data-testid="content-row"]')).toHaveLength(1)
    expect(wrapper.text()).toContain('IBD Basics v3')
  })

  it('navigates to the version detail on row click', async () => {
    const { wrapper, router } = await mountAt('/clinical/content')
    await wrapper.find('[data-testid="content-row"]').trigger('click')
    expect(router.currentRoute.value.path).toBe('/clinical/content/ibd-basics/2')
  })

  it('copies a version and navigates to the new draft', async () => {
    server.use(
      http.post('/api/content/education/modules/ibd-basics/versions/2/copy', () =>
        HttpResponse.json({ moduleSlug: 'ibd-basics', version: 3 })),
    )
    const { wrapper, router } = await mountAt('/clinical/content')
    await wrapper.find('[data-testid="copy-version"]').trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/clinical/content/ibd-basics/3')
  })
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && npm run test -- tests/views/clinical/ClinicalContentListView.test.ts`
Expected: FAIL — `@/views/clinical/ClinicalContentListView.vue` cannot be resolved.

- [ ] **Step 3: Implement the view, route, and nav item**

Create `frontend/src/views/clinical/ClinicalContentListView.vue`:

```vue
<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { contentEducationApi } from '@/api/contentEducation'
import { useApiError } from '@/composables/useApiError'
import { formatDateTime } from '@/utils/dateTime'
import type { EducationContentStatus, EducationManagementSummary } from '@/types/api'

const { t, locale } = useI18n()
const router = useRouter()
const { message, capture, clear } = useApiError()

const items = ref<EducationManagementSummary[]>([])
const loading = ref(true)
const moduleFilter = ref('')
const statusFilter = ref<EducationContentStatus | ''>('')

const statusOptions: EducationContentStatus[] = ['DRAFT', 'IN_REVIEW', 'APPROVED', 'PUBLISHED', 'ARCHIVED', 'REJECTED']

const visible = computed(() => {
  const needle = moduleFilter.value.trim().toLowerCase()
  return items.value.filter((item) => {
    const matchesModule = needle === ''
      || item.moduleSlug.toLowerCase().includes(needle)
      || item.topic.toLowerCase().includes(needle)
    const matchesStatus = statusFilter.value === '' || item.status === statusFilter.value
    return matchesModule && matchesStatus
  })
})

async function load() {
  clear()
  loading.value = true
  try {
    items.value = await contentEducationApi.listVersions()
  } catch (e) {
    items.value = []
    capture(e)
  } finally {
    loading.value = false
  }
}

function open(item: EducationManagementSummary) {
  void router.push(`/clinical/content/${item.moduleSlug}/${item.version}`)
}

async function copy(item: EducationManagementSummary) {
  clear()
  try {
    const draft = await contentEducationApi.copyVersion(item.moduleSlug, item.version)
    await router.push(`/clinical/content/${draft.moduleSlug}/${draft.version}`)
  } catch (e) {
    capture(e)
  }
}

onMounted(load)
</script>

<template>
  <section>
    <div class="flex items-center justify-between">
      <h1 class="text-2xl font-semibold">{{ t('clinical.content.title') }}</h1>
      <router-link to="/clinical/content/new" data-testid="new-module"
                   class="rounded bg-blue-600 px-3 py-1 text-sm text-white">
        {{ t('clinical.content.newModule') }}
      </router-link>
    </div>

    <div class="mt-2 flex flex-wrap items-end gap-3">
      <label class="text-sm">{{ t('clinical.content.filterModule') }}
        <input v-model="moduleFilter" data-testid="module-filter" type="text"
               class="ml-1 rounded border border-gray-300 px-2 py-1 dark:border-gray-600 dark:bg-gray-800" />
      </label>
      <label class="text-sm">{{ t('clinical.content.filterStatus') }}
        <select v-model="statusFilter" data-testid="status-filter"
                class="ml-1 rounded border border-gray-300 px-2 py-1 dark:border-gray-600 dark:bg-gray-800">
          <option value="">{{ t('clinical.content.allStatuses') }}</option>
          <option v-for="s in statusOptions" :key="s" :value="s">{{ t(`clinical.content.status.${s}`) }}</option>
        </select>
      </label>
    </div>

    <p v-if="message" class="mt-4 rounded bg-red-50 p-3 text-sm text-red-700 dark:bg-red-950 dark:text-red-300">{{ message }}</p>
    <p v-if="loading" class="mt-4">{{ t('common.loading') }}</p>
    <p v-else-if="visible.length === 0" class="mt-4 text-sm text-gray-600 dark:text-gray-400">{{ t('clinical.content.empty') }}</p>
    <table v-else class="mt-4 w-full border-collapse bg-white text-sm dark:bg-gray-800">
      <thead>
        <tr class="border-b text-left">
          <th class="p-2">{{ t('clinical.content.colModule') }}</th>
          <th class="p-2">{{ t('clinical.content.colVersion') }}</th>
          <th class="p-2">{{ t('clinical.content.colTitle') }}</th>
          <th class="p-2">{{ t('clinical.content.colStatus') }}</th>
          <th class="p-2">{{ t('clinical.content.colAuthor') }}</th>
          <th class="p-2">{{ t('clinical.content.colCreated') }}</th>
          <th class="p-2"></th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="item in visible" :key="`${item.moduleSlug}-${item.version}`" data-testid="content-row"
            class="cursor-pointer border-b hover:bg-gray-50 dark:hover:bg-gray-700" @click="open(item)">
          <td class="p-2">{{ item.topic }} <span class="text-gray-500">({{ item.moduleSlug }})</span></td>
          <td class="p-2">{{ item.version }}</td>
          <td class="p-2">{{ item.title ?? t('clinical.noValue') }}</td>
          <td class="p-2">{{ t(`clinical.content.status.${item.status}`) }}</td>
          <td class="p-2">{{ item.authorEmail ?? t('clinical.noValue') }}</td>
          <td class="p-2">{{ formatDateTime(item.createdAt, locale) }}</td>
          <td class="p-2">
            <button data-testid="copy-version" class="rounded border px-2 py-0.5 text-xs" @click.stop="copy(item)">
              {{ t('clinical.content.newVersion') }}
            </button>
          </td>
        </tr>
      </tbody>
    </table>
  </section>
</template>
```

In `frontend/src/router/index.ts`: add the import next to the other clinical view imports (line ~36):

```ts
import ClinicalContentListView from '@/views/clinical/ClinicalContentListView.vue'
```

and the child route as the first entry inside the `/clinical` children array (line ~73, before `onboarding`):

```ts
      { path: 'content', component: ClinicalContentListView },
```

In `frontend/src/components/ClinicalShell.vue`, extend the `links` computed (lines 14–18):

```ts
const links = computed(() => [
  { to: '/clinical', label: t('clinical.navOverview') },
  { to: '/clinical/onboarding', label: t('clinical.navReview') },
  { to: '/clinical/education', label: t('nav.education') },
  { to: '/clinical/content', label: t('clinical.content.nav') },
])
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd frontend && npm run test -- tests/views/clinical/ClinicalContentListView.test.ts`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add frontend/src/views/clinical/ClinicalContentListView.vue frontend/src/router/index.ts frontend/src/components/ClinicalShell.vue frontend/tests/views/clinical/ClinicalContentListView.test.ts
git commit -m "Add clinical content management list view"
```

---

### Task 4: Clinical content detail view with lifecycle actions

**Files:**
- Create: `frontend/src/views/clinical/ClinicalContentDetailView.vue`
- Modify: `frontend/src/router/index.ts` (import + child route `content/:moduleSlug/:version`)
- Test: `frontend/tests/views/clinical/ClinicalContentDetailView.test.ts`

**Interfaces:**
- Consumes: `contentEducationApi.getVersion`, `submitReview`, `review`, `publish`, `copyVersion` from Task 2; `useAuthStore().email` and `.roles` (existing store).
- Produces: route `/clinical/content/:moduleSlug/:version` (reads `route.params.moduleSlug`, `Number(route.params.version)`).
- Note: the server detail lessons are EN-localized with rendered `bodyHtml`; `title` is non-null exactly when the lesson has an EN localization (this mirrors `validatePublishable`), which the publishability check relies on.

- [ ] **Step 1: Write the failing test**

Create `frontend/tests/views/clinical/ClinicalContentDetailView.test.ts`:

```ts
import { flushPromises, mount } from '@vue/test-utils'
import { http, HttpResponse } from 'msw'
import { beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { createI18n } from 'vue-i18n'
import { createRouter, createMemoryHistory } from 'vue-router'
import { server } from '../../msw/server'
import ClinicalContentDetailView from '@/views/clinical/ClinicalContentDetailView.vue'
import { useAuthStore } from '@/stores/auth'
import en from '@/i18n/en.json'
import cs from '@/i18n/cs.json'

const i18n = createI18n({ legacy: false, locale: 'en', messages: { en, cs } })

function detail(overrides: Record<string, unknown> = {}) {
  return {
    moduleSlug: 'ibd-basics',
    topic: 'IBD',
    sortOrder: 10,
    version: 2,
    status: 'DRAFT',
    reviewNotes: null,
    reviewBypassed: false,
    authorEmail: 'author@example.com',
    reviewedByEmail: null,
    publishedByEmail: null,
    createdAt: '2026-08-01T10:00:00Z',
    submittedAt: null,
    reviewedAt: null,
    publishedAt: null,
    lessons: [
      {
        lessonSlug: 'intro', sortOrder: 10, requestedLanguage: 'EN', contentLanguage: 'EN',
        title: 'Intro', summary: null, bodyMarkdown: '# Hi', bodyHtml: '<h1>Hi</h1>', completed: null,
      },
    ],
    ...overrides,
  }
}

function makeRouter() {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/clinical/content/:moduleSlug/:version', component: ClinicalContentDetailView, props: true },
      { path: '/clinical/content/:moduleSlug/:version/edit', component: { template: '<div />' } },
    ],
  })
}

async function mountDetail(overrides: Record<string, unknown> = {}) {
  server.use(
    http.get('/api/content/education/modules/ibd-basics/versions/2', () =>
      HttpResponse.json(detail(overrides))),
  )
  useAuthStore().$patch({ email: 'viewer@example.com', roles: ['PHYSICIAN'], status: 'authenticated' })
  const router = makeRouter()
  await router.push('/clinical/content/ibd-basics/2')
  const wrapper = mount(ClinicalContentDetailView, {
    global: { plugins: [createPinia(), i18n, router] },
  })
  await flushPromises()
  return wrapper
}

describe('ClinicalContentDetailView', () => {
  beforeEach(() => setActivePinia(createPinia()))

  it('shows draft actions and lesson previews', async () => {
    const wrapper = await mountDetail()
    expect(wrapper.find('[data-testid="submit-review"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="approve"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="publish"]').exists()).toBe(false)
    expect(wrapper.html()).toContain('<h1>Hi</h1>')
  })

  it('hides approval from the author but shows it to another reviewer', async () => {
    server.use(
      http.get('/api/content/education/modules/ibd-basics/versions/2', () =>
        HttpResponse.json(detail({ status: 'IN_REVIEW' }))),
    )
    useAuthStore().$patch({ email: 'author@example.com', roles: ['PHYSICIAN'], status: 'authenticated' })
    const router = makeRouter()
    await router.push('/clinical/content/ibd-basics/2')
    const own = mount(ClinicalContentDetailView, { global: { plugins: [createPinia(), i18n, router] } })
    await flushPromises()
    expect(own.find('[data-testid="approve"]').exists()).toBe(false)

    server.use(
      http.get('/api/content/education/modules/ibd-basics/versions/2', () =>
        HttpResponse.json(detail({ status: 'IN_REVIEW' }))),
    )
    useAuthStore().$patch({ email: 'someone-else@example.com', roles: ['PHYSICIAN'], status: 'authenticated' })
    const router2 = makeRouter()
    await router2.push('/clinical/content/ibd-basics/2')
    const other = mount(ClinicalContentDetailView, { global: { plugins: [createPinia(), i18n, router2] } })
    await flushPromises()
    expect(other.find('[data-testid="approve"]').exists()).toBe(true)
    expect(other.find('[data-testid="reject"]').exists()).toBe(true)
  })

  it('lets an admin approve their own content', async () => {
    server.use(
      http.get('/api/content/education/modules/ibd-basics/versions/2', () =>
        HttpResponse.json(detail({ status: 'IN_REVIEW' }))),
    )
    useAuthStore().$patch({ email: 'author@example.com', roles: ['ADMIN'], status: 'authenticated' })
    const router = makeRouter()
    await router.push('/clinical/content/ibd-basics/2')
    const wrapper = mount(ClinicalContentDetailView, { global: { plugins: [createPinia(), i18n, router] } })
    await flushPromises()
    expect(wrapper.find('[data-testid="approve"]').exists()).toBe(true)
  })

  it('submitting for review updates the status in place', async () => {
    let calls = 0
    server.use(
      http.get('/api/content/education/modules/ibd-basics/versions/2', () =>
        HttpResponse.json(detail(calls === 0 ? { status: 'DRAFT' } : { status: 'IN_REVIEW' }))),
      http.post('/api/content/education/modules/ibd-basics/versions/2/submit-review', () => {
        calls += 1
        return HttpResponse.json(detail({ status: 'IN_REVIEW' }))
      }),
    )
    useAuthStore().$patch({ email: 'viewer@example.com', roles: ['PHYSICIAN'], status: 'authenticated' })
    const router = makeRouter()
    await router.push('/clinical/content/ibd-basics/2')
    const wrapper = mount(ClinicalContentDetailView, { global: { plugins: [createPinia(), i18n, router] } })
    await flushPromises()
    await wrapper.find('[data-testid="submit-review"]').trigger('click')
    await flushPromises()
    expect(calls).toBe(1)
    expect(wrapper.text()).toContain('In review')
  })

  it('disables publish when the version is not publishable', async () => {
    server.use(
      http.get('/api/content/education/modules/ibd-basics/versions/2', () =>
        HttpResponse.json(detail({ status: 'APPROVED', lessons: [] }))),
    )
    useAuthStore().$patch({ email: 'viewer@example.com', roles: ['PHYSICIAN'], status: 'authenticated' })
    const router = makeRouter()
    await router.push('/clinical/content/ibd-basics/2')
    const wrapper = mount(ClinicalContentDetailView, { global: { plugins: [createPinia(), i18n, router] } })
    await flushPromises()
    const publish = wrapper.find('[data-testid="publish"]')
    expect(publish.attributes('disabled')).toBeDefined()
    expect(publish.attributes('title')).toContain('Publishing requires')
  })

  it('shows an error banner and resyncs when a transition is rejected', async () => {
    let getCalls = 0
    server.use(
      http.get('/api/content/education/modules/ibd-basics/versions/2', () => {
        getCalls += 1
        return HttpResponse.json(detail())
      }),
      http.post('/api/content/education/modules/ibd-basics/versions/2/submit-review', () =>
        HttpResponse.json({ error: 'request_failed' }, { status: 400 })),
    )
    useAuthStore().$patch({ email: 'viewer@example.com', roles: ['PHYSICIAN'], status: 'authenticated' })
    const router = makeRouter()
    await router.push('/clinical/content/ibd-basics/2')
    const wrapper = mount(ClinicalContentDetailView, { global: { plugins: [createPinia(), i18n, router] } })
    await flushPromises()
    await wrapper.find('[data-testid="submit-review"]').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('Something went wrong')
    expect(getCalls).toBe(2)
  })
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && npm run test -- tests/views/clinical/ClinicalContentDetailView.test.ts`
Expected: FAIL — `@/views/clinical/ClinicalContentDetailView.vue` cannot be resolved.

- [ ] **Step 3: Implement the view and route**

Create `frontend/src/views/clinical/ClinicalContentDetailView.vue`:

```vue
<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { contentEducationApi } from '@/api/contentEducation'
import { useApiError } from '@/composables/useApiError'
import { useAuthStore } from '@/stores/auth'
import { formatDateTime } from '@/utils/dateTime'
import type { EducationManagementDetail } from '@/types/api'

const { t, locale } = useI18n()
const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const { message, capture, clear } = useApiError()

const moduleSlug = route.params.moduleSlug as string
const version = Number(route.params.version)

const detail = ref<EducationManagementDetail | null>(null)
const loading = ref(true)
const reviewOpen = ref(false)
const notes = ref('')
const openLesson = ref<string | null>(null)

const isAuthor = computed(() => !!detail.value?.authorEmail && detail.value.authorEmail === auth.email)
const isAdmin = computed(() => auth.roles.includes('ADMIN'))
const canSubmitReview = computed(() => detail.value?.status === 'DRAFT' || detail.value?.status === 'REJECTED')
const canReview = computed(() => detail.value?.status === 'IN_REVIEW' && (!isAuthor.value || isAdmin.value))
const canPublish = computed(() => detail.value?.status === 'APPROVED')
// Mirrors the server-side validatePublishable invariants: an English module localization always
// exists (enforced at create), so lessons drive the check; title is non-null exactly when the
// lesson has an English localization.
const publishable = computed(() => !!detail.value
  && detail.value.lessons.length > 0
  && detail.value.lessons.every((lesson) => !!lesson.title))

async function load() {
  clear()
  loading.value = true
  try {
    detail.value = await contentEducationApi.getVersion(moduleSlug, version)
    openLesson.value ??= detail.value.lessons[0]?.lessonSlug ?? null
  } catch (e) {
    capture(e)
  } finally {
    loading.value = false
  }
}

async function transition(call: () => Promise<EducationManagementDetail>) {
  clear()
  try {
    detail.value = await call()
    reviewOpen.value = false
    notes.value = ''
  } catch (e) {
    capture(e)
    // Another manager may have changed the state; resync the action bar instead of going stale.
    await load()
  }
}

async function copy() {
  clear()
  try {
    const draft = await contentEducationApi.copyVersion(moduleSlug, version)
    await router.push(`/clinical/content/${draft.moduleSlug}/${draft.version}`)
  } catch (e) {
    capture(e)
    await load()
  }
}

onMounted(load)
</script>

<template>
  <section class="max-w-3xl">
    <router-link to="/clinical/content" class="text-sm text-blue-600 dark:text-blue-400">
      ← {{ t('clinical.content.backToList') }}
    </router-link>
    <p v-if="loading" class="mt-4">{{ t('common.loading') }}</p>
    <template v-else-if="detail">
      <div class="mt-2 flex flex-wrap items-center gap-3">
        <h1 class="text-2xl font-semibold">
          {{ t('clinical.content.detailTitle') }} {{ detail.moduleSlug }} v{{ detail.version }}
        </h1>
        <span class="rounded bg-gray-100 px-2 py-0.5 text-xs dark:bg-gray-700"
              data-testid="status-badge">{{ t(`clinical.content.status.${detail.status}`) }}</span>
        <span v-if="detail.reviewBypassed" class="rounded bg-yellow-100 px-2 py-0.5 text-xs text-yellow-800 dark:bg-yellow-900 dark:text-yellow-200">
          {{ t('clinical.content.reviewBypassed') }}
        </span>
      </div>
      <p class="mt-1 text-gray-600 dark:text-gray-400">{{ detail.topic }}</p>

      <p v-if="message" class="mt-4 rounded bg-red-50 p-3 text-sm text-red-700 dark:bg-red-950 dark:text-red-300">{{ message }}</p>

      <dl class="mt-4 grid grid-cols-2 gap-x-6 gap-y-1 text-sm sm:grid-cols-3">
        <div>
          <dt class="text-gray-500">{{ t('clinical.content.meta.author') }}</dt>
          <dd data-testid="meta-author">{{ detail.authorEmail ?? t('clinical.noValue') }}</dd>
        </div>
        <div>
          <dt class="text-gray-500">{{ t('clinical.content.meta.createdAt') }}</dt>
          <dd>{{ formatDateTime(detail.createdAt, locale) }}</dd>
        </div>
        <div v-if="detail.submittedAt">
          <dt class="text-gray-500">{{ t('clinical.content.meta.submittedAt') }}</dt>
          <dd>{{ formatDateTime(detail.submittedAt, locale) }}</dd>
        </div>
        <div v-if="detail.reviewedByEmail">
          <dt class="text-gray-500">{{ t('clinical.content.meta.reviewedBy') }}</dt>
          <dd>{{ detail.reviewedByEmail }}</dd>
        </div>
        <div v-if="detail.reviewedAt">
          <dt class="text-gray-500">{{ t('clinical.content.meta.reviewedAt') }}</dt>
          <dd>{{ formatDateTime(detail.reviewedAt, locale) }}</dd>
        </div>
        <div v-if="detail.publishedByEmail">
          <dt class="text-gray-500">{{ t('clinical.content.meta.publishedBy') }}</dt>
          <dd>{{ detail.publishedByEmail }}</dd>
        </div>
        <div v-if="detail.publishedAt">
          <dt class="text-gray-500">{{ t('clinical.content.meta.publishedAt') }}</dt>
          <dd>{{ formatDateTime(detail.publishedAt, locale) }}</dd>
        </div>
      </dl>

      <div class="mt-6 flex flex-wrap gap-2">
        <button v-if="canSubmitReview" data-testid="submit-review"
                class="rounded bg-blue-600 px-3 py-1 text-sm text-white"
                @click="transition(() => contentEducationApi.submitReview(moduleSlug, version))">
          {{ t('clinical.content.actions.submitReview') }}
        </button>
        <template v-if="canReview">
          <button data-testid="approve" class="rounded bg-green-600 px-3 py-1 text-sm text-white" @click="reviewOpen = !reviewOpen">
            {{ t('clinical.content.actions.approve') }}
          </button>
          <button data-testid="reject" class="rounded bg-red-600 px-3 py-1 text-sm text-white" @click="reviewOpen = !reviewOpen">
            {{ t('clinical.content.actions.reject') }}
          </button>
        </template>
        <button v-if="canPublish" data-testid="publish"
                class="rounded bg-green-700 px-3 py-1 text-sm text-white disabled:opacity-50"
                :disabled="!publishable"
                :title="publishable ? undefined : t('clinical.content.notPublishable')"
                @click="transition(() => contentEducationApi.publish(moduleSlug, version))">
          {{ t('clinical.content.actions.publish') }}
        </button>
        <button data-testid="copy" class="rounded border px-3 py-1 text-sm" @click="copy">
          {{ t('clinical.content.actions.copy') }}
        </button>
      </div>

      <div v-if="reviewOpen" class="mt-4 rounded border p-3">
        <label class="text-sm">{{ t('clinical.content.reviewNotes') }}
          <textarea v-model="notes" data-testid="review-notes" rows="3"
                    :placeholder="t('clinical.content.notesPlaceholder')"
                    class="mt-1 w-full rounded border border-gray-300 px-2 py-1 dark:border-gray-600 dark:bg-gray-800"></textarea>
        </label>
        <div class="mt-2 flex gap-2">
          <button data-testid="confirm-approve" class="rounded bg-green-600 px-3 py-1 text-sm text-white"
                  @click="transition(() => contentEducationApi.review(moduleSlug, version, 'approve', notes))">
            {{ t('clinical.content.actions.approve') }}
          </button>
          <button data-testid="confirm-reject" class="rounded bg-red-600 px-3 py-1 text-sm text-white"
                  @click="transition(() => contentEducationApi.review(moduleSlug, version, 'reject', notes))">
            {{ t('clinical.content.actions.reject') }}
          </button>
        </div>
      </div>

      <p v-if="detail.reviewNotes" class="mt-4 rounded bg-gray-50 p-3 text-sm dark:bg-gray-800">
        {{ t('clinical.content.reviewNotes') }}: {{ detail.reviewNotes }}
      </p>

      <h2 class="mt-6 font-medium">{{ t('clinical.content.lessonsTitle') }}</h2>
      <div v-if="detail.lessons.length === 0" class="mt-2 text-sm text-gray-600 dark:text-gray-400">
        {{ t('clinical.content.empty') }}
      </div>
      <div v-else class="mt-2 space-y-2">
        <div v-for="lesson in detail.lessons" :key="lesson.lessonSlug" class="rounded border bg-white dark:bg-gray-800">
          <button class="flex w-full items-center justify-between p-4 text-left"
                  @click="openLesson = openLesson === lesson.lessonSlug ? null : lesson.lessonSlug">
            <span>{{ lesson.title ?? lesson.lessonSlug }}</span>
            <span class="text-gray-400">{{ openLesson === lesson.lessonSlug ? '−' : '+' }}</span>
          </button>
          <div v-if="openLesson === lesson.lessonSlug" class="border-t p-4">
            <!-- bodyHtml is server-rendered from staff-authored content; same trust model as the patient view -->
            <div class="prose max-w-none" v-html="lesson.bodyHtml" />
          </div>
        </div>
      </div>
    </template>
  </section>
</template>
```

In `frontend/src/router/index.ts`: add the import and the child route AFTER `content`:

```ts
import ClinicalContentDetailView from '@/views/clinical/ClinicalContentDetailView.vue'
```

```ts
      { path: 'content/:moduleSlug/:version', component: ClinicalContentDetailView },
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd frontend && npm run test -- tests/views/clinical/ClinicalContentDetailView.test.ts`
Expected: PASS (6 tests).

- [ ] **Step 5: Commit**

```bash
git add frontend/src/views/clinical/ClinicalContentDetailView.vue frontend/src/router/index.ts frontend/tests/views/clinical/ClinicalContentDetailView.test.ts
git commit -m "Add clinical content detail view with lifecycle actions"
```

---

### Task 5: Full-form draft editor (MarkdownEditor component + edit view + detail Edit button)

**Files:**
- Create: `frontend/src/components/MarkdownEditor.vue`
- Create: `frontend/src/views/clinical/ClinicalContentEditView.vue`
- Modify: `frontend/src/views/clinical/ClinicalContentDetailView.vue` (add Edit button + `editable` computed)
- Modify: `frontend/src/router/index.ts` (import + child route `content/:moduleSlug/:version/edit`)
- Test: `frontend/tests/views/clinical/ClinicalContentEditView.test.ts`

**Interfaces:**
- Consumes: `contentEducationApi.getVersionForm`, `updateVersion`, `previewMarkdown` from Task 2; `FieldError` component; `ApiError` from `@/api/http`.
- Produces: route `/clinical/content/:moduleSlug/:version/edit`; `MarkdownEditor` component with props `{ modelValue: string; error?: string }` and `update:modelValue` emit (reused by both EN and CS bodies in this task; nothing else consumes it yet).

- [ ] **Step 1: Write the failing test**

Create `frontend/tests/views/clinical/ClinicalContentEditView.test.ts`:

```ts
import { flushPromises, mount } from '@vue/test-utils'
import { http, HttpResponse } from 'msw'
import { beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { createI18n } from 'vue-i18n'
import { createRouter, createMemoryHistory } from 'vue-router'
import { server } from '../../msw/server'
import ClinicalContentEditView from '@/views/clinical/ClinicalContentEditView.vue'
import en from '@/i18n/en.json'
import cs from '@/i18n/cs.json'

const i18n = createI18n({ legacy: false, locale: 'en', messages: { en, cs } })

function form() {
  return {
    slug: 'ibd-basics',
    topic: 'IBD',
    sortOrder: 10,
    englishTitle: 'IBD Basics',
    englishSummary: 'Overview.',
    czechTitle: 'Základy',
    czechSummary: 'Přehled.',
    lessons: [
      {
        slug: 'intro', sortOrder: 10, englishTitle: 'Intro', englishSummary: 'Intro summary.',
        englishBodyMarkdown: '# Hello', czechTitle: 'Úvod', czechSummary: 'Shrnutí úvodu.',
        czechBodyMarkdown: 'Ahoj',
      },
    ],
  }
}

function makeRouter() {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/clinical/content/:moduleSlug/:version/edit', component: ClinicalContentEditView, props: true },
      { path: '/clinical/content/:moduleSlug/:version', component: { template: '<div />' } },
    ],
  })
}

describe('ClinicalContentEditView', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    server.use(
      http.get('/api/content/education/modules/ibd-basics/versions/2/form', () => HttpResponse.json(form())),
    )
  })

  it('loads the form and saves the full draft', async () => {
    let putBody: unknown
    server.use(
      http.get('/api/csrf', () => HttpResponse.json({ token: 't', headerName: 'X-XSRF-TOKEN' })),
      http.put('/api/content/education/modules/ibd-basics/versions/2', async ({ request }) => {
        putBody = await request.json()
        return HttpResponse.json({})
      }),
    )
    const router = makeRouter()
    await router.push('/clinical/content/ibd-basics/2/edit')
    const wrapper = mount(ClinicalContentEditView, { global: { plugins: [createPinia(), i18n, router] } })
    await flushPromises()

    const body = wrapper.find('textarea[data-testid="markdown-source"]')
    expect(body.exists()).toBe(true)
    await body.setValue('# Hello edited')

    await wrapper.find('[data-testid="save"]').trigger('click')
    await flushPromises()

    expect(putBody).toEqual({
      slug: 'ibd-basics',
      topic: 'IBD',
      sortOrder: 10,
      englishTitle: 'IBD Basics',
      englishSummary: 'Overview.',
      czechTitle: 'Základy',
      czechSummary: 'Přehled.',
      lessons: [
        {
          slug: 'intro', sortOrder: 10, englishTitle: 'Intro', englishSummary: 'Intro summary.',
          englishBodyMarkdown: '# Hello edited', czechTitle: 'Úvod', czechSummary: 'Shrnutí úvodu.',
          czechBodyMarkdown: 'Ahoj',
        },
      ],
    })
    expect(router.currentRoute.value.path).toBe('/clinical/content/ibd-basics/2')
  })

  it('renders the server-rendered preview tab', async () => {
    server.use(
      http.get('/api/csrf', () => HttpResponse.json({ token: 't', headerName: 'X-XSRF-TOKEN' })),
      http.post('/api/content/education/markdown-preview', () => HttpResponse.json({ html: '<h1>Hello</h1>' })),
    )
    const router = makeRouter()
    await router.push('/clinical/content/ibd-basics/2/edit')
    const wrapper = mount(ClinicalContentEditView, { global: { plugins: [createPinia(), i18n, router] } })
    await flushPromises()

    await wrapper.find('[data-testid="markdown-preview-tab"]').trigger('click')
    await flushPromises()
    expect(wrapper.html()).toContain('<h1>Hello</h1>')
  })

  it('disables saving while a populated lesson row is incomplete', async () => {
    const router = makeRouter()
    await router.push('/clinical/content/ibd-basics/2/edit')
    const wrapper = mount(ClinicalContentEditView, { global: { plugins: [createPinia(), i18n, router] } })
    await flushPromises()

    await wrapper.find('input[data-testid="lesson-slug-0"]').setValue('')
    expect(wrapper.find('[data-testid="save"]').attributes('disabled')).toBeDefined()
    expect(wrapper.text()).toContain('Populated lessons need')
  })
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && npm run test -- tests/views/clinical/ClinicalContentEditView.test.ts`
Expected: FAIL — `@/views/clinical/ClinicalContentEditView.vue` cannot be resolved.

- [ ] **Step 3: Implement the MarkdownEditor component**

Create `frontend/src/components/MarkdownEditor.vue`:

```vue
<script setup lang="ts">
import { ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import FieldError from '@/components/FieldError.vue'
import { contentEducationApi } from '@/api/contentEducation'

const props = defineProps<{
  modelValue: string
  error?: string
}>()

const emit = defineEmits<{
  'update:modelValue': [value: string]
}>()

const { t } = useI18n()
const tab = ref<'edit' | 'preview'>('edit')
const html = ref('')
const loadingPreview = ref(false)

async function showPreview() {
  tab.value = 'preview'
  if (!props.modelValue.trim()) {
    html.value = ''
    return
  }
  loadingPreview.value = true
  try {
    html.value = (await contentEducationApi.previewMarkdown(props.modelValue)).html
  } catch {
    html.value = ''
  } finally {
    loadingPreview.value = false
  }
}

// Edits invalidate a stale preview; force the author back onto the edit tab.
watch(() => props.modelValue, () => {
  if (tab.value === 'preview') tab.value = 'edit'
})
</script>

<template>
  <div>
    <div class="flex gap-3 text-sm">
      <button type="button" data-testid="markdown-edit-tab"
              :class="tab === 'edit' ? 'font-semibold' : 'text-gray-500'"
              @click="tab = 'edit'">{{ t('clinical.content.editor.editTab') }}</button>
      <button type="button" data-testid="markdown-preview-tab"
              :class="tab === 'preview' ? 'font-semibold' : 'text-gray-500'"
              @click="showPreview">{{ t('clinical.content.editor.previewTab') }}</button>
    </div>
    <textarea v-if="tab === 'edit'" :value="modelValue" rows="8" data-testid="markdown-source"
              class="mt-1 w-full rounded border border-gray-300 px-2 py-1 font-mono text-sm dark:border-gray-600 dark:bg-gray-800"
              @input="emit('update:modelValue', ($event.target as HTMLTextAreaElement).value)"></textarea>
    <div v-else class="prose mt-1 max-w-none rounded border border-gray-300 p-2 dark:border-gray-600">
      <p v-if="loadingPreview" class="text-sm text-gray-500">{{ t('common.loading') }}</p>
      <!-- html is rendered by the server-side EducationMarkdownService from staff-authored content -->
      <div v-else v-html="html" />
    </div>
    <p class="mt-1 text-xs text-gray-500 dark:text-gray-400">{{ t('clinical.content.editor.markdownHint') }}</p>
    <FieldError :message="error" />
  </div>
</template>
```

- [ ] **Step 4: Implement the edit view and route, and add the Edit button to the detail view**

Create `frontend/src/views/clinical/ClinicalContentEditView.vue`:

```vue
<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { onBeforeRouteLeave, useRoute, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import MarkdownEditor from '@/components/MarkdownEditor.vue'
import { contentEducationApi } from '@/api/contentEducation'
import { ApiError } from '@/api/http'
import { useApiError } from '@/composables/useApiError'
import type { EducationContentFormData, EducationContentLessonRow } from '@/types/api'

const { t } = useI18n()
const route = useRoute()
const router = useRouter()
const { message, capture, clear } = useApiError()

const moduleSlug = route.params.moduleSlug as string
const version = Number(route.params.version)

const form = ref<EducationContentFormData | null>(null)
const lessons = ref<EducationContentLessonRow[]>([])
const loading = ref(true)
const saving = ref(false)
const initialSnapshot = ref('')

function snapshot(): unknown {
  return form.value ? { ...form.value, lessons: lessons.value } : null
}

const dirty = computed(() => form.value !== null && JSON.stringify(snapshot()) !== initialSnapshot.value)

function rowPopulated(row: EducationContentLessonRow): boolean {
  return !!(row.slug || row.englishTitle || row.englishSummary || row.englishBodyMarkdown
    || row.czechTitle || row.czechSummary || row.czechBodyMarkdown)
}

function rowIncomplete(row: EducationContentLessonRow): boolean {
  return rowPopulated(row)
    && (!row.slug.trim() || row.sortOrder < 1 || !row.englishTitle.trim()
        || !row.englishSummary.trim() || !row.englishBodyMarkdown.trim())
}

const hasIncompleteRows = computed(() => lessons.value.some(rowIncomplete))

function nextSortOrder(): number {
  return lessons.value.reduce((max, row) => Math.max(max, row.sortOrder), 0) + 10
}

function addLesson() {
  lessons.value.push({
    slug: '', sortOrder: nextSortOrder(), englishTitle: '', englishSummary: '', englishBodyMarkdown: '',
    czechTitle: '', czechSummary: '', czechBodyMarkdown: '',
  })
}

async function load() {
  clear()
  loading.value = true
  try {
    const data = await contentEducationApi.getVersionForm(moduleSlug, version)
    form.value = data
    lessons.value = data.lessons.map((row) => ({ ...row }))
    initialSnapshot.value = JSON.stringify(snapshot())
  } catch (e) {
    capture(e)
  } finally {
    loading.value = false
  }
}

async function save() {
  if (!form.value || hasIncompleteRows.value) return
  saving.value = true
  clear()
  try {
    await contentEducationApi.updateVersion(moduleSlug, version, { ...form.value, lessons: lessons.value })
    await router.push(`/clinical/content/${moduleSlug}/${version}`)
  } catch (e) {
    capture(e)
    if (e instanceof ApiError && e.status === 400) {
      // The version left the editable state meanwhile (e.g. submitted for review); resync.
      await load()
    }
  } finally {
    saving.value = false
  }
}

onBeforeRouteLeave(() => {
  if (dirty.value && !window.confirm(t('clinical.content.editor.unsaved'))) return false
  return true
})

onMounted(load)
</script>

<template>
  <section class="max-w-3xl">
    <router-link :to="`/clinical/content/${moduleSlug}/${version}`" class="text-sm text-blue-600 dark:text-blue-400">
      ← {{ t('common.back') }}
    </router-link>
    <h1 class="mt-2 text-2xl font-semibold">{{ t('clinical.content.editor.title') }}</h1>
    <p class="mt-1 text-sm text-gray-600 dark:text-gray-400">{{ form?.slug }} v{{ version }}</p>

    <p v-if="message" class="mt-4 rounded bg-red-50 p-3 text-sm text-red-700 dark:bg-red-950 dark:text-red-300">{{ message }}</p>
    <p v-if="loading" class="mt-4">{{ t('common.loading') }}</p>

    <form v-else-if="form" class="mt-4 space-y-4" @submit.prevent="save">
      <div class="grid gap-3 sm:grid-cols-2">
        <label class="text-sm">{{ t('clinical.content.fields.topic') }}
          <input v-model="form.topic" data-testid="topic" type="text"
                 class="mt-1 w-full rounded border border-gray-300 px-2 py-1 dark:border-gray-600 dark:bg-gray-800" />
        </label>
        <label class="text-sm">{{ t('clinical.content.fields.sortOrder') }}
          <input v-model.number="form.sortOrder" data-testid="sort-order" type="number" min="1"
                 class="mt-1 w-full rounded border border-gray-300 px-2 py-1 dark:border-gray-600 dark:bg-gray-800" />
        </label>
        <label class="text-sm">{{ t('clinical.content.fields.englishTitle') }}
          <input v-model="form.englishTitle" data-testid="english-title" type="text"
                 class="mt-1 w-full rounded border border-gray-300 px-2 py-1 dark:border-gray-600 dark:bg-gray-800" />
        </label>
        <label class="text-sm">{{ t('clinical.content.fields.czechTitle') }}
          <input v-model="form.czechTitle" data-testid="czech-title" type="text"
                 class="mt-1 w-full rounded border border-gray-300 px-2 py-1 dark:border-gray-600 dark:bg-gray-800" />
        </label>
        <label class="text-sm sm:col-span-2">{{ t('clinical.content.fields.englishSummary') }}
          <textarea v-model="form.englishSummary" data-testid="english-summary" rows="2"
                    class="mt-1 w-full rounded border border-gray-300 px-2 py-1 dark:border-gray-600 dark:bg-gray-800"></textarea>
        </label>
        <label class="text-sm sm:col-span-2">{{ t('clinical.content.fields.czechSummary') }}
          <textarea v-model="form.czechSummary" data-testid="czech-summary" rows="2"
                    class="mt-1 w-full rounded border border-gray-300 px-2 py-1 dark:border-gray-600 dark:bg-gray-800"></textarea>
        </label>
      </div>

      <h2 class="font-medium">{{ t('clinical.content.editor.lessons') }}</h2>
      <div v-for="(lesson, index) in lessons" :key="index" data-testid="lesson-row"
           class="space-y-3 rounded border p-3">
        <div class="flex items-end justify-between gap-3">
          <label class="flex-1 text-sm">{{ t('clinical.content.editor.lessonSlug') }}
            <input v-model="lesson.slug" :data-testid="`lesson-slug-${index}`" type="text"
                   class="mt-1 w-full rounded border border-gray-300 px-2 py-1 dark:border-gray-600 dark:bg-gray-800" />
          </label>
          <label class="w-28 text-sm">{{ t('clinical.content.fields.sortOrder') }}
            <input v-model.number="lesson.sortOrder" :data-testid="`lesson-sort-${index}`" type="number" min="1"
                   class="mt-1 w-full rounded border border-gray-300 px-2 py-1 dark:border-gray-600 dark:bg-gray-800" />
          </label>
          <button type="button" :data-testid="`remove-lesson-${index}`"
                  class="rounded border px-2 py-1 text-sm" @click="lessons.splice(index, 1)">
            {{ t('common.remove') }}
          </button>
        </div>
        <p v-if="rowIncomplete(lesson)" class="text-sm text-red-600 dark:text-red-400">
          {{ t('clinical.content.editor.lessonIncomplete') }}
        </p>
        <div class="grid gap-3 sm:grid-cols-2">
          <div>
            <label class="text-sm">{{ t('clinical.content.fields.englishTitle') }}
              <input v-model="lesson.englishTitle" :data-testid="`english-title-${index}`" type="text"
                     class="mt-1 w-full rounded border border-gray-300 px-2 py-1 dark:border-gray-600 dark:bg-gray-800" />
            </label>
            <label class="mt-2 block text-sm">{{ t('clinical.content.fields.englishSummary') }}
              <textarea v-model="lesson.englishSummary" :data-testid="`english-summary-${index}`" rows="2"
                        class="mt-1 w-full rounded border border-gray-300 px-2 py-1 dark:border-gray-600 dark:bg-gray-800"></textarea>
            </label>
            <div class="mt-2">
              <p class="text-sm">{{ t('clinical.content.fields.englishBody') }}</p>
              <MarkdownEditor v-model="lesson.englishBodyMarkdown" />
            </div>
          </div>
          <div>
            <label class="text-sm">{{ t('clinical.content.fields.czechTitle') }}
              <input v-model="lesson.czechTitle" :data-testid="`czech-title-${index}`" type="text"
                     class="mt-1 w-full rounded border border-gray-300 px-2 py-1 dark:border-gray-600 dark:bg-gray-800" />
            </label>
            <label class="mt-2 block text-sm">{{ t('clinical.content.fields.czechSummary') }}
              <textarea v-model="lesson.czechSummary" :data-testid="`czech-summary-${index}`" rows="2"
                        class="mt-1 w-full rounded border border-gray-300 px-2 py-1 dark:border-gray-600 dark:bg-gray-800"></textarea>
            </label>
            <div class="mt-2">
              <p class="text-sm">{{ t('clinical.content.fields.czechBody') }}</p>
              <MarkdownEditor v-model="lesson.czechBodyMarkdown" />
            </div>
          </div>
        </div>
      </div>

      <button type="button" data-testid="add-lesson" class="rounded border px-3 py-1 text-sm" @click="addLesson">
        {{ t('clinical.content.editor.addLesson') }}
      </button>

      <div class="flex gap-2">
        <button type="submit" data-testid="save" :disabled="saving || hasIncompleteRows"
                class="rounded bg-blue-600 px-3 py-1 text-sm text-white disabled:opacity-50">
          {{ t('common.save') }}
        </button>
        <router-link :to="`/clinical/content/${moduleSlug}/${version}`"
                     class="rounded border px-3 py-1 text-sm">{{ t('common.cancel') }}</router-link>
      </div>
    </form>
  </section>
</template>
```

In `frontend/src/router/index.ts`: add the import and child route AFTER `content/:moduleSlug/:version`:

```ts
import ClinicalContentEditView from '@/views/clinical/ClinicalContentEditView.vue'
```

```ts
      { path: 'content/:moduleSlug/:version/edit', component: ClinicalContentEditView },
```

In `frontend/src/views/clinical/ClinicalContentDetailView.vue`: add the `editable` computed next to the other action computeds:

```ts
const editable = computed(() => detail.value?.status === 'DRAFT' || detail.value?.status === 'REJECTED')
```

and the Edit button inside the action bar `<div class="mt-6 flex flex-wrap gap-2">`, after the copy button:

```vue
        <router-link v-if="editable" :to="`/clinical/content/${moduleSlug}/${version}/edit`"
                     data-testid="edit-content" class="rounded border px-3 py-1 text-sm">
          {{ t('clinical.content.actions.edit') }}
        </router-link>
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `cd frontend && npm run test -- tests/views/clinical/ClinicalContentEditView.test.ts`
Expected: PASS (3 tests).

- [ ] **Step 6: Commit**

```bash
git add frontend/src/components/MarkdownEditor.vue frontend/src/views/clinical/ClinicalContentEditView.vue frontend/src/views/clinical/ClinicalContentDetailView.vue frontend/src/router/index.ts frontend/tests/views/clinical/ClinicalContentEditView.test.ts
git commit -m "Add clinical content draft editor with server-rendered preview"
```

---

### Task 6: New module view

**Files:**
- Create: `frontend/src/views/clinical/ClinicalContentNewView.vue`
- Modify: `frontend/src/router/index.ts` (import + child route `content/new`)
- Test: `frontend/tests/views/clinical/ClinicalContentNewView.test.ts`

**Interfaces:**
- Consumes: `contentEducationApi.createModule` from Task 2; the edit route from Task 5 (navigates to `/clinical/content/{slug}/{version}/edit` on success, so Task 5 must land first).
- Produces: route `/clinical/content/new`.

- [ ] **Step 1: Write the failing test**

Create `frontend/tests/views/clinical/ClinicalContentNewView.test.ts`:

```ts
import { flushPromises, mount } from '@vue/test-utils'
import { http, HttpResponse } from 'msw'
import { beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { createI18n } from 'vue-i18n'
import { createRouter, createMemoryHistory } from 'vue-router'
import { server } from '../../msw/server'
import ClinicalContentNewView from '@/views/clinical/ClinicalContentNewView.vue'
import en from '@/i18n/en.json'
import cs from '@/i18n/cs.json'

const i18n = createI18n({ legacy: false, locale: 'en', messages: { en, cs } })

function makeRouter() {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/clinical/content/new', component: ClinicalContentNewView },
      { path: '/clinical/content/:moduleSlug/:version/edit', component: { template: '<div />' } },
    ],
  })
}

describe('ClinicalContentNewView', () => {
  beforeEach(() => setActivePinia(createPinia()))

  it('creates the module and opens the editor', async () => {
    let received: unknown
    server.use(
      http.get('/api/csrf', () => HttpResponse.json({ token: 't', headerName: 'X-XSRF-TOKEN' })),
      http.post('/api/content/education/modules', async ({ request }) => {
        received = await request.json()
        return HttpResponse.json({ moduleSlug: 'ibd-basics', version: 1 })
      }),
    )
    const router = makeRouter()
    await router.push('/clinical/content/new')
    const wrapper = mount(ClinicalContentNewView, { global: { plugins: [createPinia(), i18n, router] } })
    await flushPromises()

    await wrapper.find('[data-testid="slug"]').setValue('ibd-basics')
    await wrapper.find('[data-testid="topic"]').setValue('IBD')
    await wrapper.find('[data-testid="english-title"]').setValue('IBD Basics')
    await wrapper.find('[data-testid="english-summary"]').setValue('Overview.')
    await wrapper.find('[data-testid="create"]').trigger('submit')
    await flushPromises()

    expect(received).toEqual({
      slug: 'ibd-basics',
      topic: 'IBD',
      sortOrder: 10,
      englishTitle: 'IBD Basics',
      englishSummary: 'Overview.',
      czechTitle: null,
      czechSummary: null,
    })
    expect(router.currentRoute.value.path).toBe('/clinical/content/ibd-basics/1/edit')
  })

  it('shows field errors from the server', async () => {
    server.use(
      http.get('/api/csrf', () => HttpResponse.json({ token: 't', headerName: 'X-XSRF-TOKEN' })),
      http.post('/api/content/education/modules', () =>
        HttpResponse.json(
          { error: 'validation_failed', fields: { slug: 'must be unique' } },
          { status: 400 },
        )),
    )
    const router = makeRouter()
    await router.push('/clinical/content/new')
    const wrapper = mount(ClinicalContentNewView, { global: { plugins: [createPinia(), i18n, router] } })
    await flushPromises()

    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(wrapper.text()).toContain('must be unique')
  })
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && npm run test -- tests/views/clinical/ClinicalContentNewView.test.ts`
Expected: FAIL — `@/views/clinical/ClinicalContentNewView.vue` cannot be resolved.

- [ ] **Step 3: Implement the view and route**

Create `frontend/src/views/clinical/ClinicalContentNewView.vue`:

```vue
<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import FieldError from '@/components/FieldError.vue'
import { contentEducationApi } from '@/api/contentEducation'
import { useApiError } from '@/composables/useApiError'

const { t } = useI18n()
const router = useRouter()
const { message, fieldErrors, capture, clear } = useApiError()

const form = reactive({
  slug: '',
  topic: '',
  sortOrder: 10,
  englishTitle: '',
  englishSummary: '',
  czechTitle: '',
  czechSummary: '',
})
const saving = ref(false)

async function submit() {
  saving.value = true
  clear()
  try {
    const created = await contentEducationApi.createModule({
      slug: form.slug.trim(),
      topic: form.topic.trim(),
      sortOrder: form.sortOrder,
      englishTitle: form.englishTitle.trim(),
      englishSummary: form.englishSummary.trim(),
      czechTitle: form.czechTitle.trim() || null,
      czechSummary: form.czechSummary.trim() || null,
    })
    await router.push(`/clinical/content/${created.moduleSlug}/${created.version}/edit`)
  } catch (e) {
    capture(e)
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <section class="max-w-xl">
    <router-link to="/clinical/content" class="text-sm text-blue-600 dark:text-blue-400">
      ← {{ t('clinical.content.backToList') }}
    </router-link>
    <h1 class="mt-2 text-2xl font-semibold">{{ t('clinical.content.create.title') }}</h1>

    <p v-if="message" class="mt-4 rounded bg-red-50 p-3 text-sm text-red-700 dark:bg-red-950 dark:text-red-300">{{ message }}</p>

    <form class="mt-4 space-y-3" @submit.prevent="submit">
      <label class="block text-sm">{{ t('clinical.content.fields.slug') }}
        <input v-model="form.slug" data-testid="slug" type="text" required
               class="mt-1 w-full rounded border border-gray-300 px-2 py-1 dark:border-gray-600 dark:bg-gray-800" />
        <FieldError :message="fieldErrors.slug" />
      </label>
      <label class="block text-sm">{{ t('clinical.content.fields.topic') }}
        <input v-model="form.topic" data-testid="topic" type="text" required
               class="mt-1 w-full rounded border border-gray-300 px-2 py-1 dark:border-gray-600 dark:bg-gray-800" />
        <FieldError :message="fieldErrors.topic" />
      </label>
      <label class="block text-sm">{{ t('clinical.content.fields.sortOrder') }}
        <input v-model.number="form.sortOrder" data-testid="sort-order" type="number" min="1" required
               class="mt-1 w-full rounded border border-gray-300 px-2 py-1 dark:border-gray-600 dark:bg-gray-800" />
        <FieldError :message="fieldErrors.sortOrder" />
      </label>
      <label class="block text-sm">{{ t('clinical.content.fields.englishTitle') }}
        <input v-model="form.englishTitle" data-testid="english-title" type="text" required
               class="mt-1 w-full rounded border border-gray-300 px-2 py-1 dark:border-gray-600 dark:bg-gray-800" />
        <FieldError :message="fieldErrors.englishTitle" />
      </label>
      <label class="block text-sm">{{ t('clinical.content.fields.englishSummary') }}
        <textarea v-model="form.englishSummary" data-testid="english-summary" rows="3" required
                  class="mt-1 w-full rounded border border-gray-300 px-2 py-1 dark:border-gray-600 dark:bg-gray-800"></textarea>
        <FieldError :message="fieldErrors.englishSummary" />
      </label>
      <label class="block text-sm">{{ t('clinical.content.fields.czechTitle') }}
        <input v-model="form.czechTitle" data-testid="czech-title" type="text"
               class="mt-1 w-full rounded border border-gray-300 px-2 py-1 dark:border-gray-600 dark:bg-gray-800" />
        <FieldError :message="fieldErrors.czechTitle" />
      </label>
      <label class="block text-sm">{{ t('clinical.content.fields.czechSummary') }}
        <textarea v-model="form.czechSummary" data-testid="czech-summary" rows="3"
                  class="mt-1 w-full rounded border border-gray-300 px-2 py-1 dark:border-gray-600 dark:bg-gray-800"></textarea>
        <FieldError :message="fieldErrors.czechSummary" />
      </label>

      <button type="submit" data-testid="create" :disabled="saving"
              class="rounded bg-blue-600 px-3 py-1 text-sm text-white disabled:opacity-50">
        {{ t('clinical.content.create.submit') }}
      </button>
    </form>
  </section>
</template>
```

In `frontend/src/router/index.ts`: add the import and register `content/new` BEFORE the `content/:moduleSlug/:version` child route:

```ts
import ClinicalContentNewView from '@/views/clinical/ClinicalContentNewView.vue'
```

```ts
      { path: 'content/new', component: ClinicalContentNewView },
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd frontend && npm run test -- tests/views/clinical/ClinicalContentNewView.test.ts`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add frontend/src/views/clinical/ClinicalContentNewView.vue frontend/src/router/index.ts frontend/tests/views/clinical/ClinicalContentNewView.test.ts
git commit -m "Add clinical education module creation view"
```

---

### Task 7: Full verification

**Files:** none (verification only; fix-forward any regressions found).

- [ ] **Step 1: Backend suite**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: Frontend suite, types, and production build**

Run: `cd frontend && npm run test && npm run typecheck && npm run build`
Expected: all pass.

- [ ] **Step 3: Commit any regression fixes**

If either step surfaced failures fixed in this task:

```bash
git add -A
git commit -m "Fix regressions from clinical content management feature"
```

If nothing failed, no commit is needed — note that in the final summary.
