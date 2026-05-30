package com.metabion.integration;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;

class CsrfIT extends AbstractAuthIT {

    @Test
    void protected_post_without_token_is_403() {
        createEnabledUser("alice@example.com", "CorrectPass123");
        var client = newClient();
        assertThat(login(client, "alice@example.com", "CorrectPass123").getStatusCode()).isEqualTo(HttpStatus.OK);

        var response = client.post("/api/auth/logout", null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(me(client).getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
