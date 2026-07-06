package com.metabion.service.oauth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metabion.config.OAuthAuthorizationProperties;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.SSLSession;

import static org.assertj.core.api.Assertions.assertThat;

class HttpOAuthClientMetadataFetcherTest {

    @Test
    void rejectsNonHttpsAndLoopbackMetadataUrlsBeforeNetworkFetch() {
        var client = new FakeHttpClient(200, "{}");
        var fetcher = fetcher(client);

        assertThat(fetcher.fetch("http://192.0.2.1/metadata.json")).isEmpty();
        assertThat(fetcher.fetch("https://127.0.0.1/metadata.json")).isEmpty();
        assertThat(fetcher.fetch("https://localhost/metadata.json")).isEmpty();

        assertThat(client.sendCount()).isZero();
    }

    @Test
    void parsesValidMetadataWithStringAndArrayScopes() {
        var client = new FakeHttpClient(200, """
                {
                  "client_name": "Example Client",
                  "redirect_uris": ["https://client.example/callback", "https://client.example/other"],
                  "scope": ["patient:profile:read", "patient:trend:read"]
                }
                """);
        var fetcher = fetcher(client);

        var metadata = fetcher.fetch("https://192.0.2.1/metadata.json");

        assertThat(metadata).isPresent();
        assertThat(metadata.get().clientId()).isEqualTo("https://192.0.2.1/metadata.json");
        assertThat(metadata.get().displayLabel()).isEqualTo("Example Client");
        assertThat(metadata.get().redirectUris()).containsExactly(
                "https://client.example/callback",
                "https://client.example/other");
        assertThat(metadata.get().scopes()).containsExactly("patient:profile:read", "patient:trend:read");
        assertThat(client.lastRequest().headers().firstValue("Accept")).contains("application/json");
        assertThat(client.lastRequest().timeout()).contains(Duration.ofMillis(100));
    }

    @Test
    void parsesSpaceSeparatedScopeString() {
        var client = new FakeHttpClient(200, """
                {
                  "client_name": "Example Client",
                  "redirect_uris": ["https://client.example/callback"],
                  "scope": "patient:profile:read patient:trend:read"
                }
                """);
        var fetcher = fetcher(client);

        var metadata = fetcher.fetch("https://192.0.2.1/metadata.json");

        assertThat(metadata).isPresent();
        assertThat(metadata.get().scopes()).containsExactly("patient:profile:read", "patient:trend:read");
    }

    @Test
    void rejectsOversizedResponse() {
        var client = new FakeHttpClient(200, "x".repeat(33));
        var fetcher = new HttpOAuthClientMetadataFetcher(props(32), client, new ObjectMapper());

        assertThat(fetcher.fetch("https://192.0.2.1/metadata.json")).isEmpty();
    }

    @Test
    void rejectsNon200WithoutFollowingRedirect() {
        var client = new FakeHttpClient(302, "");
        var fetcher = fetcher(client);

        assertThat(fetcher.fetch("https://192.0.2.1/metadata.json")).isEmpty();

        assertThat(client.sendCount()).isOne();
        assertThat(client.lastRequest().uri()).isEqualTo(URI.create("https://192.0.2.1/metadata.json"));
    }

    @Test
    void rejectsInvalidMetadata() {
        var fetcher = fetcher(new FakeHttpClient(200, """
                {
                  "client_name": " ",
                  "redirect_uris": []
                }
                """));

        assertThat(fetcher.fetch("https://192.0.2.1/metadata.json")).isEmpty();
    }

    @Test
    void restoresInterruptFlagWhenRequestIsInterrupted() {
        var fetcher = fetcher(FakeHttpClient.interrupting());

        try {
            assertThat(fetcher.fetch("https://192.0.2.1/metadata.json")).isEmpty();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    private static HttpOAuthClientMetadataFetcher fetcher(FakeHttpClient client) {
        return new HttpOAuthClientMetadataFetcher(props(4096), client, new ObjectMapper());
    }

    private static OAuthAuthorizationProperties props(int maxBytes) {
        return new OAuthAuthorizationProperties(
                "http://localhost:8080",
                "http://localhost:8080/api/mcp",
                Duration.ofMinutes(5),
                Duration.ofHours(1),
                new OAuthAuthorizationProperties.ClientMetadataProperties(true, Duration.ofMillis(100), maxBytes),
                Map.of());
    }

    private static final class FakeHttpClient extends HttpClient {
        private final int statusCode;
        private final byte[] body;
        private final boolean interrupt;
        private final AtomicInteger sendCount = new AtomicInteger();
        private HttpRequest lastRequest;

        private FakeHttpClient(int statusCode, String body) {
            this.statusCode = statusCode;
            this.body = body.getBytes(StandardCharsets.UTF_8);
            this.interrupt = false;
        }

        private FakeHttpClient() {
            this.statusCode = 0;
            this.body = new byte[0];
            this.interrupt = true;
        }

        private static FakeHttpClient interrupting() {
            return new FakeHttpClient();
        }

        private int sendCount() {
            return sendCount.get();
        }

        private HttpRequest lastRequest() {
            return lastRequest;
        }

        @Override
        public Optional<CookieHandler> cookieHandler() {
            return Optional.empty();
        }

        @Override
        public Optional<Duration> connectTimeout() {
            return Optional.empty();
        }

        @Override
        public Redirect followRedirects() {
            return Redirect.NEVER;
        }

        @Override
        public Optional<ProxySelector> proxy() {
            return Optional.empty();
        }

        @Override
        public SSLContext sslContext() {
            try {
                return SSLContext.getDefault();
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
        }

        @Override
        public SSLParameters sslParameters() {
            return new SSLParameters();
        }

        @Override
        public Optional<Authenticator> authenticator() {
            return Optional.empty();
        }

        @Override
        public HttpClient.Version version() {
            return HttpClient.Version.HTTP_1_1;
        }

        @Override
        public Optional<Executor> executor() {
            return Optional.empty();
        }

        @Override
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler)
                throws IOException, InterruptedException {
            sendCount.incrementAndGet();
            lastRequest = request;
            if (interrupt) {
                throw new InterruptedException("interrupted");
            }
            return SimpleResponse.of(request, statusCode, body);
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,
                                                                HttpResponse.BodyHandler<T> responseBodyHandler) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request,
                HttpResponse.BodyHandler<T> responseBodyHandler,
                HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
            throw new UnsupportedOperationException();
        }
    }

    private record SimpleResponse<T>(
            HttpRequest request,
            int statusCode,
            T body
    ) implements HttpResponse<T> {

        @SuppressWarnings("unchecked")
        private static <T> SimpleResponse<T> of(HttpRequest request, int statusCode, byte[] body) {
            return new SimpleResponse<>(request, statusCode, (T) body);
        }

        @Override
        public Optional<HttpResponse<T>> previousResponse() {
            return Optional.empty();
        }

        @Override
        public HttpHeaders headers() {
            return HttpHeaders.of(Map.of(), (name, value) -> true);
        }

        @Override
        public Optional<SSLSession> sslSession() {
            return Optional.empty();
        }

        @Override
        public URI uri() {
            return request.uri();
        }

        @Override
        public HttpClient.Version version() {
            return HttpClient.Version.HTTP_1_1;
        }
    }
}
