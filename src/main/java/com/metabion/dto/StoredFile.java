package com.metabion.dto;

public record StoredFile(
        String storageKey,
        long sizeBytes,
        String sha256
) {
}
