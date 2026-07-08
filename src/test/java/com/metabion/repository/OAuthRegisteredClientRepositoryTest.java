package com.metabion.repository;

import com.metabion.domain.OAuthRegisteredClient;
import com.metabion.domain.PatientAccessTokenScope;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest(properties = {
        "spring.profiles.active=dev",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"
})
class OAuthRegisteredClientRepositoryTest {

    private static final Instant NOW = Instant.parse("2026-07-08T10:00:00Z");

    @Autowired
    OAuthRegisteredClientRepository clients;

    @Test
    void findByClientIdPreservesRedirectUriOrderAfterRoundTrip() {
        var redirectUris = List.of(
                "https://example.com/callback/one",
                "http://127.0.0.1:49152/callback",
                "https://example.org/callback/two");

        clients.saveAndFlush(client(redirectUris, Set.of(PatientAccessTokenScope.PATIENT_PROFILE_READ.authority())));

        var found = clients.findByClientId("mcp_client_abc123");

        assertThat(found).isPresent();
        assertThat(found.orElseThrow().redirectUris()).containsExactlyElementsOf(redirectUris);
    }

    @Test
    void acceptsHttpsRedirectUri() {
        clients.saveAndFlush(client(
                List.of("https://example.com/callback"),
                Set.of(PatientAccessTokenScope.PATIENT_PROFILE_READ.authority())));

        var found = clients.findByClientId("mcp_client_abc123");

        assertThat(found).isPresent();
        assertThat(found.orElseThrow().redirectUris()).containsExactly("https://example.com/callback");
    }

    @Test
    void acceptsIpv6LoopbackRedirectUriWithExplicitPort() {
        clients.saveAndFlush(client(
                List.of("http://[::1]:49152/callback"),
                Set.of(PatientAccessTokenScope.PATIENT_PROFILE_READ.authority())));

        var found = clients.findByClientId("mcp_client_abc123");

        assertThat(found).isPresent();
        assertThat(found.orElseThrow().redirectUris()).containsExactly("http://[::1]:49152/callback");
    }

    @Test
    void rejectsNonLoopbackPlainHttpRedirectUri() {
        assertThatThrownBy(() -> client(
                List.of("http://example.com/callback"),
                Set.of(PatientAccessTokenScope.PATIENT_PROFILE_READ.authority())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("redirect uri must use https or loopback http with an explicit port");
    }

    @Test
    void rejectsLoopbackHttpRedirectUriWithoutPort() {
        assertThatThrownBy(() -> client(
                List.of("http://127.0.0.1/callback"),
                Set.of(PatientAccessTokenScope.PATIENT_PROFILE_READ.authority())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("redirect uri must use https or loopback http with an explicit port");
    }

    @Test
    void rejectsClientSecretBasicTokenEndpointAuthMethod() {
        assertThatThrownBy(() -> new OAuthRegisteredClient(
                "mcp_client_abc123",
                "Codex",
                "client_secret_basic",
                List.of("https://example.com/callback"),
                Set.of(PatientAccessTokenScope.PATIENT_PROFILE_READ.authority()),
                NOW,
                NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("token endpoint auth method must be none");
    }

    @Test
    void rejectsUnsupportedScope() {
        assertThatThrownBy(() -> client(
                List.of("https://example.com/callback"),
                Set.of("patient:unknown:scope")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported patient token scope");
    }

    private OAuthRegisteredClient client(List<String> redirectUris, Set<String> scopes) {
        return new OAuthRegisteredClient(
                "mcp_client_abc123",
                "Codex",
                "none",
                redirectUris,
                scopes,
                NOW,
                NOW);
    }
}
