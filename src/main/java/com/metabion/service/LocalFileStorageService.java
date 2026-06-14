package com.metabion.service;

import com.metabion.dto.FileStorageResource;
import com.metabion.dto.StoredFile;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

@Service
public class LocalFileStorageService implements FileStorageService {

    private final Path root;

    public LocalFileStorageService(@Value("${metabion.storage.local.root:./var/metabion-storage}") String root) {
        this(Path.of(root));
    }

    LocalFileStorageService(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    @Override
    public StoredFile store(String storageKey, InputStream inputStream, long sizeBytes) throws IOException {
        var target = resolveSafe(storageKey);
        Files.createDirectories(target.getParent());
        var digest = sha256();
        long written;
        try (var source = new DigestInputStream(new BufferedInputStream(inputStream), digest);
             var output = new BufferedOutputStream(Files.newOutputStream(target))) {
            written = source.transferTo(output);
        }
        if (written != sizeBytes) {
            Files.deleteIfExists(target);
            throw new IOException("Stored byte count does not match expected size");
        }
        return new StoredFile(storageKey, written, HexFormat.of().formatHex(digest.digest()));
    }

    @Override
    public FileStorageResource read(String storageKey) throws IOException {
        var target = resolveSafe(storageKey);
        return new FileStorageResource(Files.newInputStream(target), Files.size(target));
    }

    @Override
    public void delete(String storageKey) throws IOException {
        try {
            Files.deleteIfExists(resolveSafe(storageKey));
        } catch (NoSuchFileException ignored) {
            // Idempotent cleanup path.
        }
    }

    private Path resolveSafe(String storageKey) {
        if (storageKey == null || storageKey.isBlank()) {
            throw new IllegalArgumentException("storageKey is required");
        }
        var resolved = root.resolve(storageKey).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("storageKey is outside storage root");
        }
        return resolved;
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }
}
