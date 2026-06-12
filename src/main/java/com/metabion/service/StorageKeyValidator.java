package com.metabion.service;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.regex.Pattern;

@Service
public class StorageKeyValidator {

    private static final Pattern ALLOWED_STORAGE_KEY = Pattern.compile("[A-Za-z0-9_./-]+");

    public void validate(String storageKey) {
        if (storageKey == null) {
            return;
        }
        if (!ALLOWED_STORAGE_KEY.matcher(storageKey).matches()
                || storageKey.startsWith("/")
                || storageKey.contains("\\")
                || storageKey.contains(":")
                || storageKey.contains("?")
                || storageKey.contains("#")
                || hasUnsafeSegment(storageKey)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "photo storageKey is not allowed");
        }
    }

    private boolean hasUnsafeSegment(String storageKey) {
        var segments = storageKey.split("/", -1);
        for (var segment : segments) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                return true;
            }
        }
        return false;
    }
}
