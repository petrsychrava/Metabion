package com.metabion.service;

import com.metabion.domain.DailyDietLog;
import com.metabion.domain.DailyDietLogMeal;
import com.metabion.domain.DailyDietLogPhotoReference;
import com.metabion.domain.DietLogPhotoStatus;
import com.metabion.domain.FoodCategory;
import com.metabion.domain.MealType;
import com.metabion.domain.PatientProfile;
import com.metabion.domain.RoleName;
import com.metabion.domain.User;
import com.metabion.dto.DailyDietLogRequest;
import com.metabion.dto.FileStorageResource;
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
import java.io.InputStream;
import java.nio.file.NoSuchFileException;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
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
    void uploadRejectsUnsupportedSignature() throws Exception {
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

    @Test
    void attachUploadsRejectsCrossPatientPhoto() {
        var patientUser = user(1L, "patient@example.com", RoleName.PATIENT);
        var patient = patient(10L, patientUser);
        var otherPatient = patient(20L, user(2L, "other@example.com", RoleName.PATIENT));
        var log = new DailyDietLog(patient, LocalDate.of(2026, 6, 10));
        log.addMeal(new DailyDietLogMeal(MealType.LUNCH, FoodCategory.PROTEIN, "Lunch", null, 0));
        var photo = DailyDietLogPhotoReference.pending(
                otherPatient,
                otherPatient.getUser(),
                "x.jpg",
                "image/jpeg",
                4L,
                "a".repeat(64),
                "diet-log-photos/20/x.jpg");
        org.springframework.test.util.ReflectionTestUtils.setField(photo, "id", 99L);
        when(photos.findByIdIn(List.of(99L))).thenReturn(List.of(photo));

        assertThatThrownBy(() -> service.attachToLog(
                patient,
                log,
                List.of(new DailyDietLogRequest.PhotoUploadReferenceRequest(0, 99L, "caption"))))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("400 BAD_REQUEST")
                .hasMessageContaining("photo upload is invalid");
    }

    @Test
    void attachToLogReplacesExistingAttachedPhotosWithRequestedSet() {
        var user = user(1L, "patient@example.com", RoleName.PATIENT);
        var patient = patient(10L, user);
        var log = new DailyDietLog(patient, LocalDate.of(2026, 6, 10));
        log.addMeal(new DailyDietLogMeal(MealType.LUNCH, FoodCategory.PROTEIN, "Lunch", null, 0));
        var kept = attachedPhoto(50L, patient, user, log, "old caption", 0);
        var removed = attachedPhoto(51L, patient, user, log, "remove me", 1);
        when(photos.findByIdIn(List.of(50L))).thenReturn(List.of(kept));

        service.attachToLog(
                patient,
                log,
                List.of(new DailyDietLogRequest.PhotoUploadReferenceRequest(0, 50L, "updated caption")));

        assertThat(kept.getStatus()).isEqualTo(DietLogPhotoStatus.ATTACHED);
        assertThat(kept.getCaption()).isEqualTo("updated caption");
        assertThat(kept.getSortOrder()).isZero();
        assertThat(kept.getMeal()).isSameAs(log.getMeals().getFirst());
        assertThat(removed.getStatus()).isEqualTo(DietLogPhotoStatus.REMOVED);
        assertThat(removed.getRemovedByUser()).isSameAs(user);
        assertThat(removed.getRemovedAt()).isNotNull();
    }

    @Test
    void attachToLogAssignsPhotoToRequestedMealIndex() {
        var user = user(1L, "patient@example.com", RoleName.PATIENT);
        var patient = patient(10L, user);
        var log = new DailyDietLog(patient, LocalDate.of(2026, 6, 10));
        var lunch = new DailyDietLogMeal(MealType.LUNCH, FoodCategory.PROTEIN, "Salmon", null, 0);
        var dinner = new DailyDietLogMeal(MealType.DINNER, FoodCategory.LOW_CARB_VEGETABLES, "Greens", null, 1);
        log.addMeal(lunch);
        log.addMeal(dinner);
        var photo = pendingPhoto(50L, patient, user);
        when(photos.findByIdIn(List.of(50L))).thenReturn(List.of(photo));

        service.attachToLog(
                patient,
                log,
                List.of(new DailyDietLogRequest.PhotoUploadReferenceRequest(1, 50L, "Dinner")));

        assertThat(photo.getMeal()).isSameAs(dinner);
        assertThat(log.getPhotoReferences()).contains(photo);
    }

    @Test
    void attachToLogRejectsInvalidMealIndex() {
        var user = user(1L, "patient@example.com", RoleName.PATIENT);
        var patient = patient(10L, user);
        var log = new DailyDietLog(patient, LocalDate.of(2026, 6, 10));
        log.addMeal(new DailyDietLogMeal(MealType.LUNCH, FoodCategory.PROTEIN, "Salmon", null, 0));
        var photo = pendingPhoto(50L, patient, user);
        when(photos.findByIdIn(List.of(50L))).thenReturn(List.of(photo));

        assertThatThrownBy(() -> service.attachToLog(
                patient,
                log,
                List.of(new DailyDietLogRequest.PhotoUploadReferenceRequest(2, 50L, "Bad index"))))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("400 BAD_REQUEST")
                .hasMessageContaining("photo upload is invalid");
    }

    @Test
    void attachToLogRejectsMissingMealIndex() {
        var user = user(1L, "patient@example.com", RoleName.PATIENT);
        var patient = patient(10L, user);
        var log = new DailyDietLog(patient, LocalDate.of(2026, 6, 10));
        log.addMeal(new DailyDietLogMeal(MealType.LUNCH, FoodCategory.PROTEIN, "Salmon", null, 0));
        var photo = pendingPhoto(50L, patient, user);
        when(photos.findByIdIn(List.of(50L))).thenReturn(List.of(photo));

        assertThatThrownBy(() -> service.attachToLog(
                patient,
                log,
                List.of(new DailyDietLogRequest.PhotoUploadReferenceRequest(50L, "Missing meal"))))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("400 BAD_REQUEST")
                .hasMessageContaining("photo upload is invalid");
    }

    @Test
    void attachToLogEmptyRequestRemovesExistingAttachedPhotos() {
        var user = user(1L, "patient@example.com", RoleName.PATIENT);
        var patient = patient(10L, user);
        var log = new DailyDietLog(patient, LocalDate.of(2026, 6, 10));
        var removed = attachedPhoto(51L, patient, user, log, "remove me", 0);

        service.attachToLog(patient, log, List.of());

        assertThat(removed.getStatus()).isEqualTo(DietLogPhotoStatus.REMOVED);
        assertThat(removed.getRemovedByUser()).isSameAs(user);
        assertThat(removed.getRemovedAt()).isNotNull();
    }

    @Test
    void patientCanReadOwnPendingPhotoContent() throws Exception {
        var user = user(1L, "patient@example.com", RoleName.PATIENT);
        var patient = patient(10L, user);
        var photo = DailyDietLogPhotoReference.pending(
                patient,
                user,
                "plate.jpg",
                "image/jpeg",
                3L,
                "a".repeat(64),
                "diet-log-photos/10/plate.jpg");
        org.springframework.test.util.ReflectionTestUtils.setField(photo, "id", 50L);
        when(users.findByEmail("patient@example.com")).thenReturn(Optional.of(user));
        when(photos.findById(50L)).thenReturn(Optional.of(photo));
        when(storage.read("diet-log-photos/10/plate.jpg"))
                .thenReturn(new FileStorageResource(new ByteArrayInputStream(new byte[]{1, 2, 3}), 3));

        var content = service.readContent(auth("patient@example.com"), 50L);

        assertThat(content.contentType()).isEqualTo("image/jpeg");
        assertThat(content.resource().sizeBytes()).isEqualTo(3);
    }

    @Test
    void clinicalUserCannotReadPendingPhotoContent() throws Exception {
        var patientUser = user(1L, "patient@example.com", RoleName.PATIENT);
        var clinician = user(2L, "doctor@example.com", RoleName.PHYSICIAN);
        var photo = DailyDietLogPhotoReference.pending(
                patient(10L, patientUser),
                patientUser,
                "plate.jpg",
                "image/jpeg",
                3L,
                "a".repeat(64),
                "diet-log-photos/10/plate.jpg");
        org.springframework.test.util.ReflectionTestUtils.setField(photo, "id", 50L);
        when(users.findByEmail("doctor@example.com")).thenReturn(Optional.of(clinician));
        when(photos.findById(50L)).thenReturn(Optional.of(photo));

        assertThatThrownBy(() -> service.readContent(auth("doctor@example.com"), 50L))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("403 FORBIDDEN")
                .hasMessageContaining("Current user cannot read photo");
        verify(storage, never()).read(anyString());
    }

    @Test
    void cleanupDeletesOldPendingRowsAndToleratesMissingFiles() throws Exception {
        var user = user(1L, "patient@example.com", RoleName.PATIENT);
        var pending = DailyDietLogPhotoReference.pending(
                patient(10L, user),
                user,
                "old.jpg",
                "image/jpeg",
                4L,
                "a".repeat(64),
                "diet-log-photos/10/old.jpg");
        when(photos.findByStatusAndCreatedAtBefore(eq(DietLogPhotoStatus.PENDING), any()))
                .thenReturn(List.of(pending));
        doThrow(new NoSuchFileException("old.jpg"))
                .when(storage).delete("diet-log-photos/10/old.jpg");

        service.cleanupPendingUploads();

        verify(photos).delete(pending);
    }

    private void givenPatient() {
        var user = user(1L, "patient@example.com", RoleName.PATIENT);
        when(users.findByEmail("patient@example.com")).thenReturn(Optional.of(user));
        when(patientProfiles.findByUserId(1L)).thenReturn(Optional.of(patient(10L, user)));
    }

    private static TestingAuthenticationToken auth(String email) {
        var authentication = new TestingAuthenticationToken(email, "n/a");
        authentication.setAuthenticated(true);
        return authentication;
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

    private static DailyDietLogPhotoReference attachedPhoto(Long id,
                                                            PatientProfile patient,
                                                            User user,
                                                            DailyDietLog log,
                                                            String caption,
                                                            int sortOrder) {
        var photo = DailyDietLogPhotoReference.pending(
                patient,
                user,
                "plate-" + id + ".jpg",
                "image/jpeg",
                4L,
                "a".repeat(64),
                "diet-log-photos/10/plate-" + id + ".jpg");
        org.springframework.test.util.ReflectionTestUtils.setField(photo, "id", id);
        photo.attachTo(log, caption, sortOrder);
        log.addPhotoReference(photo);
        return photo;
    }

    private static DailyDietLogPhotoReference pendingPhoto(Long id, PatientProfile patient, User user) {
        var photo = DailyDietLogPhotoReference.pending(
                patient,
                user,
                "plate-" + id + ".jpg",
                "image/jpeg",
                4L,
                "a".repeat(64),
                "diet-log-photos/10/plate-" + id + ".jpg");
        org.springframework.test.util.ReflectionTestUtils.setField(photo, "id", id);
        return photo;
    }
}
