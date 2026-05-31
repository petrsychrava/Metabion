package com.metabion.config;

import com.metabion.controller.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RateLimitingFilterTest {

    private AuthEndpointController controller;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        controller = new AuthEndpointController();
        mvc = MockMvcBuilders
                .standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .addFilters(new RateLimitingFilter())
                .build();
    }

    @Test
    void sixthLoginFromSameIpReturnsInvalidCredentialsBody() throws Exception {
        var body = "{\"email\":\"user@example.com\",\"password\":\"wrong\"}";

        for (int i = 0; i < 5; i++) {
            mvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body)
                            .with(req -> {
                                req.setRemoteAddr("203.0.113.10");
                                return req;
                            }))
                    .andExpect(status().isUnauthorized())
                    .andExpect(content().json("{\"error\":\"invalid_credentials\"}"));
        }

        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .with(req -> {
                            req.setRemoteAddr("203.0.113.10");
                            return req;
                        }))
                .andExpect(status().isUnauthorized())
                .andExpect(content().json("{\"error\":\"invalid_credentials\"}"));
    }

    @Test
    void untrustedForwardedForDoesNotBypassIpLimit() throws Exception {
        var body = "{\"email\":\"unique@example.com\",\"password\":\"wrong\"}";

        for (int i = 0; i < 5; i++) {
            mvc.perform(post("/api/auth/login")
                            .header("X-Forwarded-For", "203.0.113." + i)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body)
                            .with(req -> {
                                req.setRemoteAddr("198.51.100.10");
                                return req;
                            }))
                    .andExpect(status().isUnauthorized())
                    .andExpect(content().json("{\"error\":\"invalid_credentials\"}"));
        }

        mvc.perform(post("/api/auth/login")
                        .header("X-Forwarded-For", "203.0.113.250")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .with(req -> {
                            req.setRemoteAddr("198.51.100.10");
                            return req;
                        }))
                .andExpect(status().isUnauthorized())
                .andExpect(content().json("{\"error\":\"invalid_credentials\"}"));

        org.assertj.core.api.Assertions.assertThat(controller.loginAttempts()).isEqualTo(5);
    }

    @Test
    void eleventhLoginForSameEmailReturnsInvalidCredentialsBody() throws Exception {
        for (int i = 0; i < 10; i++) {
            var remoteAddr = "203.0.113." + i;
            mvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"victim@example.com\",\"password\":\"wrong\"}")
                            .with(req -> {
                                req.setRemoteAddr(remoteAddr);
                                return req;
                            }))
                    .andExpect(status().isUnauthorized())
                    .andExpect(content().json("{\"error\":\"invalid_credentials\"}"));
        }

        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\" victim@example.com \",\"password\":\"wrong\"}")
                        .with(req -> {
                            req.setRemoteAddr("203.0.113.250");
                            return req;
                        }))
                .andExpect(status().isUnauthorized())
                .andExpect(content().json("{\"error\":\"invalid_credentials\"}"));
    }

    @Test
    void mvcLoginIsRateLimitedByIp() throws Exception {
        for (int i = 0; i < 5; i++) {
            mvc.perform(post("/login")
                            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                            .content("email=user%40example.com&password=wrong")
                            .with(req -> {
                                req.setRemoteAddr("203.0.113.20");
                                return req;
                            }))
                    .andExpect(status().isUnauthorized())
                    .andExpect(content().json("{\"error\":\"invalid_credentials\"}"));
        }

        mvc.perform(post("/login")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .content("email=user%40example.com&password=wrong")
                        .with(req -> {
                            req.setRemoteAddr("203.0.113.20");
                            return req;
                        }))
                .andExpect(status().isUnauthorized())
                .andExpect(content().json("{\"status\":\"rate_limited:login\"}"));

        org.assertj.core.api.Assertions.assertThat(controller.mvcLoginAttempts()).isEqualTo(5);
    }

    @Test
    void mvcLoginIsRateLimitedByEmailFromFormBody() throws Exception {
        for (int i = 0; i < 10; i++) {
            var remoteAddr = "203.0.114." + i;
            mvc.perform(post("/login")
                            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                            .content("email=victim%40example.com&password=wrong")
                            .with(req -> {
                                req.setRemoteAddr(remoteAddr);
                                return req;
                            }))
                    .andExpect(status().isUnauthorized())
                    .andExpect(content().json("{\"error\":\"invalid_credentials\"}"));
        }

        mvc.perform(post("/login")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .content("email=+VICTIM%40example.com+&password=wrong")
                        .with(req -> {
                            req.setRemoteAddr("203.0.114.250");
                            return req;
                        }))
                .andExpect(status().isUnauthorized())
                .andExpect(content().json("{\"status\":\"rate_limited:login\"}"));

        org.assertj.core.api.Assertions.assertThat(controller.mvcLoginAttempts()).isEqualTo(10);
    }

    @Test
    void mvcRegisterIsRateLimitedByIp() throws Exception {
        for (int i = 0; i < 10; i++) {
            mvc.perform(post("/register")
                            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                            .content("email=new%40example.com&password=SecurePass123")
                            .with(req -> {
                                req.setRemoteAddr("198.51.100.20");
                                return req;
                            }))
                    .andExpect(status().isOk());
        }

        mvc.perform(post("/register")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .content("email=new%40example.com&password=SecurePass123")
                        .with(req -> {
                            req.setRemoteAddr("198.51.100.20");
                            return req;
                        }))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"status\":\"rate_limited:register\"}"));

        org.assertj.core.api.Assertions.assertThat(controller.mvcRegisterAttempts()).isEqualTo(10);
    }

    @Test
    void mvcForgotPasswordIsRateLimitedByEmailFromFormBody() throws Exception {
        for (int i = 0; i < 5; i++) {
            var remoteAddr = "198.51.101." + i;
            mvc.perform(post("/forgot-password")
                            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                            .content("email=reset%40example.com")
                            .with(req -> {
                                req.setRemoteAddr(remoteAddr);
                                return req;
                            }))
                    .andExpect(status().isOk())
                    .andExpect(content().json("{\"status\":\"ok\"}"));
        }

        mvc.perform(post("/forgot-password")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .content("email=RESET%40example.com")
                        .with(req -> {
                            req.setRemoteAddr("198.51.101.250");
                            return req;
                        }))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"status\":\"rate_limited:forgot-password\"}"));

        org.assertj.core.api.Assertions.assertThat(controller.mvcForgotPasswordAttempts()).isEqualTo(5);
    }

    @Test
    void mvcResetPasswordIsRateLimitedByIp() throws Exception {
        for (int i = 0; i < 20; i++) {
            mvc.perform(post("/reset-password")
                            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                            .content("token=abc&newPassword=SecurePass123")
                            .with(req -> {
                                req.setRemoteAddr("198.51.102.20");
                                return req;
                            }))
                    .andExpect(status().isOk())
                    .andExpect(content().json("{\"status\":\"password_reset\"}"));
        }

        mvc.perform(post("/reset-password")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .content("token=abc&newPassword=SecurePass123")
                        .with(req -> {
                            req.setRemoteAddr("198.51.102.20");
                            return req;
                        }))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"status\":\"rate_limited:reset-password\"}"));

        org.assertj.core.api.Assertions.assertThat(controller.mvcResetPasswordAttempts()).isEqualTo(20);
    }

    @Test
    void sixthForgotPasswordForSameEmailReturnsOkBody() throws Exception {
        for (int i = 0; i < 5; i++) {
            var remoteAddr = "198.51.100." + i;
            mvc.perform(post("/api/auth/forgot-password")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"reset@example.com\"}")
                            .with(req -> {
                                req.setRemoteAddr(remoteAddr);
                                return req;
                            }))
                    .andExpect(status().isOk())
                    .andExpect(content().json("{\"status\":\"ok\"}"));
        }

        mvc.perform(post("/api/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"RESET@example.com\"}")
                        .with(req -> {
                            req.setRemoteAddr("198.51.100.250");
                            return req;
                        }))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"status\":\"ok\"}"));
    }

    @Test
    void bucketStorageIsBounded() throws Exception {
        var filter = new RateLimitingFilter();
        var mvcWithFilter = MockMvcBuilders
                .standaloneSetup(new AuthEndpointController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .addFilters(filter)
                .build();

        for (int i = 0; i < 10_050; i++) {
            var remoteAddr = "198.51." + (i / 256) + "." + (i % 256);
            mvcWithFilter.perform(post("/api/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}")
                            .with(req -> {
                                req.setRemoteAddr(remoteAddr);
                                return req;
                            }))
                    .andExpect(status().isNotFound());
        }

        var buckets = RateLimitingFilter.class.getDeclaredField("buckets");
        buckets.setAccessible(true);
        var bucketMap = (Map<?, ?>) buckets.get(filter);
        org.assertj.core.api.Assertions.assertThat(bucketMap).hasSizeLessThanOrEqualTo(10_000);
    }

    @RestController
    private static class AuthEndpointController {

        private final AtomicInteger loginAttempts = new AtomicInteger();

        @PostMapping("/api/auth/login")
        ResponseEntity<Map<String, String>> login(@RequestBody Map<String, String> request) {
            loginAttempts.incrementAndGet();
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "invalid_credentials"));
        }

        @PostMapping("/api/auth/forgot-password")
        Map<String, String> forgotPassword(@RequestBody Map<String, String> request) {
            return Map.of("status", "ok");
        }

        @PostMapping("/login")
        ResponseEntity<Map<String, String>> mvcLogin() {
            var rateLimited = rateLimitedEndpoint();
            if (rateLimited != null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(Map.of("status", "rate_limited:" + rateLimited));
            }
            mvcLoginAttempts.incrementAndGet();
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "invalid_credentials"));
        }

        @PostMapping("/register")
        ResponseEntity<Map<String, String>> mvcRegister() {
            var rateLimited = rateLimitedEndpoint();
            if (rateLimited != null) {
                return ResponseEntity.ok(Map.of("status", "rate_limited:" + rateLimited));
            }
            mvcRegisterAttempts.incrementAndGet();
            return ResponseEntity.ok(Map.of("status", "ok"));
        }

        @PostMapping("/forgot-password")
        ResponseEntity<Map<String, String>> mvcForgotPassword() {
            var rateLimited = rateLimitedEndpoint();
            if (rateLimited != null) {
                return ResponseEntity.ok(Map.of("status", "rate_limited:" + rateLimited));
            }
            mvcForgotPasswordAttempts.incrementAndGet();
            return ResponseEntity.ok(Map.of("status", "ok"));
        }

        @PostMapping("/reset-password")
        ResponseEntity<Map<String, String>> mvcResetPassword() {
            var rateLimited = rateLimitedEndpoint();
            if (rateLimited != null) {
                return ResponseEntity.ok(Map.of("status", "rate_limited:" + rateLimited));
            }
            mvcResetPasswordAttempts.incrementAndGet();
            return ResponseEntity.ok(Map.of("status", "password_reset"));
        }

        int loginAttempts() {
            return loginAttempts.get();
        }

        private final AtomicInteger mvcLoginAttempts = new AtomicInteger();
        private final AtomicInteger mvcRegisterAttempts = new AtomicInteger();
        private final AtomicInteger mvcForgotPasswordAttempts = new AtomicInteger();
        private final AtomicInteger mvcResetPasswordAttempts = new AtomicInteger();

        int mvcLoginAttempts() {
            return mvcLoginAttempts.get();
        }

        int mvcRegisterAttempts() {
            return mvcRegisterAttempts.get();
        }

        int mvcForgotPasswordAttempts() {
            return mvcForgotPasswordAttempts.get();
        }

        int mvcResetPasswordAttempts() {
            return mvcResetPasswordAttempts.get();
        }

        private String rateLimitedEndpoint() {
            var attributes = org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
            if (attributes instanceof org.springframework.web.context.request.ServletRequestAttributes servlet) {
                var value = servlet.getRequest()
                        .getAttribute(RateLimitingFilter.RATE_LIMITED_ENDPOINT_ATTRIBUTE);
                return value == null ? null : value.toString();
            }
            return null;
        }
    }
}
