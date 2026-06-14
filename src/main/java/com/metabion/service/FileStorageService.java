package com.metabion.service;

import com.metabion.dto.FileStorageResource;
import com.metabion.dto.StoredFile;

import java.io.IOException;
import java.io.InputStream;

public interface FileStorageService {

    StoredFile store(String storageKey, InputStream inputStream, long sizeBytes) throws IOException;

    FileStorageResource read(String storageKey) throws IOException;

    void delete(String storageKey) throws IOException;
}
