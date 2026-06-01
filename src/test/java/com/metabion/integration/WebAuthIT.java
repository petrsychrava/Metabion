package com.metabion.integration;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class WebAuthIT extends AbstractAuthIT {

    private static final Pattern CSRF_TOKEN_VALUE =
            Pattern.compile("name=\"_csrf\"[^>]*value=\"([^\"]+)\"");

    @Test
    void browser_register_form_submission_succeeds() throws Exception {
        var cookies = new LinkedHashMap<String, String>();
        var get = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/register"))
                .header(HttpHeaders.ACCEPT, MediaType.TEXT_HTML_VALUE)
                .GET()
                .build(), HttpResponse.BodyHandlers.ofString());
        get.headers().allValues(HttpHeaders.SET_COOKIE).forEach(cookie -> storeCookie(cookies, cookie));

        assertThat(get.statusCode()).isEqualTo(200);
        var matcher = CSRF_TOKEN_VALUE.matcher(get.body());
        assertThat(matcher.find()).isTrue();

        var form = "_csrf=" + encode(matcher.group(1))
                + "&email=" + encode("browser-it@example.com")
                + "&password=" + encode("SecurePass123");

        var post = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/register"))
                .header(HttpHeaders.ACCEPT, MediaType.TEXT_HTML_VALUE)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_FORM_URLENCODED_VALUE)
                .header(HttpHeaders.COOKIE, cookieHeader(cookies))
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build(), HttpResponse.BodyHandlers.ofString());

        assertThat(post.statusCode()).isEqualTo(200);
        assertThat(post.body()).contains("Check your email");
        assertThat(users.findByEmail("browser-it@example.com")).isPresent();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String cookieHeader(LinkedHashMap<String, String> cookies) {
        return cookies.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(java.util.stream.Collectors.joining("; "));
    }

    private static void storeCookie(LinkedHashMap<String, String> cookies, String setCookie) {
        var first = setCookie.split(";", 2)[0];
        var parts = first.split("=", 2);
        if (parts.length == 2 && !parts[1].isBlank()) {
            cookies.put(parts[0], parts[1]);
        }
    }
}
