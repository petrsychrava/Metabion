# Diet Log Photo File Storage Design

## Purpose

Replace diet-log photo metadata placeholders with real private image upload, storage, and serving.

The first implementation stores images on the application server filesystem behind a storage interface that can later be backed by object storage. It is scoped to diet-log photos only, not a generic document-management subsystem.

## Current Context

Diet logs currently accept `photoReferences` containing client-supplied metadata such as original filename, content type, byte size, and storage key. The web form exposes these fields directly to patients. This was acceptable for the prior metadata-only phase, but it leaks storage concerns into the patient workflow and does not support real meal-photo logging.

The existing diet-log implementation already has:

- `DailyDietLog` as the aggregate root for meals, deviations, and photo references.
- `DailyDietLogPhotoReference` as a child entity.
- `DietLogService` for patient and clinical workflows.
- `DietLogRequestMapper` for request-to-entity mapping.
- `StorageKeyValidator` for the old placeholder storage-key model.
- REST and Thymeleaf endpoints for patient and clinical diet-log views.

Compatibility with the current photo metadata API is not required because the app is not in production use.

## Product Scope

In scope:

- Patient staged image upload for diet-log photos.
- Drag-and-drop-friendly upload flow in the web form.
- Local private filesystem storage as the first backend.
- Storage abstraction so S3, MinIO, or another object store can replace local storage later.
- Private authenticated image serving.
- Patient, assigned clinical staff, and admin image read access.
- Server-derived photo metadata.
- Captions attached when saving the diet log.
- Pending upload cleanup after 24 hours.
- Hard deletion of abandoned pending files.
- Schema support for future attached-photo removal audit.

Out of scope:

- Public URLs or signed URLs.
- Direct filesystem paths in API responses.
- Object storage implementation.
- Image resizing, thumbnail generation, transcoding, or EXIF stripping.
- Malware scanning.
- User-facing deletion of attached photos.
- Generic media library for other domains.
- PDF or arbitrary attachment upload.

## Architecture

Add a focused diet-log photo subsystem:

- `FileStorageService`: interface for storing, reading, and deleting file bytes by opaque storage key.
- `LocalFileStorageService`: local filesystem implementation. It writes below a configured private root and never under `src`, `build`, or static web resources.
- `DietLogPhotoService`: owns staged upload validation, metadata creation, pending-to-attached transitions, content-read authorization, and cleanup.
- `DietLogPhotoController`: REST endpoints for staged upload and private content streaming.
- `DietLogService`: continues to own diet-log saves, but delegates photo attachment to `DietLogPhotoService`.
- `DailyDietLogPhotoReference`: evolves from placeholder metadata into the stored image record.

The storage service boundary must not expose local paths to callers. Diet-log code works with database IDs and opaque storage keys only.

## Data Model

Update `daily_diet_log_photo_references` through Flyway.

Fields:

- `id`
- `patient_profile_id`, required
- `uploaded_by_user_id`, required
- `daily_diet_log_id`, nullable while pending
- `meal_id`, nullable
- `status`, required: `PENDING`, `ATTACHED`, `REMOVED`
- `original_filename`, server-derived
- `content_type`, server-derived
- `size_bytes`, server-derived
- `sha256`, server-derived
- `storage_key`, server-generated opaque key
- `caption`, user-editable on diet-log save
- `sort_order`
- `created_at`
- `attached_at`
- `removed_at`, nullable future audit field
- `removed_by_user_id`, nullable future audit field

Lifecycle:

- `PENDING`: uploaded but not attached to a diet log.
- `ATTACHED`: associated with a saved diet log and visible in normal diet-log responses.
- `REMOVED`: reserved for future user deletion. Removed rows keep audit metadata and are hidden from normal views.

Pending rows have no `daily_diet_log_id`. Saving a diet log attaches submitted pending upload IDs after verifying ownership. Already attached photos for the same log remain attached when a diet log is saved again; this phase does not remove attached photos through omission. Submitted existing photo IDs for the same log update caption and sort order. Abandoned pending rows older than the configured retention are hard-deleted with their files.

Attached photo deletion is not exposed in this phase. When deletion is added later, the physical file will be deleted and the database row will move to `REMOVED` with audit metadata.

## Upload Flow

Use staged upload.

1. Patient opens the diet-log form.
2. Patient drops or selects image files.
3. Browser uploads each file immediately.
4. Server validates authentication, role, file size, content type, and file signature.
5. Server stores the bytes through `FileStorageService`.
6. Server creates a `PENDING` `DailyDietLogPhotoReference` for the current patient.
7. Server returns upload metadata including `uploadId`, original filename, content type, size, and private content URL. `uploadId` is the database photo row ID while the row is pending.
8. Browser keeps hidden uploaded-photo IDs and lets the patient edit captions.
9. Diet-log save submits uploaded-photo IDs and captions. Existing attached photo IDs for the same log can also be submitted to update caption and ordering.
10. `DietLogPhotoService` attaches valid uploads to the saved log, sets ordering for submitted photos, updates captions, and rejects cross-patient or invalid references.

This flow supports drag/drop, progress UI, and form validation errors without forcing the patient to reselect files.

## API Design

Add:

- `POST /api/diet-log-photos/uploads`
  - multipart form field: `file`
  - patient only
  - returns `uploadId`, `originalFilename`, `contentType`, `sizeBytes`, `caption`, and `contentUrl`
- `GET /api/diet-log-photos/{photoId}/content`
  - streams image bytes
  - allowed for owning patient, assigned clinical staff, and admin

Change diet-log save requests:

- Replace client-supplied photo metadata with uploaded photo references.
- Each submitted photo reference contains `uploadId` and optional `caption`.
- Clients cannot submit `storageKey`, `contentType`, `sizeBytes`, or `originalFilename`.

Change diet-log responses:

- Return photo `id`, `originalFilename`, `contentType`, `sizeBytes`, `caption`, `sortOrder`, and `contentUrl`.
- Do not return raw storage keys or filesystem paths.

No photo delete endpoint is added in this phase.

## Web Workflow

Patient form:

- Replace visible photo metadata inputs with a photo upload area.
- Support normal file selection and drag/drop enhancement.
- Use staged upload to create pending uploads before final diet-log save.
- Store uploaded IDs in hidden fields.
- Show uploaded image preview or filename and editable caption.
- Enforce max 10 photos per diet log in the browser and on the server.

Clinical detail:

- Show attached photos as private image links or previews using `contentUrl`.
- Do not show storage keys.

The web form must not render fields named `storageKey`, `contentType`, `sizeBytes`, or `originalFilename` for patient input.

## Validation

Initial constraints:

- Allowed image types: JPEG, PNG, WebP.
- Max file size: 10 MB per image.
- Max attached photos: 10 per diet log.
- Empty files are rejected.
- Store original bytes unchanged.
- Validate file signatures rather than trusting only the browser-supplied MIME type.
- Derive metadata server-side.
- Captions remain bounded by the existing 500-character limit.

Unsupported images return a validation error. Oversized uploads return either `413 Payload Too Large` through Spring multipart handling or a controlled `400` response if caught at the application boundary.

## Authorization And Security

Upload:

- Authenticated patient only.
- Patient ownership is resolved from the current principal, not supplied by the request.

Content read:

- Owning patient can read attached photos and their own pending photos.
- Clinical users can read attached photos only when `AccessControlService.canAccessPatientProfile` allows access to the patient.
- Admin can read attached photos for any patient.
- Unrelated patients and staff are denied.

Storage:

- Local storage root is configurable.
- Files are stored outside source, build, and public static directories.
- Storage keys are generated by the server and opaque.
- No public URL, direct path, signed URL, credential, token, or session identifier is stored or returned.
- Application code must not log file contents, raw diet-log payloads, sensitive metadata, or storage keys.

Missing local file:

- Return `404` for content requests.
- Log a minimal operational warning without patient details.

## Configuration

Add properties:

```properties
metabion.storage.local.root=${METABION_STORAGE_ROOT:./var/metabion-storage}
metabion.diet-log-photos.max-file-size=10MB
metabion.diet-log-photos.max-photos-per-log=10
metabion.diet-log-photos.pending-retention=24h
```

The local root default is for development. Production deployments set `METABION_STORAGE_ROOT` to a persistent private volume.

## Cleanup

Add a scheduled cleanup path for abandoned pending uploads:

- Find `PENDING` photo rows older than the configured retention.
- Delete their physical files through `FileStorageService`.
- Hard-delete their database rows.

Cleanup must tolerate missing files and continue processing remaining rows.

## Testing

Storage tests:

- Local storage writes below the configured root.
- Stored bytes can be read back.
- Delete removes the stored file and tolerates missing files where appropriate.
- Path traversal is impossible through generated keys.

Service tests:

- Upload rejects unauthenticated users and non-patient users.
- Upload rejects empty files, unsupported signatures, and oversized files.
- Upload creates `PENDING` rows for the current patient.
- Diet-log save attaches current patient pending uploads.
- Diet-log save rejects cross-patient upload IDs.
- Diet-log save enforces max 10 photos.
- Re-saving a diet log preserves already attached photos when no deletion action is submitted.
- Re-saving a diet log can update captions and ordering for submitted existing photo IDs from the same log.
- Pending cleanup hard-deletes old pending rows and files.

Authorization tests:

- Owning patient can read own attached photo content.
- Owning patient can read own pending upload content.
- Assigned clinical staff can read attached photo content.
- Unassigned clinical staff cannot read attached photo content.
- Admin can read attached photo content.
- Other patients cannot read photo content.

Controller/web tests:

- Upload endpoint accepts valid JPEG, PNG, and WebP samples.
- Content endpoint streams with the stored content type.
- Patient diet-log page no longer renders raw metadata inputs.
- Patient diet-log page renders staged-upload controls and hidden upload IDs.
- Clinical detail renders private photo links or previews.

Final verification command:

```bash
./gradlew test
```

## Rollout

This is an intentional breaking change to the existing photo metadata contract.

Because the application is not yet in use, no backward-compatible API path or data migration for existing user-uploaded files is required. Existing placeholder photo metadata rows in development databases will not be migrated; local development databases can be reset.
