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
