package com.metabion.service;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StorageKeyValidatorTest {

    private final StorageKeyValidator validator = new StorageKeyValidator();

    @Test
    void allowsNullAndOpaqueRelativeKeys() {
        validator.validate(null);
        validator.validate("pending/meal.jpg");
        validator.validate("patients/10/logs/2026-06-10/photo_1-2.HEIC");
    }

    @Test
    void rejectsUnsafeOrNonOpaqueKeys() {
        assertRejected("https://example.com/meal.jpg");
        assertRejected("../meal.jpg");
        assertRejected("/tmp/meal.jpg");
        assertRejected("C:\\Users\\x\\meal.jpg");
        assertRejected("\\\\server\\share\\meal.jpg");
        assertRejected("~/meal.jpg");
        assertRejected("file:/tmp/meal.jpg");
        assertRejected("meal.jpg?token=abc");
        assertRejected("meal.jpg#sig");
        assertRejected("pending/meal.jpg?signature=abc");
        assertRejected("pending//meal.jpg");
        assertRejected("pending/./meal.jpg");
        assertRejected("pending/../meal.jpg");
        assertRejected("pending/meal 1.jpg");
        assertRejected("pending/meal@1.jpg");
    }

    private void assertRejected(String storageKey) {
        assertThatThrownBy(() -> validator.validate(storageKey))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("400 BAD_REQUEST")
                .hasMessageContaining("photo storageKey is not allowed");
    }
}
