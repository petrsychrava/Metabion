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
