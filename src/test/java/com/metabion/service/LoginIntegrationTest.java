package com.metabion.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metabion.domain.User;
import com.metabion.dto.LoginRequest;
import com.metabion.repository.UserRepository;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.profiles.active=dev",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.datasource.url=jdbc:h2:mem:login_test;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.autoconfigure.exclude=org.springframework.boot.session.jdbc.autoconfigure.JdbcSessionAutoConfiguration"
})
class LoginIntegrationTest {

    @Autowired
    WebApplicationContext context;

    @Autowired
    UserRepository userRepository;

    @Autowired
    PasswordEncoder passwordEncoder;

    @MockitoBean
    FindByIndexNameSessionRepository<Session> sessions;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private User testUser;

    @BeforeEach
    void setUp() {
        Filter[] filters = context.getBeansOfType(Filter.class).values().toArray(new Filter[0]);
        mockMvc = MockMvcBuilders
                .webAppContextSetup(context)
                .addFilters(filters)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();

        // Clean up any existing test user from previous tests
        userRepository.findByEmail("test@example.com").ifPresent(userRepository::delete);

        // Create a test user with a known password
        testUser = new User();
        testUser.setEmail("test@example.com");
        testUser.setPasswordHash(passwordEncoder.encode("test_password"));
        testUser.setEnabled(true);
        testUser.addRole("USER");
        userRepository.save(testUser);
    }

    @Test
    void loginWithValidCredentialsReturns200() throws Exception {
        var request = new LoginRequest("test@example.com", "test_password");

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AUTHENTICATED"))
                .andExpect(jsonPath("$.email").value("test@example.com"))
                .andExpect(jsonPath("$.roles").isArray())
                .andExpect(jsonPath("$.roles[0]").value("USER"));
    }

    @Test
    void loginWithWrongPasswordReturns401() throws Exception {
        var request = new LoginRequest("test@example.com", "wrong_password");

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void loginWithUnknownUserReturns401() throws Exception {
        var request = new LoginRequest("unknown@example.com", "some_password");

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void loginWithDisabledUserReturns401() throws Exception {
        testUser.setEnabled(false);
        userRepository.save(testUser);

        var request = new LoginRequest("test@example.com", "test_password");

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void repeatedFailuresLockUserAfterFiveAttempts() throws Exception {
        var requestWithWrongPassword = new LoginRequest("test@example.com", "wrong");
        var requestWithCorrectPassword = new LoginRequest("test@example.com", "test_password");

        for (int i = 0; i < 5; i++) {
            var remoteAddr = "203.0.113." + i;
            mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(requestWithWrongPassword))
                            .with(req -> {
                                req.setRemoteAddr(remoteAddr);
                                return req;
                            }))
                    .andExpect(status().isUnauthorized());
        }

        // 6th attempt with correct password should still fail (user is now locked)
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(requestWithCorrectPassword))
                        .with(req -> {
                            req.setRemoteAddr("203.0.113.250");
                            return req;
                        }))
                .andExpect(status().isUnauthorized());

        // Verify user is actually locked in database
        var lockedUser = userRepository.findByEmail("test@example.com").orElseThrow();
        assert lockedUser.isLocked() : "User should be locked after 5 failed attempts";
    }
}
