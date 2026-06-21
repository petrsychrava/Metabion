# Education Content Library And Versioning Design

## Goal

Implement a structured educational content library for IBD and ketogenic nutrition with reviewed content versioning, Czech/English localization, patient completion tracking, REST APIs, and Thymeleaf webapp pages.

This covers:

- FR-021: structured modules for IBD and ketogenic nutrition.
- FR-022: versioned and reviewed content before publication.
- FR-023: educational module completion tracking.
- FR-024: Czech and English content versions.
- FR-064: admin educational content management.
- NFR-030: Czech and English UI text.
- NFR-031: localized medical and educational content review metadata.

## Product Scope

In scope:

- Published education modules visible to every authenticated role.
- Two-level content structure: modules contain ordered lessons.
- Lesson body content authored as Markdown and rendered as sanitized HTML in the webapp.
- Czech and English lesson localizations.
- English fallback when the requested language is unavailable.
- Version, author, reviewer, approval, and publication metadata.
- Staff and admin content management.
- Staff-authored content requires approval by a different staff or admin user.
- Admin-authored content can be published directly for now.
- Approved staff-authored content can be published by staff or admin, including the original author.
- Patient lesson completion tracking.
- Module completion derived from current published lesson completion.
- REST APIs and Thymeleaf web pages.

Out of scope:

- Quizzes, prerequisites, scoring, certificates, or course enrollment.
- Scheduling publication for a future date.
- Rich text editor integration.
- File or image attachments inside education content.
- Public unauthenticated education pages.
- Per-cohort content targeting.
- Translation workflow beyond storing localized reviewed content versions.
- Full audit-log subsystem beyond content metadata.

## Architecture

Add an `education` feature slice that follows the existing project layering:

- `domain`: module, version, lesson, localization, and completion entities.
- `repository`: Spring Data repositories for published reads, draft/review lists, and progress.
- `service`: content lifecycle, authorization, localized response assembly, Markdown sanitization, and completion tracking.
- `controller/api`: REST endpoints for content reads, patient completion, and content management.
- `controller/web`: Thymeleaf pages for the patient/all-role library and staff/admin management.
- `dto`: request and response records with Jakarta validation.
- `resources/db/migration`: Flyway schema.
- `messages.properties` and `messages_cs.properties`: localized UI labels.

The design separates stable identities from immutable content snapshots. Published content is read from immutable version rows. Editing a published module creates a new draft copied from an existing version rather than mutating the published version.

## Data Model

Add Flyway migration `V11__education_content_library.sql`.

Core entities:

- `EducationModule`
  - Stable module identity.
  - Fields: `id`, `slug`, `topic`, `sort_order`, `current_published_version_id`, `created_at`, `updated_at`.
  - `slug` is unique and normalized lowercase.

- `EducationModuleVersion`
  - Versioned module snapshot.
  - Fields: `id`, `module_id`, `version`, `status`, `author_user_id`, `reviewed_by_user_id`, `published_by_user_id`, `review_bypassed`, `review_notes`, `created_at`, `submitted_at`, `reviewed_at`, `published_at`, `archived_at`.
  - Unique constraint on `(module_id, version)`.
  - Status values: `DRAFT`, `IN_REVIEW`, `APPROVED`, `PUBLISHED`, `ARCHIVED`, `REJECTED`.

- `EducationModuleLocalization`
  - Localized module display metadata for a module version.
  - Fields: `id`, `module_version_id`, `language`, `title`, `summary`.
  - Unique constraint on `(module_version_id, language)`.
  - English is required before submit/review/publish. Czech is optional.

- `EducationLesson`
  - Stable lesson identity inside a module.
  - Fields: `id`, `module_id`, `slug`, `created_at`.
  - Unique constraint on `(module_id, slug)`.

- `EducationLessonVersion`
  - Lesson snapshot inside a module version.
  - Fields: `id`, `module_version_id`, `lesson_id`, `sort_order`.
  - Unique constraints on `(module_version_id, lesson_id)` and `(module_version_id, sort_order)`.

- `EducationLessonLocalization`
  - Localized lesson content.
  - Fields: `id`, `lesson_version_id`, `language`, `title`, `summary`, `body_markdown`.
  - Unique constraint on `(lesson_version_id, language)`.
  - English is required before submit/review/publish. Czech is optional.

- `EducationLessonCompletion`
  - Patient progress for a specific published lesson snapshot.
  - Fields: `id`, `patient_profile_id`, `module_version_id`, `lesson_version_id`, `completed_at`, `created_at`.
  - Unique constraint on `(patient_profile_id, lesson_version_id)`.

Language values use the existing user language preference model: English and Czech. API responses include both `requestedLanguage` and `contentLanguage` for module and lesson localized fields.

## Lifecycle

Content lifecycle:

```text
DRAFT -> IN_REVIEW -> APPROVED -> PUBLISHED -> ARCHIVED
          |
          v
        REJECTED -> DRAFT
```

Rules:

- Staff and admins can create drafts.
- Drafts are editable until submitted for review.
- Staff-authored drafts must be approved by a different staff or admin user.
- Admin-authored drafts can be published directly for now.
- Direct admin publish records the admin in `reviewed_by_user_id`, sets `review_bypassed=true`, and records publication metadata.
- Approval records `reviewed_by_user_id`, `reviewed_at`, and optional `review_notes`.
- Rejection records `reviewed_by_user_id`, `reviewed_at`, and `review_notes`, then sets status to `REJECTED`.
- `REJECTED` versions are editable and can be resubmitted to `IN_REVIEW`.
- Approved content can be published by staff or admin, including the original author.
- Publishing records `published_by_user_id`, `published_at`, updates `EducationModule.current_published_version_id`, and archives the previous published version.
- Published and archived versions are immutable in service code.
- Copying an existing version creates the next draft version number.

## Patient And All-Role Read Flow

Every authenticated role can read published education content.

1. User opens `/app/education` or calls `GET /api/education/modules`.
2. The service loads modules that have a current published version.
3. Modules are sorted by `sort_order`.
4. The service resolves module and lesson localizations for the user's selected language.
5. If requested Czech content is missing, the service falls back to English.
6. Responses include `requestedLanguage` and `contentLanguage`.
7. Patient responses include completion counts and lesson completion flags.
8. Staff/admin/non-patient responses omit patient-specific progress.

All content reads require authentication. Unpublished content is not visible through patient/all-role read endpoints.

## Completion Flow

Patients complete lessons, not modules.

1. Patient calls `POST /api/education/modules/{moduleSlug}/lessons/{lessonSlug}/complete` or submits from the lesson page.
2. The service resolves the current published module version and lesson version.
3. The service verifies the current user has `PATIENT` role and resolves the current patient profile.
4. The service upserts `EducationLessonCompletion`.
5. Module progress is derived by comparing completed current lesson versions to all lessons in the current published module version.

Patients can undo accidental completion with `DELETE /api/education/modules/{moduleSlug}/lessons/{lessonSlug}/complete`. Completion history for older versions remains in the database but does not count toward progress for the current published version.

## REST API

Published read APIs:

- `GET /api/education/modules`
  - Authenticated users.
  - Returns published module summaries, version metadata, localized title/summary, lesson count, and patient progress where applicable.

- `GET /api/education/modules/{moduleSlug}`
  - Authenticated users.
  - Returns the current published module version, ordered lessons, localized Markdown body, publication metadata, and patient completion state where applicable.

Patient completion APIs:

- `POST /api/education/modules/{moduleSlug}/lessons/{lessonSlug}/complete`
  - Patient only.
  - Marks the current published lesson version complete.

- `DELETE /api/education/modules/{moduleSlug}/lessons/{lessonSlug}/complete`
  - Patient only.
  - Removes completion for the current published lesson version.

Content management APIs:

- `GET /api/content/education/modules`
  - Staff/admin only.
  - Lists draft, in-review, approved, published, archived, and rejected versions.

- `POST /api/content/education/modules`
  - Staff/admin only.
  - Creates a new module and first draft version.

- `POST /api/content/education/modules/{moduleSlug}/versions/{version}/lessons`
  - Staff/admin only.
  - Adds or replaces draft lesson content and localizations.

- `POST /api/content/education/modules/{moduleSlug}/versions/{version}/submit-review`
  - Staff/admin only.
  - Moves `DRAFT` or `REJECTED` to `IN_REVIEW`.

- `POST /api/content/education/modules/{moduleSlug}/versions/{version}/approve`
  - Staff/admin only.
  - Moves `IN_REVIEW` to `APPROVED`.
  - Staff authors cannot approve their own content.

- `POST /api/content/education/modules/{moduleSlug}/versions/{version}/reject`
  - Staff/admin only.
  - Records review notes and returns content to an editable rejected/draft state.

- `POST /api/content/education/modules/{moduleSlug}/versions/{version}/publish`
  - Staff/admin only.
  - Publishes `APPROVED` content, or admin-authored draft content when current user is admin.

- `POST /api/content/education/modules/{moduleSlug}/versions/{version}/copy`
  - Staff/admin only.
  - Copies an existing version into the next draft version.

API DTOs expose IDs for management screens and completion rows. Primary navigation uses stable module and lesson slugs.

## Webapp

Patient/all-role pages:

- `/app/education`
  - Lists published modules for all authenticated roles.
  - Patients see completion progress.
  - Staff/admin see the same published content without patient progress.

- `/app/education/{moduleSlug}`
  - Shows current published version metadata.
  - Shows ordered lessons with localized Markdown-rendered content.
  - Shows fallback language indicator when requested Czech content falls back to English.
  - Shows patient completion controls when the current user is a patient.

Staff/admin content pages:

- `/app/content/education`
  - Lists module versions by status.

- `/app/content/education/new`
  - Creates a new module draft.

- `/app/content/education/{moduleSlug}/versions/{version}/edit`
  - Edits draft metadata, lessons, sort order, and EN/CS Markdown localizations.

- `/app/content/education/{moduleSlug}/versions/{version}`
  - Read-only management detail with lifecycle actions.

Form actions:

- Submit review.
- Approve.
- Reject with notes.
- Publish.
- Copy to new draft.

The sidebar links for `Education library` and `Content management` become active routes. Labels and page text are added to both message bundles.

## Validation

Validation rules:

- Module slugs and lesson slugs are normalized lowercase identifiers.
- Module slug is globally unique.
- Lesson slug is unique within a module.
- Sort order is positive and unique within a module version.
- A module version must have at least one lesson before submit, approval, or publication.
- English module localization is required before submit, approval, or publication.
- English lesson localization is required for every lesson before submit, approval, or publication.
- Czech localization is optional.
- Titles and summaries have bounded lengths.
- Markdown body has bounded length.
- Published and archived versions cannot be edited.
- Completion can only target the current published lesson version.

Markdown safety:

- Store Markdown source in the database.
- Render sanitized HTML for web views only.
- API responses return Markdown source, not rendered HTML.
- Unsafe HTML/script input must not execute in rendered web pages.

## Authorization And Errors

Authorization:

- Unauthenticated users receive `401`.
- Published content reads require authentication and are available to all roles.
- Completion mutations require `PATIENT`.
- Content management requires staff or admin.
- Staff authors cannot approve their own staff-authored content.
- Admin-authored content can publish directly for now.
- Staff/admin can publish approved content, including content they authored.

Errors:

- Missing published modules return `404`.
- Unpublished content is invisible from read endpoints.
- Unauthorized management actions return `403`.
- Invalid lifecycle transitions return `400`.
- Validation failures use the existing validation error envelope.

Security considerations:

- Keep CSRF enabled for web forms and protected API mutations.
- Do not log lesson bodies in validation or lifecycle errors.
- Do not expose unpublished medical content through all-role read endpoints.
- Keep publication and approval metadata in database rows for accountability.

## Testing

Service tests:

- Create draft module with lessons and localizations.
- Submit draft for review.
- Reject in-review content with notes.
- Approve in-review content.
- Staff author cannot approve own content.
- Staff author can publish after another reviewer approves.
- Admin can publish own draft directly.
- Publishing archives the previous published version.
- Copying a published version creates the next draft version.
- Published versions are immutable.
- Czech preference returns Czech content when present.
- Czech preference falls back to English when Czech is missing.
- Fallback responses expose `requestedLanguage` and `contentLanguage`.
- Patient can complete and uncomplete current published lessons.
- Module progress is derived from current published lesson versions.
- Non-patient completion writes are forbidden.
- Unsafe Markdown is sanitized in the web rendering path.

Controller tests:

- Read endpoints are available to patient, clinical staff, and admin users.
- Unauthenticated read requests return `401`.
- Completion endpoints require patient role and CSRF for mutation.
- Content management endpoints require staff/admin.
- Review endpoints enforce different reviewer for staff-authored content.
- Web education list/detail pages render published content and progress.
- Web content management pages render lifecycle actions appropriate to version state.

Repository/integration tests:

- Flyway schema validates with the existing Postgres Testcontainers pattern.
- Unique constraints prevent duplicate module slugs.
- Unique constraints prevent duplicate version numbers per module.
- Unique constraints prevent duplicate localizations.
- Unique constraints prevent duplicate lesson completions.
- End-to-end flow: staff creates draft, different staff approves, author publishes, patient reads localized content and marks completion.

Final verification command:

```bash
./gradlew test
```

## Rollout

This is a new subsystem. No backward compatibility migration is required for education content because no existing production content library is present.

Seed content can be added in a later migration or through the management UI. The first implementation includes test fixtures for structured IBD and ketogenic nutrition module paths, but it does not commit production medical copy unless that copy is explicitly provided and approved.
