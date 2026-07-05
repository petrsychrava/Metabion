package com.metabion.dto.mcp;

public record DietPhotoContentResponse(
        Long photoId,
        String contentType,
        long sizeBytes,
        String base64Content
) {
}
