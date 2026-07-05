package com.metabion.dto.mcp;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record DietPhotoBase64UploadRequest(
        @NotBlank @Size(max = 160) String filename,
        @NotBlank @Size(max = 120) String contentType,
        @NotBlank String base64Content
) {
}
