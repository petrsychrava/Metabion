package com.metabion.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class RateLimitingFilter extends OncePerRequestFilter {

    public static final String RATE_LIMITED_ENDPOINT_ATTRIBUTE = "metabion.rateLimitedEndpoint";

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final int MAX_BUCKETS = 10_000;
    private static final Duration BUCKET_IDLE_TTL = Duration.ofHours(2);

    private final ConcurrentHashMap<Key, BucketEntry> buckets = new ConcurrentHashMap<>();
    private final boolean trustForwardedFor;

    public RateLimitingFilter() {
        this(false);
    }

    @Autowired
    public RateLimitingFilter(@Value("${metabion.security.trust-forwarded-for:false}") boolean trustForwardedFor) {
        this.trustForwardedFor = trustForwardedFor;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        var endpoint = endpointFor(request);
        if (endpoint == null) {
            filterChain.doFilter(request, response);
            return;
        }

        var wrapped = new CachedBodyHttpServletRequest(request);
        var now = System.nanoTime();
        for (var key : keysFor(endpoint, wrapped)) {
            var bucket = bucketFor(key, now);
            if (!bucket.tryConsume(1)) {
                if (isApiRequest(wrapped)) {
                    writeRateLimitedResponse(endpoint, response);
                } else {
                    wrapped.setAttribute(RATE_LIMITED_ENDPOINT_ATTRIBUTE, endpoint);
                    filterChain.doFilter(wrapped, response);
                }
                return;
            }
        }
        filterChain.doFilter(wrapped, response);
    }

    private boolean isApiRequest(HttpServletRequest request) {
        return request.getRequestURI().startsWith("/api/");
    }

    private String endpointFor(HttpServletRequest request) {
        if (!"POST".equalsIgnoreCase(request.getMethod())) {
            return null;
        }
        return switch (normalizedPath(request)) {
            case "/api/auth/login", "/login" -> "login";
            case "/api/auth/register", "/register" -> "register";
            case "/api/auth/forgot-password", "/forgot-password" -> "forgot-password";
            case "/api/auth/reset-password", "/reset-password" -> "reset-password";
            default -> null;
        };
    }

    private String normalizedPath(HttpServletRequest request) {
        var uri = request.getRequestURI();
        if (uri.length() > 1 && uri.endsWith("/")) {
            return uri.substring(0, uri.length() - 1);
        }
        return uri;
    }

    private List<Key> keysFor(String endpoint, CachedBodyHttpServletRequest request) {
        var keys = new ArrayList<Key>();
        keys.add(new Key(endpoint, "ip", clientIp(request)));

        if ("login".equals(endpoint) || "forgot-password".equals(endpoint)) {
            var email = emailFromBody(request);
            if (email != null && !email.isBlank()) {
                keys.add(new Key(endpoint, "email", email.trim().toLowerCase(Locale.ROOT)));
            }
        }
        return keys;
    }

    private Bucket bucketFor(Key key, long now) {
        if (!buckets.containsKey(key) && buckets.size() >= MAX_BUCKETS) {
            pruneBuckets(now);
        }
        return buckets.compute(key, (ignored, existing) -> {
            if (existing == null || isExpired(existing, now)) {
                return new BucketEntry(newBucket(key), now);
            }
            return new BucketEntry(existing.bucket(), now);
        }).bucket();
    }

    private Bucket newBucket(Key key) {
        var bandwidth = switch (key.endpoint() + ":" + key.scope()) {
            case "login:ip" -> Bandwidth.simple(5, Duration.ofMinutes(1));
            case "login:email" -> Bandwidth.simple(10, Duration.ofMinutes(1));
            case "register:ip" -> Bandwidth.simple(10, Duration.ofMinutes(1));
            case "forgot-password:ip" -> Bandwidth.simple(10, Duration.ofMinutes(1));
            case "forgot-password:email" -> Bandwidth.simple(5, Duration.ofHours(1));
            case "reset-password:ip" -> Bandwidth.simple(20, Duration.ofMinutes(1));
            default -> Bandwidth.simple(30, Duration.ofMinutes(1));
        };
        return Bucket.builder().addLimit(bandwidth).build();
    }

    private void pruneBuckets(long now) {
        buckets.entrySet().removeIf(entry -> isExpired(entry.getValue(), now));
        if (buckets.size() < MAX_BUCKETS) {
            return;
        }

        var bucketsToRemove = buckets.size() - MAX_BUCKETS + 1;
        buckets.entrySet().stream()
                .sorted(Comparator.comparingLong(entry -> entry.getValue().lastAccessNanos()))
                .limit(bucketsToRemove)
                .map(Map.Entry::getKey)
                .toList()
                .forEach(buckets::remove);
    }

    private boolean isExpired(BucketEntry bucket, long now) {
        return now - bucket.lastAccessNanos() > BUCKET_IDLE_TTL.toNanos();
    }

    private String emailFromBody(CachedBodyHttpServletRequest request) {
        if (request.body().length == 0) {
            return null;
        }
        if (isFormUrlEncoded(request)) {
            return emailFromFormBody(request);
        }
        try {
            JsonNode root = OBJECT_MAPPER.readTree(request.body());
            var email = root.get("email");
            return email == null || email.isNull() ? null : email.asText();
        } catch (IOException e) {
            return null;
        }
    }

    private boolean isFormUrlEncoded(HttpServletRequest request) {
        var contentType = request.getContentType();
        return contentType != null
                && contentType.toLowerCase(Locale.ROOT).startsWith("application/x-www-form-urlencoded");
    }

    private String emailFromFormBody(CachedBodyHttpServletRequest request) {
        var body = new String(request.body(), request.getCharacterEncoding() == null
                ? StandardCharsets.UTF_8
                : Charset.forName(request.getCharacterEncoding()));
        for (var pair : body.split("&")) {
            var parts = pair.split("=", 2);
            if (parts.length != 2) {
                continue;
            }
            var name = urlDecode(parts[0]);
            if ("email".equals(name)) {
                return urlDecode(parts[1]);
            }
        }
        return null;
    }

    private String urlDecode(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return value;
        }
    }

    private void writeRateLimitedResponse(String endpoint, HttpServletResponse response) throws IOException {
        if ("login".equals(endpoint)) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"invalid_credentials\"}");
            return;
        }

        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("application/json");
        response.getWriter().write("{\"status\":\"ok\"}");
    }

    private String clientIp(HttpServletRequest request) {
        if (trustForwardedFor) {
            var forwarded = request.getHeader("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                return forwarded.split(",", 2)[0].trim();
            }
        }
        return request.getRemoteAddr();
    }

    private record BucketEntry(Bucket bucket, long lastAccessNanos) {
    }

    private record Key(String endpoint, String scope, String value) {
    }
}
