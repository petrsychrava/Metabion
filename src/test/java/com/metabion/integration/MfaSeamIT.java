package com.metabion.integration;

import com.metabion.service.MfaChallengeService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

class MfaSeamIT extends AbstractAuthIT {

    @MockitoBean
    MfaChallengeService mfa;

    @Test
    void noop_returns_authenticated() throws Exception {
        when(mfa.isRequired(any())).thenReturn(false);
        createEnabledUser("alice@example.com", "CorrectPass123");
        var client = newClient();

        var response = login(client, "alice@example.com", "CorrectPass123");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json(response).get("status").asText()).isEqualTo("AUTHENTICATED");
        assertThat(client.cookie("SESSION")).isNotBlank();
    }

    @Test
    void mock_returns_mfa_required() throws Exception {
        when(mfa.isRequired(any())).thenReturn(true);
        createEnabledUser("carol@example.com", "CorrectPass123");
        var client = newClient();

        var response = login(client, "carol@example.com", "CorrectPass123");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json(response).get("status").asText()).isEqualTo("MFA_REQUIRED");
        assertThat(json(response).get("challengeId").asText()).isNotBlank();
        assertThat(client.cookie("SESSION")).isNull();
    }
}
