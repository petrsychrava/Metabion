package com.metabion.repository;

import com.metabion.domain.OAuthAuthorizationCode;
import com.metabion.domain.RoleName;
import com.metabion.domain.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(properties = {
        "spring.profiles.active=dev",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"
})
class OAuthAuthorizationCodeRepositoryTest {

    @Autowired
    UserRepository users;

    @Autowired
    OAuthAuthorizationCodeRepository codes;

    @Test
    void findByCodeHashLoadsPatientAndScopes() {
        var user = new User("patient@example.com", "hash");
        user.setEnabled(true);
        user.addRole(RoleName.PATIENT);
        users.saveAndFlush(user);

        codes.saveAndFlush(new OAuthAuthorizationCode(
                "hash-1",
                user,
                "codex",
                "Codex",
                "http://127.0.0.1:1455/oauth/callback",
                "http://localhost:8080/api/mcp",
                "challenge",
                "S256",
                Set.of("patient:profile:read"),
                Instant.parse("2026-07-06T10:00:00Z"),
                Instant.parse("2026-07-06T10:05:00Z")));

        var found = codes.findByCodeHash("hash-1");

        assertThat(found).isPresent();
        assertThat(found.get().getUser().getEmail()).isEqualTo("patient@example.com");
        assertThat(found.get().scopes()).containsExactly("patient:profile:read");
    }
}
