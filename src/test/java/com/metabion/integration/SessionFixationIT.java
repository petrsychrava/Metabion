package com.metabion.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;

import static org.assertj.core.api.Assertions.assertThat;

class SessionFixationIT extends AbstractAuthIT {

    @Autowired
    @SuppressWarnings("rawtypes")
    FindByIndexNameSessionRepository sessions;

    @Test
    @SuppressWarnings("unchecked")
    void session_id_changes_on_login() {
        createEnabledUser("alice@example.com", "CorrectPass123");
        var client = newClient();
        var anonymousSession = (Session) sessions.createSession();
        sessions.save(anonymousSession);
        client.addCookie("SESSION", anonymousSession.getId());

        var login = login(client, "alice@example.com", "CorrectPass123");

        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(client.cookie("SESSION")).isNotBlank();
        assertThat(client.cookie("SESSION")).isNotEqualTo(anonymousSession.getId());
    }
}
