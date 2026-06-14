package com.metabion.service;

import com.metabion.domain.DailyDietLog;
import com.metabion.domain.DailyDietLogPhotoReference;
import com.metabion.domain.DietLogPhotoStatus;
import com.metabion.domain.PatientProfile;
import com.metabion.domain.RoleName;
import com.metabion.domain.User;
import com.metabion.dto.DailyDietLogRequest;
import com.metabion.dto.DietLogPhotoUploadResponse;
import com.metabion.dto.FileStorageResource;
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
import java.nio.file.NoSuchFileException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

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
            return response(
                    saved.getId(),
                    saved.getOriginalFilename(),
                    saved.getContentType(),
                    saved.getSizeBytes(),
                    saved.getCaption());
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "photo could not be stored", ex);
        }
    }

    public record PhotoContent(String contentType, FileStorageResource resource) {
    }

    @Transactional(readOnly = true)
    public PhotoContent readContent(Authentication authentication, Long photoId) {
        var user = currentUser(authentication);
        var photo = photos.findById(photoId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "photo not found"));
        requireCanRead(user, authentication, photo);
        try {
            return new PhotoContent(photo.getContentType(), storage.read(photo.getStorageKey()));
        } catch (NoSuchFileException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "photo content not found", ex);
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "photo content could not be read", ex);
        }
    }

    @org.springframework.scheduling.annotation.Scheduled(
            fixedDelayString = "${metabion.diet-log-photos.cleanup-delay:PT1H}")
    public void cleanupPendingUploads() {
        var cutoff = Instant.now().minus(properties.pendingRetention());
        var pending = photos.findByStatusAndCreatedAtBefore(DietLogPhotoStatus.PENDING, cutoff);
        for (var photo : pending) {
            try {
                storage.delete(photo.getStorageKey());
            } catch (NoSuchFileException ignored) {
                // Cleanup is idempotent; delete the stale row below.
            } catch (IOException ex) {
                continue;
            }
            photos.delete(photo);
        }
    }

    public void attachToLog(PatientProfile patient,
                            DailyDietLog log,
                            List<DailyDietLogRequest.PhotoUploadReferenceRequest> requests) {
        var safeRequests = requests == null ? List.<DailyDietLogRequest.PhotoUploadReferenceRequest>of() : requests;
        if (safeRequests.size() > properties.maxPhotosPerLog()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "too many photos");
        }

        var seenIds = new HashSet<Long>();
        for (var request : safeRequests) {
            if (request == null || request.uploadId() == null || !seenIds.add(request.uploadId())) {
                throw invalidPhotoUpload();
            }
        }
        var ids = safeRequests.stream()
                .map(DailyDietLogRequest.PhotoUploadReferenceRequest::uploadId)
                .toList();

        var found = ids.isEmpty()
                ? Map.<Long, DailyDietLogPhotoReference>of()
                : photos.findByIdIn(ids).stream()
                        .collect(Collectors.toMap(DailyDietLogPhotoReference::getId, Function.identity()));
        for (var request : safeRequests) {
            validateAttachable(patient, log, found.get(request.uploadId()));
        }
        removeOmittedAttachedPhotos(log, seenIds, patient.getUser());

        for (var i = 0; i < safeRequests.size(); i++) {
            var request = safeRequests.get(i);
            var photo = found.get(request.uploadId());
            photo.attachTo(log, DietLogRequestMapper.trimToNull(request.caption()), i);
            if (!log.getPhotoReferences().contains(photo)) {
                log.addPhotoReference(photo);
            }
        }
    }

    private static void removeOmittedAttachedPhotos(DailyDietLog log, Set<Long> requestedIds, User removedByUser) {
        log.getPhotoReferences().stream()
                .filter(photo -> photo.getStatus() == DietLogPhotoStatus.ATTACHED)
                .filter(photo -> photo.getId() == null || !requestedIds.contains(photo.getId()))
                .forEach(photo -> photo.markRemoved(removedByUser));
    }

    private static void validateAttachable(PatientProfile patient, DailyDietLog log, DailyDietLogPhotoReference photo) {
        if (patient == null || log == null || photo == null || photo.getPatientProfile() == null) {
            throw invalidPhotoUpload();
        }
        if (!samePatient(patient, photo.getPatientProfile())) {
            throw invalidPhotoUpload();
        }
        var existingLog = photo.getDailyDietLog();
        if (existingLog != null && existingLog != log && !sameLog(existingLog, log)) {
            throw invalidPhotoUpload();
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
        if (user.hasRole(RoleName.PATIENT) && sameUser(patient.getUser(), user)) {
            return;
        }
        if (photo.getStatus() == DietLogPhotoStatus.ATTACHED
                && user.hasAnyRole(RoleName.NUTRITION_SPECIALIST, RoleName.PHYSICIAN, RoleName.COORDINATOR)
                && accessControl.canAccessPatientProfile(authentication, patient.getId())) {
            return;
        }
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Current user cannot read photo");
    }

    private static boolean samePatient(PatientProfile first, PatientProfile second) {
        if (first.getId() != null && second.getId() != null) {
            return first.getId().equals(second.getId());
        }
        return first == second;
    }

    private static boolean sameLog(DailyDietLog first, DailyDietLog second) {
        if (first.getId() != null && second.getId() != null) {
            return first.getId().equals(second.getId());
        }
        return first == second;
    }

    private static boolean sameUser(User first, User second) {
        if (first == null || second == null) {
            return false;
        }
        if (first.getId() != null && second.getId() != null) {
            return first.getId().equals(second.getId());
        }
        return first == second;
    }

    private static ResponseStatusException invalidPhotoUpload() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "photo upload is invalid");
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
        var normalized = filename.replace("\\", "/");
        return normalized.substring(normalized.lastIndexOf('/') + 1);
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

    private static DietLogPhotoUploadResponse response(
            Long id,
            String filename,
            String contentType,
            Long sizeBytes,
            String caption) {
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
