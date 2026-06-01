package com.metabion.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetupTest;
import com.metabion.domain.RoleName;
import com.metabion.domain.User;
import com.metabion.repository.UserRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
abstract class AbstractAuthIT {

    private static final Pattern TOKEN_PATTERN = Pattern.compile("token=([^\\s]+)");
    private static final AtomicInteger REQUEST_COUNTER = new AtomicInteger();

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    static final GreenMail greenMail = new GreenMail(ServerSetupTest.SMTP);

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    UserRepository users;

    @Autowired
    PasswordEncoder passwordEncoder;

    final ObjectMapper objectMapper = new ObjectMapper();
    final HttpClient http = HttpClient.newHttpClient();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.mail.host", () -> "127.0.0.1");
        registry.add("spring.mail.port", () -> greenMail.getSmtp().getPort());
        registry.add("spring.mail.username", () -> "");
        registry.add("spring.mail.password", () -> "");
        registry.add("spring.mail.properties.mail.smtp.auth", () -> "false");
        registry.add("spring.mail.properties.mail.smtp.starttls.enable", () -> "false");
        registry.add("app.base-url", () -> "http://localhost");
        registry.add("metabion.security.trust-forwarded-for", () -> "true");
    }

    @BeforeAll
    static void startMail() {
        if (!greenMail.isRunning()) {
            greenMail.start();
        }
    }

    @AfterAll
    static void stopMail() {
        if (greenMail.isRunning()) {
            greenMail.stop();
        }
    }

    @BeforeEach
    void resetState() {
        try {
            greenMail.purgeEmailFromAllMailboxes();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        jdbc.execute("TRUNCATE TABLE spring_session_attributes, spring_session, "
                + "password_reset_tokens, account_verification_tokens, user_roles, users "
                + "RESTART IDENTITY CASCADE");
    }

    TestClient newClient() {
        return new TestClient();
    }

    ResponseEntity<String> register(TestClient client, String email, String password) {
        return client.post("/api/auth/register", Map.of("email", email, "password", password));
    }

    ResponseEntity<String> verifyToken(TestClient client, String token) {
        return client.get("/api/auth/verify?token=" + token);
    }

    ResponseEntity<String> login(TestClient client, String email, String password) {
        return client.post("/api/auth/login", Map.of("email", email, "password", password));
    }

    ResponseEntity<String> forgotPassword(TestClient client, String email) {
        return client.post("/api/auth/forgot-password", Map.of("email", email));
    }

    ResponseEntity<String> resetPassword(TestClient client, String token, String newPassword) {
        return client.post("/api/auth/reset-password", Map.of("token", token, "newPassword", newPassword));
    }

    ResponseEntity<String> logout(TestClient client) {
        client.addCookie("XSRF-TOKEN", "test-csrf-token");
        return client.postWithHeaders("/api/auth/logout", null, Map.of("X-XSRF-TOKEN", "test-csrf-token"));
    }

    ResponseEntity<String> me(TestClient client) {
        return client.get("/api/auth/me");
    }

    String latestEmailToken() throws Exception {
        var messages = greenMail.getReceivedMessages();
        if (messages.length == 0) {
            throw new AssertionError("No email was received");
        }
        var content = messages[messages.length - 1].getContent().toString();
        var matcher = TOKEN_PATTERN.matcher(content);
        if (!matcher.find()) {
            throw new AssertionError("No token query parameter found in email: " + content);
        }
        return URLDecoder.decode(matcher.group(1), StandardCharsets.UTF_8);
    }

    JsonNode json(ResponseEntity<String> response) throws Exception {
        return objectMapper.readTree(response.getBody());
    }

    User createEnabledUser(String email, String password) {
        var user = new User();
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setEnabled(true);
        user.addRole(RoleName.PATIENT);
        return users.saveAndFlush(user);
    }

    static String sha256Hex(String plaintext) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of().formatHex(digest.digest(plaintext.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    final class TestClient {
        private final Map<String, String> cookies = new LinkedHashMap<>();

        ResponseEntity<String> get(String path) {
            return exchange(path, "GET", null, Map.of());
        }

        ResponseEntity<String> post(String path, Object body) {
            return postWithHeaders(path, body, Map.of());
        }

        ResponseEntity<String> postWithHeaders(String path, Object body, Map<String, String> extraHeaders) {
            return exchange(path, "POST", body, extraHeaders);
        }

        void addCookie(String name, String value) {
            cookies.put(name, value);
        }

        String cookie(String name) {
            return cookies.get(name);
        }

        private ResponseEntity<String> exchange(String path,
                                                String method,
                                                Object body,
                                                Map<String, String> extraHeaders) {
            try {
                var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                        .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                        .header("X-Forwarded-For", nextIp());
                if (!cookies.isEmpty()) {
                    builder.header(HttpHeaders.COOKIE, cookieHeader());
                }
                extraHeaders.forEach(builder::header);

                if (body == null) {
                    builder.method(method, HttpRequest.BodyPublishers.noBody());
                } else {
                    builder.header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                            .method(method, HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)));
                }

                var response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
                response.headers().allValues(HttpHeaders.SET_COOKIE).forEach(this::storeCookie);
                var headers = new HttpHeaders();
                response.headers().map().forEach(headers::put);
                return new ResponseEntity<>(response.body(), headers, HttpStatusCode.valueOf(response.statusCode()));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        private String cookieHeader() {
            return cookies.entrySet().stream()
                    .map(entry -> entry.getKey() + "=" + entry.getValue())
                    .collect(java.util.stream.Collectors.joining("; "));
        }

        private void storeCookie(String setCookie) {
            var first = setCookie.split(";", 2)[0];
            var parts = first.split("=", 2);
            if (parts.length == 2 && !parts[1].isBlank()) {
                cookies.put(parts[0], parts[1]);
            }
        }

        private String nextIp() {
            var value = REQUEST_COUNTER.incrementAndGet();
            return "198.51." + (value / 255) + "." + (value % 255 + 1);
        }
    }
}
