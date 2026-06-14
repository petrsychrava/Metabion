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
