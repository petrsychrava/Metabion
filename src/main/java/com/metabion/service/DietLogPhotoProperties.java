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
