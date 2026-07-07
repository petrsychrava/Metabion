package com.metabion.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class OAuthAuthorizationPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfig.class)
            .withPropertyValues(
                    "metabion.oauth.issuer=http://localhost:8080",
                    "metabion.oauth.resource=http://localhost:8080/api/mcp",
                    "metabion.oauth.authorization-code-ttl=PT5M",
                    "metabion.oauth.access-token-ttl=PT1H",
                    "metabion.oauth.client-metadata.enabled=true",
                    "metabion.oauth.clients.codex.display-label=Codex",
                    "metabion.oauth.clients.codex.redirect-uris=http://127.0.0.1:1455/oauth/callback",
                    "metabion.oauth.clients.claude.display-label=Claude",
                    "metabion.oauth.clients.claude.redirect-uris=http://127.0.0.1:1456/oauth/callback");

    @Test
    void bindsIssuerResourceTtlsAndKnownClients() {
        contextRunner.run(context -> {
            var props = context.getBean(OAuthAuthorizationProperties.class);

            assertThat(props.issuer()).isEqualTo("http://localhost:8080");
            assertThat(props.resource()).isEqualTo("http://localhost:8080/api/mcp");
            assertThat(props.authorizationCodeTtl()).isEqualTo(java.time.Duration.ofMinutes(5));
            assertThat(props.accessTokenTtl()).isEqualTo(java.time.Duration.ofHours(1));
            assertThat(props.clientMetadata().enabled()).isTrue();
            assertThat(props.clients()).containsKeys("codex", "claude");
            assertThat(props.clients().get("codex").displayLabel()).isEqualTo("Codex");
            assertThat(props.clients().get("codex").redirectUris())
                    .containsExactly("http://127.0.0.1:1455/oauth/callback");
        });
    }

    @EnableConfigurationProperties(OAuthAuthorizationProperties.class)
    static class TestConfig {
    }
}
