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

        int loginAttempts() {
            return loginAttempts.get();
        }
    }
}
