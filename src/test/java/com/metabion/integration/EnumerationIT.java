package com.metabion.integration;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;

class EnumerationIT extends AbstractAuthIT {

    @Test
    void validation_failures_are_uniform() {
        createEnabledUser("known@example.com", "CorrectPass123");

        var known = register(newClient(), "known@example.com", "short");
        var unknown = register(newClient(), "unknown@example.com", "short");

        assertThat(known.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(unknown.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(known.getBody()).isEqualTo(unknown.getBody());
    }
}
