package com.metabion.integration;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;

class PasswordResetSessionIT extends AbstractAuthIT {

    @Test
    void reset_kills_existing_sessions() throws Exception {
        createEnabledUser("bob@example.com", "CorrectPass123");
        var clientA = newClient();
        assertThat(login(clientA, "bob@example.com", "CorrectPass123").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(me(clientA).getStatusCode()).isEqualTo(HttpStatus.OK);

        var clientB = newClient();
        forgotPassword(clientB, "bob@example.com");
        assertThat(greenMail.waitForIncomingEmail(2_000, 1)).isTrue();
        var token = latestEmailToken();
        assertThat(resetPassword(clientB, token, "BrandNewPass123").getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(me(clientA).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(login(newClient(), "bob@example.com", "CorrectPass123").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(login(newClient(), "bob@example.com", "BrandNewPass123").getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
