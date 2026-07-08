package com.metabion.repository;

import com.metabion.domain.OAuthRegisteredClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(properties = {
        "spring.profiles.active=dev",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"
})
class OAuthRegisteredClientRepositoryTest {

    @Autowired
    OAuthRegisteredClientRepository clients;

    @Test
    void findByClientIdLoadsRedirectUrisAndScopes() {
        var now = Instant.parse("2026-07-08T10:00:00Z");
        clients.saveAndFlush(new OAuthRegisteredClient(
                "mcp_client_abc123",
                "Codex",
                "none",
                List.of("http://127.0.0.1:49152/callback"),
                Set.of("patient:profile:read"),
                now,
                now));

        var found = clients.findByClientId("mcp_client_abc123");

        assertThat(found).isPresent();
        assertThat(found.get().getClientName()).isEqualTo("Codex");
        assertThat(found.get().getTokenEndpointAuthMethod()).isEqualTo("none");
        assertThat(found.get().redirectUris()).containsExactly("http://127.0.0.1:49152/callback");
        assertThat(found.get().scopes()).containsExactly("patient:profile:read");
    }
}
