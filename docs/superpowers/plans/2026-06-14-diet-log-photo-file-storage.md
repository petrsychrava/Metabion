# Diet Log Photo File Storage Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace client-entered diet-log photo metadata with staged private image upload, local filesystem storage, authenticated serving, and diet-log attachment.

**Architecture:** Add a focused diet-log photo subsystem. `FileStorageService` hides storage backend details, `DietLogPhotoService` owns upload lifecycle and authorization, and `DietLogService` delegates photo attachment inside the same transaction as diet-log save. Existing diet-log API and web forms change to submit uploaded photo IDs instead of storage metadata.

**Tech Stack:** Java 25, Spring Boot 4.0.6, Spring MVC multipart upload, Spring Security, Spring Data JPA, Flyway, H2/PostgreSQL-compatible SQL, JUnit 5, Mockito, MockMvc.

---

## File Structure

Create:

- `src/main/java/com/metabion/domain/DietLogPhotoStatus.java`: `PENDING`, `ATTACHED`, `REMOVED`.
- `src/main/java/com/metabion/dto/DietLogPhotoUploadResponse.java`: upload/content metadata response.
- `src/main/java/com/metabion/dto/FileStorageResource.java`: storage read result with stream, content type, size.
- `src/main/java/com/metabion/dto/StoredFile.java`: storage write result with generated key, size, SHA-256.
- `src/main/java/com/metabion/service/FileStorageService.java`: storage backend interface.
- `src/main/java/com/metabion/service/LocalFileStorageService.java`: local filesystem implementation.
- `src/main/java/com/metabion/service/DietLogPhotoProperties.java`: typed configuration for file size/count/retention.
- `src/main/java/com/metabion/service/DietLogPhotoService.java`: upload validation, lifecycle, authorization, cleanup.
- `src/main/java/com/metabion/controller/api/DietLogPhotoController.java`: upload and private content endpoints.
- `src/main/java/com/metabion/repository/DailyDietLogPhotoReferenceRepository.java`: photo queries for upload/attach/read/cleanup.
- `src/main/resources/db/migration/V10__diet_log_photo_storage.sql`: schema evolution.
- `src/test/java/com/metabion/service/LocalFileStorageServiceTest.java`: local storage unit tests.
- `src/test/java/com/metabion/service/DietLogPhotoServiceTest.java`: lifecycle/authorization tests.
- `src/test/java/com/metabion/controller/api/DietLogPhotoControllerTest.java`: upload/content endpoint tests.

Modify:

- `src/main/java/com/metabion/Main.java`: enable configuration properties and scheduling if not already enabled.
- `src/main/java/com/metabion/domain/DailyDietLog.java`: stop orphan-removing photo references through child replacement; photos are attached by service.
- `src/main/java/com/metabion/domain/DailyDietLogPhotoReference.java`: add ownership, status, hash, timestamps, removal audit fields.
- `src/main/java/com/metabion/dto/DailyDietLogRequest.java`: replace `PhotoReferenceRequest` metadata with `PhotoUploadReferenceRequest`.
- `src/main/java/com/metabion/dto/DailyDietLogResponse.java`: return `contentUrl`, not storage key.
- `src/main/java/com/metabion/dto/DietLogForm.java`: replace raw metadata rows with uploaded photo rows.
- `src/main/java/com/metabion/service/DietLogRequestMapper.java`: stop mapping photo metadata.
- `src/main/java/com/metabion/service/DietLogResponseAssembler.java`: construct photo content URLs.
- `src/main/java/com/metabion/service/DietLogService.java`: inject `DietLogPhotoService` and attach photos after saving log.
- `src/main/java/com/metabion/controller/web/WebDietLogController.java`: hydrate uploaded photo rows and preserve them on validation errors.
- `src/main/resources/application.properties`: add storage/photo properties and multipart limits.
- `src/main/resources/templates/diet-logs.html`: replace metadata inputs with upload controls/hidden IDs.
- `src/main/resources/templates/clinical-diet-log-detail.html`: render private image links/previews instead of metadata-only details.
- `src/main/resources/messages.properties` and `src/main/resources/messages_cs.properties`: update photo labels.
- Existing tests under `src/test/java/com/metabion/service`, `controller/api`, `controller/web`, `dto`, and `repository`: update expectations for new photo contract.

Do not commit `.superpowers/` brainstorming artifacts.

---

### Task 1: Schema And Domain Model

**Files:**
- Create: `src/main/java/com/metabion/domain/DietLogPhotoStatus.java`
- Modify: `src/main/java/com/metabion/domain/DailyDietLogPhotoReference.java`
- Modify: `src/main/java/com/metabion/domain/DailyDietLog.java`
- Create: `src/main/resources/db/migration/V10__diet_log_photo_storage.sql`
- Test: `src/test/java/com/metabion/repository/DailyDietLogRepositoryTest.java`

- [ ] **Step 1: Write failing repository tests for photo lifecycle fields**

Add imports to `DailyDietLogRepositoryTest`:

```java
import com.metabion.domain.DietLogPhotoStatus;
import java.time.Instant;
```

Add this test:

```java
@Test
void photoReferenceStoresUploadLifecycleMetadata() {
    var patient = createPatient("photo-lifecycle@example.com");
    var uploader = patient.getUser();
    var log = new DailyDietLog(patient, LocalDate.of(2026, 6, 10));
    log.setAdherenceLevel(DietAdherenceLevel.MOSTLY);
    log.setAppetiteLevel(AppetiteLevel.NORMAL);

    var photo = DailyDietLogPhotoReference.pending(
            patient,
            uploader,
            "plate.jpg",
            "image/jpeg",
            1234L,
            "0f4e2a",
            "diet-log-photos/10/2026/06/14/file.jpg");
    photo.attachTo(log, "Lunch plate", 0);
    log.addPhotoReference(photo);

    entityManager.persist(log);
    entityManager.flush();
    entityManager.clear();

    var saved = dailyDietLogs.findByPatientProfileIdAndLogDate(patient.getId(), LocalDate.of(2026, 6, 10))
            .orElseThrow();

    assertThat(saved.getPhotoReferences()).singleElement()
            .satisfies(savedPhoto -> {
                assertThat(savedPhoto.getPatientProfile().getId()).isEqualTo(patient.getId());
                assertThat(savedPhoto.getUploadedByUser().getId()).isEqualTo(uploader.getId());
                assertThat(savedPhoto.getStatus()).isEqualTo(DietLogPhotoStatus.ATTACHED);
                assertThat(savedPhoto.getOriginalFilename()).isEqualTo("plate.jpg");
                assertThat(savedPhoto.getContentType()).isEqualTo("image/jpeg");
                assertThat(savedPhoto.getSizeBytes()).isEqualTo(1234L);
                assertThat(savedPhoto.getSha256()).isEqualTo("0f4e2a");
                assertThat(savedPhoto.getStorageKey()).isEqualTo("diet-log-photos/10/2026/06/14/file.jpg");
                assertThat(savedPhoto.getCaption()).isEqualTo("Lunch plate");
                assertThat(savedPhoto.getAttachedAt()).isNotNull();
                assertThat(savedPhoto.getRemovedAt()).isNull();
            });
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```bash
./gradlew test --tests com.metabion.repository.DailyDietLogRepositoryTest.photoReferenceStoresUploadLifecycleMetadata
```

Expected: FAIL because `DietLogPhotoStatus`, `pending`, and new fields do not exist.

- [ ] **Step 3: Add status enum**

Create `src/main/java/com/metabion/domain/DietLogPhotoStatus.java`:

```java
package com.metabion.domain;

public enum DietLogPhotoStatus {
    PENDING,
    ATTACHED,
    REMOVED
}
```

- [ ] **Step 4: Replace photo entity with lifecycle-aware entity**

Update `DailyDietLogPhotoReference` to include these imports:

```java
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;

import java.time.Instant;
```

Then add these fields and methods:

```java
@ManyToOne(fetch = FetchType.LAZY)
@JoinColumn(name = "patient_profile_id", nullable = false)
private PatientProfile patientProfile;

@ManyToOne(fetch = FetchType.LAZY)
@JoinColumn(name = "uploaded_by_user_id", nullable = false)
private User uploadedByUser;

@Enumerated(EnumType.STRING)
@Column(nullable = false, length = 20)
private DietLogPhotoStatus status = DietLogPhotoStatus.PENDING;

@Column(name = "sha256", nullable = false, length = 64)
private String sha256;

@Column(name = "created_at", nullable = false, updatable = false)
private Instant createdAt = Instant.now();

@Column(name = "attached_at")
private Instant attachedAt;

@Column(name = "removed_at")
private Instant removedAt;

@ManyToOne(fetch = FetchType.LAZY)
@JoinColumn(name = "removed_by_user_id")
private User removedByUser;

public static DailyDietLogPhotoReference pending(
        PatientProfile patientProfile,
        User uploadedByUser,
        String originalFilename,
        String contentType,
        Long sizeBytes,
        String sha256,
        String storageKey) {
    var photo = new DailyDietLogPhotoReference(
            originalFilename,
            contentType,
            sizeBytes,
            storageKey,
            null,
            0);
    photo.patientProfile = patientProfile;
    photo.uploadedByUser = uploadedByUser;
    photo.status = DietLogPhotoStatus.PENDING;
    photo.sha256 = sha256;
    return photo;
}

public void attachTo(DailyDietLog log, String caption, int sortOrder) {
    setDailyDietLog(log);
    this.status = DietLogPhotoStatus.ATTACHED;
    this.caption = caption;
    this.sortOrder = sortOrder;
    if (this.attachedAt == null) {
        this.attachedAt = Instant.now();
    }
}
```

Add getters/setters for all new fields following the existing style.

- [ ] **Step 5: Update `DailyDietLog` child replacement**

Change `replaceChildren` to stop replacing photos:

```java
public void replaceChildren(
        List<DailyDietLogMeal> meals,
        List<DailyDietLogDeviation> deviations) {
    replaceMeals(meals);
    replaceDeviations(deviations);
}
```

Leave `addPhotoReference` and `getPhotoReferences` intact. Delete the `replacePhotoReferences` method in this same step.

- [ ] **Step 6: Add Flyway migration**

Create `src/main/resources/db/migration/V10__diet_log_photo_storage.sql`:

```sql
ALTER TABLE daily_diet_log_photo_references
    ADD COLUMN patient_profile_id BIGINT,
    ADD COLUMN uploaded_by_user_id BIGINT,
    ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    ADD COLUMN sha256 VARCHAR(64),
    ADD COLUMN created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    ADD COLUMN attached_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN removed_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN removed_by_user_id BIGINT;

UPDATE daily_diet_log_photo_references photo
SET patient_profile_id = log.patient_profile_id,
    uploaded_by_user_id = profile.user_id,
    status = CASE WHEN photo.daily_diet_log_id IS NULL THEN 'PENDING' ELSE 'ATTACHED' END,
    sha256 = repeat('0', 64),
    attached_at = CASE WHEN photo.daily_diet_log_id IS NULL THEN NULL ELSE NOW() END
FROM daily_diet_logs log
JOIN patient_profiles profile ON profile.id = log.patient_profile_id
WHERE photo.daily_diet_log_id = log.id;

ALTER TABLE daily_diet_log_photo_references
    ALTER COLUMN patient_profile_id SET NOT NULL,
    ALTER COLUMN uploaded_by_user_id SET NOT NULL,
    ALTER COLUMN sha256 SET NOT NULL,
    ALTER COLUMN storage_key SET NOT NULL,
    ADD CONSTRAINT fk_daily_diet_log_photo_references_patient
        FOREIGN KEY (patient_profile_id) REFERENCES patient_profiles(id) ON DELETE CASCADE,
    ADD CONSTRAINT fk_daily_diet_log_photo_references_uploaded_by
        FOREIGN KEY (uploaded_by_user_id) REFERENCES users(id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_daily_diet_log_photo_references_removed_by
        FOREIGN KEY (removed_by_user_id) REFERENCES users(id) ON DELETE RESTRICT,
    ADD CONSTRAINT chk_daily_diet_log_photo_references_status
        CHECK (status IN ('PENDING', 'ATTACHED', 'REMOVED')),
    ADD CONSTRAINT chk_daily_diet_log_photo_references_sha256
        CHECK (length(sha256) = 64),
    ADD CONSTRAINT chk_daily_diet_log_photo_references_attached_state
        CHECK (
            (status = 'PENDING' AND daily_diet_log_id IS NULL AND attached_at IS NULL)
            OR (status = 'ATTACHED' AND daily_diet_log_id IS NOT NULL AND attached_at IS NOT NULL)
            OR (status = 'REMOVED')
        );

CREATE INDEX ix_daily_diet_log_photo_references_patient_status
    ON daily_diet_log_photo_references(patient_profile_id, status);

CREATE INDEX ix_daily_diet_log_photo_references_pending_created
    ON daily_diet_log_photo_references(status, created_at);
```

- [ ] **Step 7: Run repository test**

Run:

```bash
./gradlew test --tests com.metabion.repository.DailyDietLogRepositoryTest.photoReferenceStoresUploadLifecycleMetadata
```

Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/metabion/domain/DietLogPhotoStatus.java \
  src/main/java/com/metabion/domain/DailyDietLogPhotoReference.java \
  src/main/java/com/metabion/domain/DailyDietLog.java \
  src/main/resources/db/migration/V10__diet_log_photo_storage.sql \
  src/test/java/com/metabion/repository/DailyDietLogRepositoryTest.java
git commit -m "Add diet log photo lifecycle model"
```

---

### Task 2: File Storage Interface And Local Backend

**Files:**
- Create: `src/main/java/com/metabion/dto/FileStorageResource.java`
- Create: `src/main/java/com/metabion/dto/StoredFile.java`
- Create: `src/main/java/com/metabion/service/FileStorageService.java`
- Create: `src/main/java/com/metabion/service/LocalFileStorageService.java`
- Modify: `src/main/resources/application.properties`
- Test: `src/test/java/com/metabion/service/LocalFileStorageServiceTest.java`

- [ ] **Step 1: Write failing local storage tests**

Create `src/test/java/com/metabion/service/LocalFileStorageServiceTest.java`:

```java
package com.metabion.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalFileStorageServiceTest {

    @TempDir
    Path storageRoot;

    @Test
    void storesReadsAndDeletesBytesBelowConfiguredRoot() throws Exception {
        var service = new LocalFileStorageService(storageRoot);
        var bytes = "image-bytes".getBytes(StandardCharsets.UTF_8);

        var stored = service.store("diet-log-photos/10/test.jpg", new ByteArrayInputStream(bytes), bytes.length);

        assertThat(stored.storageKey()).isEqualTo("diet-log-photos/10/test.jpg");
        assertThat(stored.sizeBytes()).isEqualTo(bytes.length);
        assertThat(stored.sha256()).hasSize(64);
        assertThat(Files.exists(storageRoot.resolve(stored.storageKey()))).isTrue();

        try (var resource = service.read(stored.storageKey())) {
            assertThat(resource.inputStream().readAllBytes()).isEqualTo(bytes);
            assertThat(resource.sizeBytes()).isEqualTo(bytes.length);
        }

        service.delete(stored.storageKey());
        assertThat(Files.exists(storageRoot.resolve(stored.storageKey()))).isFalse();
        service.delete(stored.storageKey());
    }

    @Test
    void rejectsUnsafeStorageKeys() {
        var service = new LocalFileStorageService(storageRoot);

        assertThatThrownBy(() -> service.store("../escape.jpg", new ByteArrayInputStream(new byte[]{1}), 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("storageKey");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```bash
./gradlew test --tests com.metabion.service.LocalFileStorageServiceTest
```

Expected: FAIL because storage types do not exist.

- [ ] **Step 3: Add storage DTOs and interface**

Create `FileStorageResource`:

```java
package com.metabion.dto;

import java.io.IOException;
import java.io.InputStream;

public record FileStorageResource(
        InputStream inputStream,
        long sizeBytes
) implements AutoCloseable {
    @Override
    public void close() throws IOException {
        inputStream.close();
    }
}
```

Create `StoredFile`:

```java
package com.metabion.dto;

public record StoredFile(
        String storageKey,
        long sizeBytes,
        String sha256
) {
}
```

Create `FileStorageService`:

```java
package com.metabion.service;

import com.metabion.dto.FileStorageResource;
import com.metabion.dto.StoredFile;

import java.io.IOException;
import java.io.InputStream;

public interface FileStorageService {
    StoredFile store(String storageKey, InputStream inputStream, long sizeBytes) throws IOException;

    FileStorageResource read(String storageKey) throws IOException;

    void delete(String storageKey) throws IOException;
}
```

- [ ] **Step 4: Implement local storage**

Create `LocalFileStorageService`:

```java
package com.metabion.service;

import com.metabion.dto.FileStorageResource;
import com.metabion.dto.StoredFile;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

@Service
public class LocalFileStorageService implements FileStorageService {

    private final Path root;

    public LocalFileStorageService(@Value("${metabion.storage.local.root:./var/metabion-storage}") String root) {
        this(Path.of(root));
    }

    LocalFileStorageService(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    @Override
    public StoredFile store(String storageKey, InputStream inputStream, long sizeBytes) throws IOException {
        var target = resolveSafe(storageKey);
        Files.createDirectories(target.getParent());
        var digest = sha256();
        long written;
        try (var source = new DigestInputStream(new BufferedInputStream(inputStream), digest);
             var output = new BufferedOutputStream(Files.newOutputStream(target))) {
            written = source.transferTo(output);
        }
        if (written != sizeBytes) {
            Files.deleteIfExists(target);
            throw new IOException("Stored byte count does not match expected size");
        }
        return new StoredFile(storageKey, written, HexFormat.of().formatHex(digest.digest()));
    }

    @Override
    public FileStorageResource read(String storageKey) throws IOException {
        var target = resolveSafe(storageKey);
        return new FileStorageResource(Files.newInputStream(target), Files.size(target));
    }

    @Override
    public void delete(String storageKey) throws IOException {
        try {
            Files.deleteIfExists(resolveSafe(storageKey));
        } catch (NoSuchFileException ignored) {
            // Idempotent cleanup path.
        }
    }

    private Path resolveSafe(String storageKey) {
        if (storageKey == null || storageKey.isBlank()) {
            throw new IllegalArgumentException("storageKey is required");
        }
        var resolved = root.resolve(storageKey).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("storageKey is outside storage root");
        }
        return resolved;
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }
}
```

- [ ] **Step 5: Add configuration**

Append to `application.properties`:

```properties
# --- File storage ---
metabion.storage.local.root=${METABION_STORAGE_ROOT:./var/metabion-storage}
spring.servlet.multipart.max-file-size=${METABION_MAX_UPLOAD_SIZE:10MB}
spring.servlet.multipart.max-request-size=${METABION_MAX_REQUEST_SIZE:100MB}
```

- [ ] **Step 6: Run storage tests**

Run:

```bash
./gradlew test --tests com.metabion.service.LocalFileStorageServiceTest
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/metabion/dto/FileStorageResource.java \
  src/main/java/com/metabion/dto/StoredFile.java \
  src/main/java/com/metabion/service/FileStorageService.java \
  src/main/java/com/metabion/service/LocalFileStorageService.java \
  src/main/resources/application.properties \
  src/test/java/com/metabion/service/LocalFileStorageServiceTest.java
git commit -m "Add local file storage service"
```

---

### Task 3: Photo Repository, Properties, And Upload Service

**Files:**
- Create: `src/main/java/com/metabion/repository/DailyDietLogPhotoReferenceRepository.java`
- Create: `src/main/java/com/metabion/service/DietLogPhotoProperties.java`
- Create: `src/main/java/com/metabion/dto/DietLogPhotoUploadResponse.java`
- Create: `src/main/java/com/metabion/service/DietLogPhotoService.java`
- Modify: `src/main/java/com/metabion/Main.java`
- Modify: `src/main/resources/application.properties`
- Test: `src/test/java/com/metabion/service/DietLogPhotoServiceTest.java`

- [ ] **Step 1: Write failing upload validation tests**

Create `DietLogPhotoServiceTest` with these tests:

```java
package com.metabion.service;

import com.metabion.domain.PatientProfile;
import com.metabion.domain.RoleName;
import com.metabion.domain.User;
import com.metabion.repository.DailyDietLogPhotoReferenceRepository;
import com.metabion.repository.PatientProfileRepository;
import com.metabion.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DietLogPhotoServiceTest {

    @Mock UserRepository users;
    @Mock PatientProfileRepository patientProfiles;
    @Mock DailyDietLogPhotoReferenceRepository photos;
    @Mock FileStorageService storage;
    @Mock AccessControlService accessControl;

    DietLogPhotoService service;

    @BeforeEach
    void setUp() {
        service = new DietLogPhotoService(
                users,
                patientProfiles,
                photos,
                storage,
                accessControl,
                new DietLogPhotoProperties(10 * 1024 * 1024L, 10, Duration.ofHours(24)));
    }

    @Test
    void uploadCreatesPendingPhotoForCurrentPatient() throws Exception {
        var user = user(1L, "patient@example.com", RoleName.PATIENT);
        var patient = patient(10L, user);
        when(users.findByEmail("patient@example.com")).thenReturn(Optional.of(user));
        when(patientProfiles.findByUserId(1L)).thenReturn(Optional.of(patient));
        when(storage.store(any(), any(InputStream.class), eq(4L)))
                .thenReturn(new com.metabion.dto.StoredFile("diet-log-photos/10/test.jpg", 4L, "a".repeat(64)));
        when(photos.save(any())).thenAnswer(invocation -> {
            var photo = invocation.getArgument(0, com.metabion.domain.DailyDietLogPhotoReference.class);
            org.springframework.test.util.ReflectionTestUtils.setField(photo, "id", 50L);
            return photo;
        });

        var response = service.uploadForCurrentPatient(
                auth("patient@example.com"),
                new MockMultipartFile("file", "plate.jpg", "image/jpeg", jpegBytes()));

        assertThat(response.uploadId()).isEqualTo(50L);
        assertThat(response.originalFilename()).isEqualTo("plate.jpg");
        assertThat(response.contentType()).isEqualTo("image/jpeg");
        assertThat(response.sizeBytes()).isEqualTo(4L);
        assertThat(response.contentUrl()).isEqualTo("/api/diet-log-photos/50/content");

        var captor = ArgumentCaptor.forClass(com.metabion.domain.DailyDietLogPhotoReference.class);
        verify(photos).save(captor.capture());
        assertThat(captor.getValue().getPatientProfile()).isSameAs(patient);
        assertThat(captor.getValue().getStorageKey()).isEqualTo("diet-log-photos/10/test.jpg");
    }

    @Test
    void uploadRejectsUnsupportedSignature() {
        givenPatient();

        assertThatThrownBy(() -> service.uploadForCurrentPatient(
                auth("patient@example.com"),
                new MockMultipartFile("file", "note.txt", "text/plain", "nope".getBytes())))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("400 BAD_REQUEST")
                .hasMessageContaining("unsupported image type");
        verify(storage, never()).store(any(), any(), anyLong());
    }

    @Test
    void uploadRejectsOversizedFile() {
        givenPatient();
        service = new DietLogPhotoService(
                users,
                patientProfiles,
                photos,
                storage,
                accessControl,
                new DietLogPhotoProperties(3, 10, Duration.ofHours(24)));

        assertThatThrownBy(() -> service.uploadForCurrentPatient(
                auth("patient@example.com"),
                new MockMultipartFile("file", "plate.jpg", "image/jpeg", jpegBytes())))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("400 BAD_REQUEST")
                .hasMessageContaining("photo file is too large");
    }

    private void givenPatient() {
        var user = user(1L, "patient@example.com", RoleName.PATIENT);
        when(users.findByEmail("patient@example.com")).thenReturn(Optional.of(user));
        when(patientProfiles.findByUserId(1L)).thenReturn(Optional.of(patient(10L, user)));
    }

    private static TestingAuthenticationToken auth(String email) {
        return new TestingAuthenticationToken(email, "n/a");
    }

    private static byte[] jpegBytes() {
        return new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xD9};
    }

    private static User user(Long id, String email, RoleName role) {
        var user = new User(email, "hash");
        org.springframework.test.util.ReflectionTestUtils.setField(user, "id", id);
        user.addRole(role);
        return user;
    }

    private static PatientProfile patient(Long id, User user) {
        var patient = new PatientProfile(user);
        org.springframework.test.util.ReflectionTestUtils.setField(patient, "id", id);
        return patient;
    }
}
```

- [ ] **Step 2: Run tests to verify failure**

Run:

```bash
./gradlew test --tests com.metabion.service.DietLogPhotoServiceTest
```

Expected: FAIL because repository/properties/service do not exist.

- [ ] **Step 3: Add repository**

Create `DailyDietLogPhotoReferenceRepository`:

```java
package com.metabion.repository;

import com.metabion.domain.DailyDietLogPhotoReference;
import com.metabion.domain.DietLogPhotoStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

public interface DailyDietLogPhotoReferenceRepository extends JpaRepository<DailyDietLogPhotoReference, Long> {
    List<DailyDietLogPhotoReference> findByIdIn(Collection<Long> ids);

    List<DailyDietLogPhotoReference> findByStatusAndCreatedAtBefore(DietLogPhotoStatus status, Instant createdBefore);
}
```

- [ ] **Step 4: Add configuration properties**

Create `DietLogPhotoProperties`:

```java
package com.metabion.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "metabion.diet-log-photos")
public record DietLogPhotoProperties(
        long maxFileSize,
        int maxPhotosPerLog,
        Duration pendingRetention
) {
}
```

Update `Main.java`:

```java
@EnableConfigurationProperties(DietLogPhotoProperties.class)
@EnableScheduling
@SpringBootApplication
public class Main {
```

Add imports:

```java
import com.metabion.service.DietLogPhotoProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;
```

Append properties:

```properties
metabion.diet-log-photos.max-file-size=${METABION_DIET_LOG_PHOTO_MAX_FILE_SIZE:10485760}
metabion.diet-log-photos.max-photos-per-log=${METABION_DIET_LOG_PHOTO_MAX_PHOTOS:10}
metabion.diet-log-photos.pending-retention=${METABION_DIET_LOG_PHOTO_PENDING_RETENTION:24h}
```

- [ ] **Step 5: Add upload response**

Create `DietLogPhotoUploadResponse`:

```java
package com.metabion.dto;

public record DietLogPhotoUploadResponse(
        Long uploadId,
        String originalFilename,
        String contentType,
        Long sizeBytes,
        String caption,
        String contentUrl
) {
}
```

- [ ] **Step 6: Implement upload service**

Create `DietLogPhotoService` with upload validation:

```java
package com.metabion.service;

import com.metabion.domain.DailyDietLogPhotoReference;
import com.metabion.domain.PatientProfile;
import com.metabion.domain.RoleName;
import com.metabion.domain.User;
import com.metabion.dto.DietLogPhotoUploadResponse;
import com.metabion.repository.DailyDietLogPhotoReferenceRepository;
import com.metabion.repository.PatientProfileRepository;
import com.metabion.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.util.Locale;
import java.util.UUID;

@Service
@Transactional
public class DietLogPhotoService {

    private final UserRepository users;
    private final PatientProfileRepository patientProfiles;
    private final DailyDietLogPhotoReferenceRepository photos;
    private final FileStorageService storage;
    private final AccessControlService accessControl;
    private final DietLogPhotoProperties properties;

    public DietLogPhotoService(UserRepository users,
                               PatientProfileRepository patientProfiles,
                               DailyDietLogPhotoReferenceRepository photos,
                               FileStorageService storage,
                               AccessControlService accessControl,
                               DietLogPhotoProperties properties) {
        this.users = users;
        this.patientProfiles = patientProfiles;
        this.photos = photos;
        this.storage = storage;
        this.accessControl = accessControl;
        this.properties = properties;
    }

    public DietLogPhotoUploadResponse uploadForCurrentPatient(Authentication authentication, MultipartFile file) {
        var user = currentUser(authentication);
        if (!user.hasRole(RoleName.PATIENT)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Current user is not a patient");
        }
        var patient = patientProfiles.findByUserId(user.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Patient profile not found"));
        validateFile(file);
        var contentType = detectContentType(file);
        var storageKey = storageKey(patient.getId(), extensionFor(contentType));
        try {
            var stored = storage.store(storageKey, file.getInputStream(), file.getSize());
            var photo = DailyDietLogPhotoReference.pending(
                    patient,
                    user,
                    sanitizeFilename(file.getOriginalFilename()),
                    contentType,
                    stored.sizeBytes(),
                    stored.sha256(),
                    stored.storageKey());
            var saved = photos.save(photo);
            return response(saved.getId(), saved.getOriginalFilename(), saved.getContentType(), saved.getSizeBytes(), saved.getCaption());
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "photo could not be stored", ex);
        }
    }

    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "photo file is required");
        }
        if (file.getSize() > properties.maxFileSize()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "photo file is too large");
        }
    }

    private String detectContentType(MultipartFile file) {
        try (var input = new BufferedInputStream(file.getInputStream())) {
            input.mark(16);
            var header = input.readNBytes(16);
            if (isJpeg(header)) {
                return "image/jpeg";
            }
            if (isPng(header)) {
                return "image/png";
            }
            if (isWebp(header)) {
                return "image/webp";
            }
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "photo file could not be read", ex);
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unsupported image type");
    }

    private static boolean isJpeg(byte[] header) {
        return header.length >= 3
                && (header[0] & 0xFF) == 0xFF
                && (header[1] & 0xFF) == 0xD8
                && (header[2] & 0xFF) == 0xFF;
    }

    private static boolean isPng(byte[] header) {
        return header.length >= 8
                && (header[0] & 0xFF) == 0x89
                && header[1] == 0x50
                && header[2] == 0x4E
                && header[3] == 0x47
                && header[4] == 0x0D
                && header[5] == 0x0A
                && header[6] == 0x1A
                && header[7] == 0x0A;
    }

    private static boolean isWebp(byte[] header) {
        return header.length >= 12
                && header[0] == 'R'
                && header[1] == 'I'
                && header[2] == 'F'
                && header[3] == 'F'
                && header[8] == 'W'
                && header[9] == 'E'
                && header[10] == 'B'
                && header[11] == 'P';
    }

    private static String extensionFor(String contentType) {
        return switch (contentType) {
            case "image/jpeg" -> ".jpg";
            case "image/png" -> ".png";
            case "image/webp" -> ".webp";
            default -> throw new IllegalArgumentException("Unsupported content type " + contentType);
        };
    }

    private static String sanitizeFilename(String filename) {
        if (filename == null || filename.isBlank()) {
            return "photo";
        }
        return filename.replace("\\", "/").substring(filename.replace("\\", "/").lastIndexOf('/') + 1);
    }

    private static String storageKey(Long patientProfileId, String extension) {
        var now = LocalDate.now();
        return "diet-log-photos/%d/%04d/%02d/%02d/%s%s".formatted(
                patientProfileId,
                now.getYear(),
                now.getMonthValue(),
                now.getDayOfMonth(),
                UUID.randomUUID().toString().toLowerCase(Locale.ROOT),
                extension);
    }

    private static DietLogPhotoUploadResponse response(Long id, String filename, String contentType, Long sizeBytes, String caption) {
        return new DietLogPhotoUploadResponse(
                id,
                filename,
                contentType,
                sizeBytes,
                caption,
                "/api/diet-log-photos/" + id + "/content");
    }

    private User currentUser(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated() || authentication.getName() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        return users.findByEmail(UserService.normalize(authentication.getName()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authenticated user not found"));
    }
}
```

- [ ] **Step 7: Run upload service tests**

Run:

```bash
./gradlew test --tests com.metabion.service.DietLogPhotoServiceTest
```

Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/metabion/repository/DailyDietLogPhotoReferenceRepository.java \
  src/main/java/com/metabion/service/DietLogPhotoProperties.java \
  src/main/java/com/metabion/dto/DietLogPhotoUploadResponse.java \
  src/main/java/com/metabion/service/DietLogPhotoService.java \
  src/main/java/com/metabion/Main.java \
  src/main/resources/application.properties \
  src/test/java/com/metabion/service/DietLogPhotoServiceTest.java
git commit -m "Add staged diet photo upload service"
```

---

### Task 4: Attach Uploaded Photos During Diet Log Save

**Files:**
- Modify: `src/main/java/com/metabion/dto/DailyDietLogRequest.java`
- Modify: `src/main/java/com/metabion/service/DietLogRequestMapper.java`
- Modify: `src/main/java/com/metabion/service/DietLogService.java`
- Modify: `src/main/java/com/metabion/service/DietLogPhotoService.java`
- Test: `src/test/java/com/metabion/service/DietLogServiceTest.java`
- Test: `src/test/java/com/metabion/service/DietLogPhotoServiceTest.java`

- [ ] **Step 1: Write failing attach tests**

Add to `DietLogPhotoServiceTest`:

```java
@Test
void attachUploadsRejectsCrossPatientPhoto() {
    var patientUser = user(1L, "patient@example.com", RoleName.PATIENT);
    var patient = patient(10L, patientUser);
    var otherPatient = patient(20L, user(2L, "other@example.com", RoleName.PATIENT));
    var log = new com.metabion.domain.DailyDietLog(patient, java.time.LocalDate.of(2026, 6, 10));
    var photo = com.metabion.domain.DailyDietLogPhotoReference.pending(
            otherPatient,
            otherPatient.getUser(),
            "x.jpg",
            "image/jpeg",
            4L,
            "a".repeat(64),
            "diet-log-photos/20/x.jpg");
    org.springframework.test.util.ReflectionTestUtils.setField(photo, "id", 99L);
    when(photos.findByIdIn(java.util.List.of(99L))).thenReturn(java.util.List.of(photo));

    assertThatThrownBy(() -> service.attachToLog(
            patient,
            log,
            java.util.List.of(new com.metabion.dto.DailyDietLogRequest.PhotoUploadReferenceRequest(99L, "caption"))))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("400 BAD_REQUEST")
            .hasMessageContaining("photo upload is invalid");
}
```

Add to `DietLogServiceTest`:

```java
@Mock DietLogPhotoService dietLogPhotoService;
```

Change setup to pass `dietLogPhotoService` to the `DietLogService` constructor after adding the constructor parameter.

Add:

```java
@Test
void saveForCurrentPatientAttachesUploadedPhotosAfterSavingLog() {
    var patient = givenAuthenticatedPatient();
    var saved = savedLog(99L, patient, LocalDate.of(2026, 6, 10));
    when(dailyDietLogs.findByPatientProfileIdAndLogDate(10L, LocalDate.of(2026, 6, 10)))
            .thenReturn(Optional.empty());
    when(dailyDietLogs.save(any())).thenReturn(saved);
    when(measurements.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    var photoReference = new DailyDietLogRequest.PhotoUploadReferenceRequest(50L, "Lunch plate");
    var request = new DailyDietLogRequest(
            LocalDate.of(2026, 6, 10),
            DietAdherenceLevel.MOSTLY,
            AppetiteLevel.NORMAL,
            "Stable",
            null,
            List.of(),
            List.of(),
            List.of(photoReference),
            List.of());

    service.saveForCurrentPatient(auth("patient@example.com"), request);

    verify(dietLogPhotoService).attachToLog(patient, saved, List.of(photoReference));
}
```

- [ ] **Step 2: Run tests to verify failure**

Run:

```bash
./gradlew test --tests com.metabion.service.DietLogPhotoServiceTest.attachUploadsRejectsCrossPatientPhoto \
  --tests com.metabion.service.DietLogServiceTest.saveForCurrentPatientAttachesUploadedPhotosAfterSavingLog
```

Expected: FAIL because request type and attach method do not exist.

- [ ] **Step 3: Change request DTO**

In `DailyDietLogRequest`, replace the nested `PhotoReferenceRequest` record with:

```java
public record PhotoUploadReferenceRequest(
        @NotNull Long uploadId,
        @Size(max = 500) String caption
) {
}
```

Change the request field and helpers:

```java
@Valid List<PhotoUploadReferenceRequest> photoReferences,

public List<PhotoUploadReferenceRequest> photoReferencesOrEmpty() {
    return photoReferences == null ? List.of() : photoReferences;
}
```

- [ ] **Step 4: Stop mapping photos in request mapper**

Change `DietLogRequestMapper.applyTo`:

```java
public void applyTo(DailyDietLog log, DailyDietLogRequest request) {
    log.setLogDate(request.logDate());
    log.setAdherenceLevel(request.adherenceLevel());
    log.setAppetiteLevel(request.appetiteLevel());
    log.setNotes(trimToNull(request.notes()));
    log.setMetadata(trimToNull(request.metadata()));
    log.replaceChildren(mealsFrom(request), deviationsFrom(request));
}
```

Remove the `StorageKeyValidator` dependency and `photoReferencesFrom` method after compile errors identify unused code.

- [ ] **Step 5: Implement attach method**

Add to `DietLogPhotoService`:

```java
public void attachToLog(PatientProfile patient,
                        com.metabion.domain.DailyDietLog log,
                        java.util.List<com.metabion.dto.DailyDietLogRequest.PhotoUploadReferenceRequest> requests) {
    var safeRequests = requests == null ? java.util.List.<com.metabion.dto.DailyDietLogRequest.PhotoUploadReferenceRequest>of() : requests;
    if (safeRequests.size() > properties.maxPhotosPerLog()) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "too many photos");
    }
    var ids = safeRequests.stream().map(com.metabion.dto.DailyDietLogRequest.PhotoUploadReferenceRequest::uploadId).toList();
    var found = photos.findByIdIn(ids).stream().collect(java.util.stream.Collectors.toMap(
            com.metabion.domain.DailyDietLogPhotoReference::getId,
            java.util.function.Function.identity()));
    for (var i = 0; i < safeRequests.size(); i++) {
        var request = safeRequests.get(i);
        var photo = found.get(request.uploadId());
        if (photo == null || photo.getPatientProfile() == null || !photo.getPatientProfile().getId().equals(patient.getId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "photo upload is invalid");
        }
        if (photo.getDailyDietLog() != null
                && log.getId() != null
                && !photo.getDailyDietLog().getId().equals(log.getId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "photo upload is invalid");
        }
        photo.attachTo(log, DietLogRequestMapper.trimToNull(request.caption()), i);
        if (!log.getPhotoReferences().contains(photo)) {
            log.addPhotoReference(photo);
        }
    }
}
```

- [ ] **Step 6: Wire `DietLogService` transaction**

Add constructor dependency:

```java
private final DietLogPhotoService dietLogPhotoService;
```

After `var saved = dailyDietLogs.save(log);` and before saving measurements:

```java
dietLogPhotoService.attachToLog(patient, saved, request.photoReferencesOrEmpty());
```

Keep `@Transactional` on `DietLogService` so save and attach share one transaction.

- [ ] **Step 7: Run service tests**

Run:

```bash
./gradlew test --tests com.metabion.service.DietLogServiceTest \
  --tests com.metabion.service.DietLogPhotoServiceTest
```

Expected: PASS after updating old tests that expected metadata-created photo rows.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/metabion/dto/DailyDietLogRequest.java \
  src/main/java/com/metabion/service/DietLogRequestMapper.java \
  src/main/java/com/metabion/service/DietLogService.java \
  src/main/java/com/metabion/service/DietLogPhotoService.java \
  src/test/java/com/metabion/service/DietLogServiceTest.java \
  src/test/java/com/metabion/service/DietLogPhotoServiceTest.java
git commit -m "Attach staged photos to diet logs"
```

---

### Task 5: Private Content Streaming API

**Files:**
- Create: `src/main/java/com/metabion/controller/api/DietLogPhotoController.java`
- Modify: `src/main/java/com/metabion/service/DietLogPhotoService.java`
- Test: `src/test/java/com/metabion/controller/api/DietLogPhotoControllerTest.java`
- Test: `src/test/java/com/metabion/service/DietLogPhotoServiceTest.java`

- [ ] **Step 1: Write failing controller tests**

Create `DietLogPhotoControllerTest`:

```java
package com.metabion.controller.api;

import com.metabion.dto.DietLogPhotoUploadResponse;
import com.metabion.dto.FileStorageResource;
import com.metabion.service.DietLogPhotoService;
import com.metabion.service.SecurityService;
import com.metabion.service.UserService;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.io.ByteArrayInputStream;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.profiles.active=dev",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.datasource.url=jdbc:h2:mem:diet_log_photo_controller_test;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.autoconfigure.exclude=org.springframework.boot.session.jdbc.autoconfigure.JdbcSessionAutoConfiguration"
})
class DietLogPhotoControllerTest {

    @Autowired WebApplicationContext context;
    @MockitoBean FindByIndexNameSessionRepository<Session> sessions;
    @MockitoBean UserService userService;
    @MockitoBean SecurityService securityService;
    @MockitoBean DietLogPhotoService dietLogPhotoService;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        Filter[] filters = context.getBeansOfType(Filter.class).values().toArray(new Filter[0]);
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(filters)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    @Test
    void patientCanUploadPhotoWithCsrf() throws Exception {
        when(dietLogPhotoService.uploadForCurrentPatient(any(), any()))
                .thenReturn(new DietLogPhotoUploadResponse(50L, "plate.jpg", "image/jpeg", 4L, null,
                        "/api/diet-log-photos/50/content"));

        mvc.perform(multipart("/api/diet-log-photos/uploads")
                        .file(new MockMultipartFile("file", "plate.jpg", "image/jpeg", new byte[]{1, 2, 3, 4}))
                        .with(user("patient@example.com").roles("PATIENT"))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.uploadId").value(50))
                .andExpect(jsonPath("$.contentUrl").value("/api/diet-log-photos/50/content"));
    }

    @Test
    void authenticatedUserCanStreamPhotoContent() throws Exception {
        when(dietLogPhotoService.readContent(any(), eq(50L)))
                .thenReturn(new DietLogPhotoService.PhotoContent(
                        "image/jpeg",
                        new FileStorageResource(new ByteArrayInputStream(new byte[]{1, 2, 3}), 3)));

        mvc.perform(get("/api/diet-log-photos/50/content")
                        .with(user("doctor@example.com").roles("PHYSICIAN")))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_JPEG))
                .andExpect(content().bytes(new byte[]{1, 2, 3}));
    }
}
```

- [ ] **Step 2: Run test to verify failure**

Run:

```bash
./gradlew test --tests com.metabion.controller.api.DietLogPhotoControllerTest
```

Expected: FAIL because controller/read method do not exist.

- [ ] **Step 3: Add content read service**

Add nested record and method to `DietLogPhotoService`:

```java
public record PhotoContent(String contentType, com.metabion.dto.FileStorageResource resource) {
}

@Transactional(readOnly = true)
public PhotoContent readContent(Authentication authentication, Long photoId) {
    var user = currentUser(authentication);
    var photo = photos.findById(photoId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "photo not found"));
    requireCanRead(user, authentication, photo);
    try {
        return new PhotoContent(photo.getContentType(), storage.read(photo.getStorageKey()));
    } catch (java.nio.file.NoSuchFileException ex) {
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "photo content not found");
    } catch (IOException ex) {
        throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "photo content could not be read", ex);
    }
}

private void requireCanRead(User user, Authentication authentication, DailyDietLogPhotoReference photo) {
    var patient = photo.getPatientProfile();
    if (patient == null || patient.getId() == null) {
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "photo not found");
    }
    if (user.hasRole(RoleName.ADMIN)) {
        return;
    }
    if (user.hasRole(RoleName.PATIENT) && patient.getUser() != null && patient.getUser().getId().equals(user.getId())) {
        return;
    }
    if (photo.getStatus() == com.metabion.domain.DietLogPhotoStatus.ATTACHED
            && user.hasAnyRole(RoleName.NUTRITION_SPECIALIST, RoleName.PHYSICIAN, RoleName.COORDINATOR)
            && accessControl.canAccessPatientProfile(authentication, patient.getId())) {
        return;
    }
    throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Current user cannot read photo");
}
```

- [ ] **Step 4: Add controller**

Create `DietLogPhotoController`:

```java
package com.metabion.controller.api;

import com.metabion.dto.DietLogPhotoUploadResponse;
import com.metabion.service.DietLogPhotoService;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
public class DietLogPhotoController {

    private final DietLogPhotoService dietLogPhotoService;

    public DietLogPhotoController(DietLogPhotoService dietLogPhotoService) {
        this.dietLogPhotoService = dietLogPhotoService;
    }

    @PostMapping("/api/diet-log-photos/uploads")
    public DietLogPhotoUploadResponse upload(@RequestPart("file") MultipartFile file,
                                             Authentication authentication) {
        return dietLogPhotoService.uploadForCurrentPatient(authentication, file);
    }

    @GetMapping("/api/diet-log-photos/{id}/content")
    public ResponseEntity<InputStreamResource> content(@PathVariable Long id,
                                                       Authentication authentication) {
        var content = dietLogPhotoService.readContent(authentication, id);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(content.contentType()))
                .contentLength(content.resource().sizeBytes())
                .body(new InputStreamResource(content.resource().inputStream()));
    }
}
```

- [ ] **Step 5: Run controller tests**

Run:

```bash
./gradlew test --tests com.metabion.controller.api.DietLogPhotoControllerTest
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/metabion/controller/api/DietLogPhotoController.java \
  src/main/java/com/metabion/service/DietLogPhotoService.java \
  src/test/java/com/metabion/controller/api/DietLogPhotoControllerTest.java \
  src/test/java/com/metabion/service/DietLogPhotoServiceTest.java
git commit -m "Add private diet photo content API"
```

---

### Task 6: Response DTOs And Clinical Photo Rendering

**Files:**
- Modify: `src/main/java/com/metabion/dto/DailyDietLogResponse.java`
- Modify: `src/main/java/com/metabion/service/DietLogResponseAssembler.java`
- Modify: `src/main/resources/templates/clinical-diet-log-detail.html`
- Test: `src/test/java/com/metabion/dto/DietLogFormTest.java`
- Test: `src/test/java/com/metabion/controller/web/WebDietLogControllerTest.java`

- [ ] **Step 1: Write/update failing response assertions**

Update tests that assert photo response fields so they expect `contentUrl` and no storage key. Example assertion:

```java
assertThat(response.photoReferences()).singleElement()
        .satisfies(row -> {
            assertThat(row.id()).isEqualTo(50L);
            assertThat(row.originalFilename()).isEqualTo("plate.jpg");
            assertThat(row.contentType()).isEqualTo("image/jpeg");
            assertThat(row.sizeBytes()).isEqualTo(2048L);
            assertThat(row.contentUrl()).isEqualTo("/api/diet-log-photos/50/content");
            assertThat(row.caption()).isEqualTo("Lunch plate");
        });
```

Update `WebDietLogControllerTest.clinicalDetailRenders...` to assert rendered content contains `/api/diet-log-photos/50/content` and does not contain `Storage key`.

- [ ] **Step 2: Run focused tests to verify failure**

Run:

```bash
./gradlew test --tests com.metabion.dto.DietLogFormTest \
  --tests com.metabion.controller.web.WebDietLogControllerTest
```

Expected: FAIL because response still exposes storage key and template renders metadata.

- [ ] **Step 3: Update response DTO**

Change nested `PhotoReferenceResponse` in `DailyDietLogResponse`:

```java
public record PhotoReferenceResponse(
        Long id,
        Long mealId,
        String originalFilename,
        String contentType,
        Long sizeBytes,
        String caption,
        String contentUrl,
        int sortOrder
) {
    private static PhotoReferenceResponse from(DailyDietLogPhotoReference photoReference) {
        var meal = photoReference.getMeal();
        var id = photoReference.getId();
        return new PhotoReferenceResponse(
                id,
                meal == null ? null : meal.getId(),
                photoReference.getOriginalFilename(),
                photoReference.getContentType(),
                photoReference.getSizeBytes(),
                photoReference.getCaption(),
                id == null ? null : "/api/diet-log-photos/" + id + "/content",
                photoReference.getSortOrder());
    }
}
```

- [ ] **Step 4: Filter attached photos only**

In `DailyDietLogResponse.from`, change photo mapping:

```java
log.getPhotoReferences().stream()
        .filter(photo -> photo.getStatus() == com.metabion.domain.DietLogPhotoStatus.ATTACHED)
        .map(PhotoReferenceResponse::from)
        .toList(),
```

- [ ] **Step 5: Update clinical detail template**

Replace the photo section in `clinical-diet-log-detail.html` with:

```html
<section class="panel app-panel">
    <h2 th:text="#{dietLogs.photoReference}">Photos</h2>
    <div th:each="photo : ${log.photoReferences()}">
        <p>
            <a th:href="${photo.contentUrl()}" th:text="${photo.originalFilename()} ?: #{dietLogs.photo}">plate.jpg</a>
            <span th:text="${photo.caption()} ?: #{dietLogs.notProvided}">Lunch</span>
        </p>
        <img th:src="${photo.contentUrl()}" th:alt="${photo.caption()} ?: ${photo.originalFilename()}" style="max-width: 240px; height: auto;">
    </div>
    <p th:if="${#lists.isEmpty(log.photoReferences())}" th:text="#{dietLogs.noPhotoReferences}">No photos recorded</p>
</section>
```

- [ ] **Step 6: Run tests**

Run:

```bash
./gradlew test --tests com.metabion.dto.DietLogFormTest \
  --tests com.metabion.controller.web.WebDietLogControllerTest
```

Expected: PASS after updating expected constructor arguments in tests.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/metabion/dto/DailyDietLogResponse.java \
  src/main/java/com/metabion/service/DietLogResponseAssembler.java \
  src/main/resources/templates/clinical-diet-log-detail.html \
  src/test/java/com/metabion/dto/DietLogFormTest.java \
  src/test/java/com/metabion/controller/web/WebDietLogControllerTest.java
git commit -m "Return private diet photo content links"
```

---

### Task 7: Web Form Contract And Template

**Files:**
- Modify: `src/main/java/com/metabion/dto/DietLogForm.java`
- Modify: `src/main/java/com/metabion/controller/web/WebDietLogController.java`
- Modify: `src/main/resources/templates/diet-logs.html`
- Modify: `src/main/resources/messages.properties`
- Modify: `src/main/resources/messages_cs.properties`
- Test: `src/test/java/com/metabion/dto/DietLogFormTest.java`
- Test: `src/test/java/com/metabion/controller/web/WebDietLogControllerTest.java`

- [ ] **Step 1: Write/update failing web form assertions**

Change `patientDietLogPageRendersWithGlucosePreferenceAndShell`:

```java
.andExpect(content().string(containsString("type=\"file\"")))
.andExpect(content().string(containsString("name=\"photoReferences[2].uploadId\"")))
.andExpect(content().string(not(containsString("name=\"photoReferences[2].storageKey\""))))
.andExpect(content().string(not(containsString("name=\"photoReferences[2].contentType\""))))
.andExpect(content().string(not(containsString("name=\"photoReferences[2].sizeBytes\""))))
.andExpect(content().string(not(containsString("name=\"photoReferences[2].originalFilename\""))))
```

Update `DietLogFormTest` to build a `PhotoReferenceRow` with `uploadId` and `caption`, then assert:

```java
assertThat(request.photoReferencesOrEmpty()).singleElement()
        .satisfies(row -> {
            assertThat(row.uploadId()).isEqualTo(50L);
            assertThat(row.caption()).isEqualTo("Dinner plate");
        });
```

- [ ] **Step 2: Run tests to verify failure**

Run:

```bash
./gradlew test --tests com.metabion.dto.DietLogFormTest \
  --tests com.metabion.controller.web.WebDietLogControllerTest.patientDietLogPageRendersWithGlucosePreferenceAndShell
```

Expected: FAIL because form still exposes metadata fields.

- [ ] **Step 3: Update `DietLogForm.PhotoReferenceRow`**

Replace fields with:

```java
private Long uploadId;

@Size(max = 500)
private String caption;

public DailyDietLogRequest.PhotoUploadReferenceRequest toRequest() {
    return new DailyDietLogRequest.PhotoUploadReferenceRequest(uploadId, caption);
}

boolean isBlank() {
    return uploadId == null && blank(caption);
}

public Long getUploadId() {
    return uploadId;
}

public void setUploadId(Long uploadId) {
    this.uploadId = uploadId;
}
```

Keep caption getter/setter.

- [ ] **Step 4: Hydrate form rows from responses**

In `WebDietLogController.formFrom`, map response photos:

```java
row.setUploadId(photo.id());
row.setCaption(photo.caption());
```

Remove setting original filename, content type, size, and storage key.

- [ ] **Step 5: Update patient template photo fieldset**

Replace the photo fieldset in `diet-logs.html` with:

```html
<fieldset>
    <legend th:text="#{dietLogs.photoReference}">Photos</legend>
    <label class="field"><span th:text="#{dietLogs.photoUpload}">Upload photos</span>
        <input type="file" id="diet-photo-upload" accept="image/jpeg,image/png,image/webp" multiple>
    </label>
    <div th:each="photoReference, row : *{photoReferences}">
        <input type="hidden" th:field="*{photoReferences[__${row.index}__].uploadId}">
        <label class="field"><span th:text="#{dietLogs.caption}">Caption</span>
            <input th:field="*{photoReferences[__${row.index}__].caption}">
        </label>
    </div>
</fieldset>
```

Add this minimal JavaScript enhancement after the form:

```html
<script>
document.addEventListener('DOMContentLoaded', () => {
  const input = document.getElementById('diet-photo-upload');
  if (!input) return;
  input.addEventListener('change', async () => {
    for (const file of input.files) {
      const formData = new FormData();
      formData.append('file', file);
      await fetch('/api/diet-log-photos/uploads', {
        method: 'POST',
        headers: {'X-CSRF-TOKEN': document.querySelector('input[name="_csrf"]').value},
        body: formData
      });
    }
    window.location.reload();
  });
});
</script>
```

- [ ] **Step 6: Update messages**

In `messages.properties`:

```properties
dietLogs.photoReference=Photos
dietLogs.noPhotoReferences=No photos recorded
dietLogs.photoUpload=Upload photos
dietLogs.photo=Photo
```

In `messages_cs.properties`:

```properties
dietLogs.photoReference=Fotografie
dietLogs.noPhotoReferences=Nejsou zaznamenány žádné fotografie
dietLogs.photoUpload=Nahrát fotografie
dietLogs.photo=Fotografie
```

- [ ] **Step 7: Run web/form tests**

Run:

```bash
./gradlew test --tests com.metabion.dto.DietLogFormTest \
  --tests com.metabion.controller.web.WebDietLogControllerTest
```

Expected: PASS after updating constructor expectations.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/metabion/dto/DietLogForm.java \
  src/main/java/com/metabion/controller/web/WebDietLogController.java \
  src/main/resources/templates/diet-logs.html \
  src/main/resources/messages.properties \
  src/main/resources/messages_cs.properties \
  src/test/java/com/metabion/dto/DietLogFormTest.java \
  src/test/java/com/metabion/controller/web/WebDietLogControllerTest.java
git commit -m "Replace photo metadata form fields"
```

---

### Task 8: Pending Upload Cleanup

**Files:**
- Modify: `src/main/java/com/metabion/service/DietLogPhotoService.java`
- Test: `src/test/java/com/metabion/service/DietLogPhotoServiceTest.java`

- [ ] **Step 1: Write failing cleanup test**

Add to `DietLogPhotoServiceTest`:

```java
@Test
void cleanupDeletesOldPendingRowsAndToleratesMissingFiles() throws Exception {
    var user = user(1L, "patient@example.com", RoleName.PATIENT);
    var patient = patient(10L, user);
    var pending = com.metabion.domain.DailyDietLogPhotoReference.pending(
            patient,
            user,
            "old.jpg",
            "image/jpeg",
            4L,
            "a".repeat(64),
            "diet-log-photos/10/old.jpg");
    when(photos.findByStatusAndCreatedAtBefore(eq(com.metabion.domain.DietLogPhotoStatus.PENDING), any()))
            .thenReturn(java.util.List.of(pending));
    org.mockito.Mockito.doThrow(new java.nio.file.NoSuchFileException("old.jpg"))
            .when(storage).delete("diet-log-photos/10/old.jpg");

    service.cleanupPendingUploads();

    verify(photos).delete(pending);
}
```

- [ ] **Step 2: Run test to verify failure**

Run:

```bash
./gradlew test --tests com.metabion.service.DietLogPhotoServiceTest.cleanupDeletesOldPendingRowsAndToleratesMissingFiles
```

Expected: FAIL because cleanup method does not exist.

- [ ] **Step 3: Implement scheduled cleanup**

Add to `DietLogPhotoService`:

```java
@org.springframework.scheduling.annotation.Scheduled(fixedDelayString = "${metabion.diet-log-photos.cleanup-delay:PT1H}")
public void cleanupPendingUploads() {
    var cutoff = java.time.Instant.now().minus(properties.pendingRetention());
    var pending = photos.findByStatusAndCreatedAtBefore(com.metabion.domain.DietLogPhotoStatus.PENDING, cutoff);
    for (var photo : pending) {
        try {
            storage.delete(photo.getStorageKey());
        } catch (java.nio.file.NoSuchFileException ignored) {
            // Cleanup is idempotent.
        } catch (IOException ex) {
            continue;
        }
        photos.delete(photo);
    }
}
```

Add property:

```properties
metabion.diet-log-photos.cleanup-delay=${METABION_DIET_LOG_PHOTO_CLEANUP_DELAY:PT1H}
```

- [ ] **Step 4: Run cleanup tests**

Run:

```bash
./gradlew test --tests com.metabion.service.DietLogPhotoServiceTest.cleanupDeletesOldPendingRowsAndToleratesMissingFiles
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/metabion/service/DietLogPhotoService.java \
  src/main/resources/application.properties \
  src/test/java/com/metabion/service/DietLogPhotoServiceTest.java
git commit -m "Clean up abandoned diet photo uploads"
```

---

### Task 9: API Contract Updates And Full Verification

**Files:**
- Modify: `src/test/java/com/metabion/controller/api/DietLogControllerTest.java`
- Modify: `src/test/java/com/metabion/service/DietLogServiceTest.java`
- Modify: `src/test/java/com/metabion/dto/DietLogFormTest.java`
- Modify: `src/test/java/com/metabion/controller/web/WebDietLogControllerTest.java`
- Modify any remaining compile errors from old `PhotoReferenceRequest` usage.

- [ ] **Step 1: Search for old storage metadata contract**

Run:

```bash
rg -n "PhotoReferenceRequest|storageKey|originalFilename|contentType|sizeBytes|photo metadata|Photo metadata" src/main/java src/test/java src/main/resources
```

Expected: any hits are either domain/entity storage internals, clinical display metadata labels, or tests that still need updating.

- [ ] **Step 2: Update API JSON tests**

In `DietLogControllerTest.validLogJson`, replace:

```json
"photoReferences": []
```

with:

```json
"photoReferences": [
  {"uploadId": 50, "caption": "Lunch plate"}
]
```

Keep tests using empty arrays where they intentionally test no photos.

- [ ] **Step 3: Run compile/test sweep**

Run:

```bash
./gradlew test
```

Expected: FAIL only for remaining old-contract compile errors or assertions.

- [ ] **Step 4: Fix remaining compile errors mechanically**

For each `DailyDietLogRequest.PhotoReferenceRequest(...)` construction, replace with:

```java
new DailyDietLogRequest.PhotoUploadReferenceRequest(50L, "Lunch plate")
```

For each `DailyDietLogResponse.PhotoReferenceResponse(...)` construction, use:

```java
new DailyDietLogResponse.PhotoReferenceResponse(
        50L,
        null,
        "plate.jpg",
        "image/jpeg",
        2048L,
        "Lunch plate",
        "/api/diet-log-photos/50/content",
        0)
```

- [ ] **Step 5: Run full verification**

Run:

```bash
./gradlew test
```

Expected: PASS.

- [ ] **Step 6: Inspect final diff**

Run:

```bash
git status --short
git diff --stat
git diff -- src/main/java src/test/java src/main/resources
```

Expected: only diet-log photo storage related files changed; `.superpowers/` remains untracked and is not committed.

- [ ] **Step 7: Commit**

```bash
git add src/main/java src/test/java src/main/resources
git commit -m "Verify diet photo storage workflow"
```

---

## Final Verification

Run:

```bash
./gradlew test
git status --short
```

Expected:

- Gradle test suite passes.
- Worktree is clean after intentionally leaving `.superpowers/` uncommitted or deleting those generated brainstorming artifacts.
- No patient web form input named `storageKey`, `contentType`, `sizeBytes`, or `originalFilename` remains.
- `rg -n "PhotoReferenceRequest" src/main/java src/test/java` returns no results.
