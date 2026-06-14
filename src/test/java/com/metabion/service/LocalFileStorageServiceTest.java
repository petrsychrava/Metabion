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
