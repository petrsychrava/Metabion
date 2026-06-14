package com.metabion.dto;

import java.io.IOException;
import java.io.InputStream;

public record FileStorageResource(
        InputStream inputStream,
        long sizeBytes
) implements AutoCloseable {

    @Override
    public void close() throws IOException {
        inputStream.close();
    }
}
