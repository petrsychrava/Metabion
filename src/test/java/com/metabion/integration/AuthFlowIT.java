package com.metabion.integration;

import com.metabion.repository.PasswordResetRepository;
import com.metabion.repository.VerificationTokenRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class AuthFlowIT extends AbstractAuthIT {

    private static final String PASSWORD = "CorrectPass123";

    @Autowired
    VerificationTokenRepository verificationTokens;

    @Test
    void smtpTestServerUsesAnEphemeralPort() {
        assertThat(greenMail.getSmtp().getPort()).isNotEqualTo(3025).isPositive();
    }

    @Test
    void register_new_email() throws Exception {
        var response = register(newClient(), "Alice@Example.com", PASSWORD);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json(response).get("status").asText()).isEqualTo("ok");
        assertThat(greenMail.getReceivedMessages()).hasSize(1);

        var user = users.findByEmail("alice@example.com").orElseThrow();
        assertThat(user.isEnabled()).isFalse();
    }

    @Test
    void register_existing_email_is_silent() throws Exception {
        var client = newClient();
        register(client, "alice@example.com", PASSWORD);
        greenMail.purgeEmailFromAllMailboxes();

        var response = register(client, "ALICE@example.com", PASSWORD);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json(response).get("status").asText()).isEqualTo("ok");
        assertThat(users.count()).isEqualTo(1);
        assertThat(greenMail.getReceivedMessages()).isEmpty();
    }

    @Test
    void verify_happy_path() throws Exception {
        register(newClient(), "alice@example.com", PASSWORD);
        var token = latestEmailToken();

        var response = verifyToken(newClient(), token);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(users.findByEmail("alice@example.com").orElseThrow().isEnabled()).isTrue();
    }

    @Test
    void verify_expired() throws Exception {
        register(newClient(), "alice@example.com", PASSWORD);
        var token = latestEmailToken();
        var persisted = verificationTokens.findByTokenHash(sha256Hex(token)).orElseThrow();
        persisted.setExpiresAt(Instant.now().minusSeconds(1));
        verificationTokens.saveAndFlush(persisted);

        var response = verifyToken(newClient(), token);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(json(response).get("error").asText()).isEqualTo("invalid_token");
    }

    @Test
    void verify_consumed() throws Exception {
        register(newClient(), "alice@example.com", PASSWORD);
        var token = latestEmailToken();
        assertThat(verifyToken(newClient(), token).getStatusCode()).isEqualTo(HttpStatus.OK);

        var response = verifyToken(newClient(), token);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(json(response).get("error").asText()).isEqualTo("invalid_token");
    }

    @Test
    void login_happy_path() throws Exception {
        createEnabledUser("alice@example.com", PASSWORD);
        var client = newClient();

        var response = login(client, "alice@example.com", PASSWORD);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        var body = json(response);
        assertThat(body.get("status").asText()).isEqualTo("AUTHENTICATED");
        assertThat(client.cookie("SESSION")).isNotBlank();
    }

    @Test
    void login_wrong_password() throws Exception {
        createEnabledUser("alice@example.com", PASSWORD);

        var response = login(newClient(), "alice@example.com", "WrongPassword123");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(json(response).get("error").asText()).isEqualTo("invalid_credentials");
    }

    @Test
    void login_locked_after_five_failures() throws Exception {
        createEnabledUser("alice@example.com", PASSWORD);
        var client = newClient();

        for (int i = 0; i < 5; i++) {
            var response = login(client, "alice@example.com", "WrongPassword123");
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(json(response).get("error").asText()).isEqualTo("invalid_credentials");
        }

        var locked = login(client, "alice@example.com", PASSWORD);
        assertThat(locked.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(json(locked).get("error").asText()).isEqualTo("invalid_credentials");
    }

    @Test
    void forgot_password_sends_email() throws Exception {
        createEnabledUser("alice@example.com", PASSWORD);

        var response = forgotPassword(newClient(), "alice@example.com");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json(response).get("status").asText()).isEqualTo("ok");
        assertThat(greenMail.waitForIncomingEmail(2_000, 1)).isTrue();
    }

    @Test
    void reset_password_happy_path() throws Exception {
        createEnabledUser("alice@example.com", PASSWORD);
        forgotPassword(newClient(), "alice@example.com");
        assertThat(greenMail.waitForIncomingEmail(2_000, 1)).isTrue();
        var token = latestEmailToken();

        var reset = resetPassword(newClient(), token, "BrandNewPass123");
        var oldLogin = login(newClient(), "alice@example.com", PASSWORD);
        var newLogin = login(newClient(), "alice@example.com", "BrandNewPass123");

        assertThat(reset.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(oldLogin.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(newLogin.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void reset_consumed_token() throws Exception {
        createEnabledUser("alice@example.com", PASSWORD);
        forgotPassword(newClient(), "alice@example.com");
        assertThat(greenMail.waitForIncomingEmail(2_000, 1)).isTrue();
        var token = latestEmailToken();
        assertThat(resetPassword(newClient(), token, "BrandNewPass123").getStatusCode()).isEqualTo(HttpStatus.OK);

        var consumed = resetPassword(newClient(), token, "AnotherPass123");

        assertThat(consumed.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(json(consumed).get("error").asText()).isEqualTo("invalid_token");
    }

    @Test
    void logout_invalidates_session() {
        createEnabledUser("alice@example.com", PASSWORD);
        var client = newClient();
        assertThat(login(client, "alice@example.com", PASSWORD).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(me(client).getStatusCode()).isEqualTo(HttpStatus.OK);

        var logout = logout(client);
        var afterLogout = me(client);

        assertThat(logout.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(afterLogout.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
