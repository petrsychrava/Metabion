package com.metabion.integration;

import com.metabion.domain.PatientAccessClientType;
import com.metabion.domain.PatientAccessToken;
import com.metabion.domain.PatientAccessTokenScope;
import com.metabion.domain.RoleName;
import com.metabion.domain.User;
import com.metabion.service.PatientAccessAuditService;
import com.metabion.service.PatientAccessTokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.session.web.http.SessionRepositoryFilter;
import org.springframework.web.context.WebApplicationContext;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.profiles.active=dev",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.datasource.url=jdbc:h2:mem:mcp_bearer_session_persistence;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.session.jdbc.initialize-schema=always",
        "metabion.mcp.enabled=true",
        "spring.ai.mcp.server.enabled=true"
})
class McpBearerSessionPersistenceIT {

    @Autowired
    WebApplicationContext context;

    @Autowired
    SessionRepositoryFilter<?> sessionRepositoryFilter;

    @MockitoBean
    PatientAccessTokenService patientAccessTokenService;

    @MockitoBean
    PatientAccessAuditService patientAccessAuditService;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(sessionRepositoryFilter)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    @Test
    void bearerAuthenticatedMcpRequestDoesNotFailWhenJdbcSessionCommits() throws Exception {
        when(patientAccessTokenService.authenticate("valid-token")).thenReturn(Optional.of(patientToken()));

        var initializeResult = mvc.perform(post("/api/mcp")
                        .header("Authorization", "Bearer valid-token")
                        .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"test","version":"1"}}}
                                """))
                .andExpect(status().isOk())
                .andReturn();
        assertNoSessionSecurityContext(initializeResult);

        var result = mvc.perform(post("/api/mcp")
                        .header("Authorization", "Bearer valid-token")
                        .header("Mcp-Session-Id", initializeResult.getResponse().getHeader("Mcp-Session-Id"))
                        .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}
                                """))
                .andExpect(request().asyncStarted())
                .andReturn();

        var completedResult = mvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andReturn();
        assertNoSessionSecurityContext(completedResult);
    }

    private static void assertNoSessionSecurityContext(org.springframework.test.web.servlet.MvcResult result) {
        var session = result.getRequest().getSession(false);
        if (session != null) {
            org.assertj.core.api.Assertions.assertThat(session.getAttribute(
                            HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY))
                    .isNull();
        }
    }

    private static PatientAccessToken patientToken() {
        var user = new User("patient@example.com", "hash");
        ReflectionTestUtils.setField(user, "id", 10L);
        user.setEnabled(true);
        user.addRole(RoleName.PATIENT);
        var token = new PatientAccessToken(
                user,
                "hash",
                PatientAccessClientType.MCP_OTHER,
                "LM Studio",
                Instant.parse("2026-07-04T10:00:00Z"),
                Instant.parse("2026-08-03T10:00:00Z"),
                "http://localhost:8080/api/mcp",
                Set.of(PatientAccessTokenScope.PATIENT_PROFILE_READ));
        ReflectionTestUtils.setField(token, "id", 50L);
        return token;
    }
}
