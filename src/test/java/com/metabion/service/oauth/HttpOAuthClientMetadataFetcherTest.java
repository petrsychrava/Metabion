package com.metabion.service.oauth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metabion.config.OAuthAuthorizationProperties;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HttpOAuthClientMetadataFetcherTest {

    @Test
    void rejectsNonHttpsAndLoopbackMetadataUrlsBeforeNetworkFetch() {
        var transport = new FakeTransport(200, "{}");
        var fetcher = fetcher(transport);

        assertThat(fetcher.fetch("http://192.0.2.1/metadata.json")).isEmpty();
        assertThat(fetcher.fetch("https://127.0.0.1/metadata.json")).isEmpty();
        assertThat(fetcher.fetch("https://localhost/metadata.json")).isEmpty();

        assertThat(transport.calls()).isZero();
    }

    @Test
    void rejectsIpv6UniqueLocalBeforeNetworkFetch() {
        var transport = new FakeTransport(200, "{}");
        var fetcher = fetcher(transport);

        assertThat(fetcher.fetch("https://[fc00::1]/metadata.json")).isEmpty();

        assertThat(transport.calls()).isZero();
    }

    @Test
    void rejectsIpv4MappedPrivateLiteralBeforeNetworkFetch() throws Exception {
        var transport = new FakeTransport(200, "{}");
        var fetcher = fetcher(transport);

        assertThat(fetcher.fetch("https://[::ffff:192.168.1.10]/metadata.json")).isEmpty();
        assertThat(HttpOAuthClientMetadataFetcher.isUnsafeAddress(InetAddress.getByAddress(new byte[] {
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, (byte) 0xff, (byte) 0xff, (byte) 192, (byte) 168, 1, 10
        }))).isTrue();

        assertThat(transport.calls()).isZero();
    }

    @Test
    void parsesValidMetadataWithArrayScope() {
        var transport = new FakeTransport(200, """
                {
                  "client_name": "Example Client",
                  "redirect_uris": ["https://client.example/callback", "https://client.example/other"],
                  "scope": ["patient:profile:read", "patient:trend:read"]
                }
                """);
        var fetcher = fetcher(transport);

        var metadata = fetcher.fetch("https://192.0.2.1/metadata.json");

        assertThat(metadata).isPresent();
        assertThat(metadata.get().clientId()).isEqualTo("https://192.0.2.1/metadata.json");
        assertThat(metadata.get().displayLabel()).isEqualTo("Example Client");
        assertThat(metadata.get().redirectUris()).containsExactly(
                "https://client.example/callback",
                "https://client.example/other");
        assertThat(metadata.get().scopes()).containsExactly("patient:profile:read", "patient:trend:read");
        assertThat(transport.calls()).isOne();
        assertThat(transport.lastUri()).isEqualTo(URI.create("https://192.0.2.1/metadata.json"));
        assertThat(transport.lastAddress().getHostAddress()).isEqualTo("192.0.2.1");
        assertThat(transport.lastTimeout()).isEqualTo(Duration.ofMillis(100));
    }

    @Test
    void parsesSpaceSeparatedScopeString() {
        var transport = new FakeTransport(200, """
                {
                  "client_name": "Example Client",
                  "redirect_uris": ["https://client.example/callback"],
                  "scope": "patient:profile:read patient:trend:read"
                }
                """);
        var fetcher = fetcher(transport);

        var metadata = fetcher.fetch("https://192.0.2.1/metadata.json");

        assertThat(metadata).isPresent();
        assertThat(metadata.get().scopes()).containsExactly("patient:profile:read", "patient:trend:read");
    }

    @Test
    void rejectsPresentBlankScopeString() {
        var fetcher = fetcher(new FakeTransport(200, """
                {
                  "client_name": "Example Client",
                  "redirect_uris": ["https://client.example/callback"],
                  "scope": "   "
                }
                """));

        assertThat(fetcher.fetch("https://192.0.2.1/metadata.json")).isEmpty();
    }

    @Test
    void decodesChunkedResponseBody() {
        var fetcher = fetcher(new RawResponseTransport("""
                HTTP/1.1 200 OK\r
                Transfer-Encoding: chunked\r
                \r
                %s\r
                %s\r
                0\r
                \r
                """.formatted(Integer.toHexString(metadataJson().getBytes(StandardCharsets.UTF_8).length), metadataJson())));

        var metadata = fetcher.fetch("https://192.0.2.1/metadata.json");

        assertThat(metadata).isPresent();
        assertThat(metadata.get().displayLabel()).isEqualTo("Example Client");
    }

    @Test
    void contentLengthResponseReadsOnlyDeclaredBody() {
        var body = metadataJson();
        var fetcher = fetcher(new RawResponseTransport("""
                HTTP/1.1 200 OK\r
                Content-Length: %s\r
                \r
                %signored-by-content-length
                """.formatted(body.getBytes(StandardCharsets.UTF_8).length, body)));

        var metadata = fetcher.fetch("https://192.0.2.1/metadata.json");

        assertThat(metadata).isPresent();
        assertThat(metadata.get().displayLabel()).isEqualTo("Example Client");
    }

    @Test
    void rejectsTooManyAggregateHeaders() {
        var response = new StringBuilder("HTTP/1.1 200 OK\r\n");
        for (int index = 0; index < 100; index++) {
            response.append("X-Test-").append(index).append(": value\r\n");
        }
        response.append("\r\n").append(metadataJson());

        assertThatThrownBy(() -> HttpOAuthClientMetadataFetcher.readResponse(
                new ByteArrayInputStream(response.toString().getBytes(StandardCharsets.US_ASCII)),
                InputStream.nullInputStream()))
                .isInstanceOf(IOException.class);
    }

    @Test
    void rejectsOversizedResponseViaBoundedRead() {
        var body = new CountingInputStream("x".repeat(33));
        var transport = new FakeTransport(200, body);
        var fetcher = new HttpOAuthClientMetadataFetcher(props(32), transport, new ObjectMapper());

        assertThat(fetcher.fetch("https://192.0.2.1/metadata.json")).isEmpty();
        assertThat(body.bytesRead()).isEqualTo(33);
    }

    @Test
    void rejectsNon200WithoutReadingBody() {
        var body = new CountingInputStream("""
                {
                  "client_name": "Example Client",
                  "redirect_uris": ["https://client.example/callback"]
                }
                """);
        var transport = new FakeTransport(302, body);
        var fetcher = fetcher(transport);

        assertThat(fetcher.fetch("https://192.0.2.1/metadata.json")).isEmpty();

        assertThat(transport.calls()).isOne();
        assertThat(body.bytesRead()).isZero();
    }

    @Test
    void rejectsInvalidMetadata() {
        var fetcher = fetcher(new FakeTransport(200, """
                {
                  "client_name": " ",
                  "redirect_uris": []
                }
                """));

        assertThat(fetcher.fetch("https://192.0.2.1/metadata.json")).isEmpty();
    }

    @Test
    void restoresInterruptFlagWhenRequestIsInterrupted() {
        var fetcher = fetcher(FakeTransport.interrupting());

        try {
            assertThat(fetcher.fetch("https://192.0.2.1/metadata.json")).isEmpty();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    private static HttpOAuthClientMetadataFetcher fetcher(
            HttpOAuthClientMetadataFetcher.MetadataDocumentTransport transport) {
        return new HttpOAuthClientMetadataFetcher(props(4096), transport, new ObjectMapper());
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

    private static String metadataJson() {
        return """
                {
                  "client_name": "Example Client",
                  "redirect_uris": ["https://client.example/callback"],
                  "scope": ["patient:profile:read"]
                }
                """;
    }

    private static final class RawResponseTransport implements HttpOAuthClientMetadataFetcher.MetadataDocumentTransport {
        private final byte[] response;

        private RawResponseTransport(String response) {
            this.response = response.getBytes(StandardCharsets.US_ASCII);
        }

        @Override
        public HttpOAuthClientMetadataFetcher.MetadataDocumentResponse fetch(
                URI uri,
                InetAddress address,
                Duration timeout) throws IOException {
            var input = new ByteArrayInputStream(response);
            return HttpOAuthClientMetadataFetcher.readResponse(input, input);
        }
    }

    private static final class FakeTransport implements HttpOAuthClientMetadataFetcher.MetadataDocumentTransport {
        private final int statusCode;
        private final InputStream body;
        private final boolean interrupt;
        private final AtomicInteger calls = new AtomicInteger();
        private URI lastUri;
        private InetAddress lastAddress;
        private Duration lastTimeout;

        private FakeTransport(int statusCode, String body) {
            this(statusCode, new CountingInputStream(body));
        }

        private FakeTransport(int statusCode, InputStream body) {
            this.statusCode = statusCode;
            this.body = body;
            this.interrupt = false;
        }

        private FakeTransport() {
            this.statusCode = 0;
            this.body = InputStream.nullInputStream();
            this.interrupt = true;
        }

        private static FakeTransport interrupting() {
            return new FakeTransport();
        }

        @Override
        public HttpOAuthClientMetadataFetcher.MetadataDocumentResponse fetch(
                URI uri,
                InetAddress address,
                Duration timeout) throws IOException, InterruptedException {
            calls.incrementAndGet();
            lastUri = uri;
            lastAddress = address;
            lastTimeout = timeout;
            if (interrupt) {
                throw new InterruptedException("interrupted");
            }
            return new HttpOAuthClientMetadataFetcher.MetadataDocumentResponse(statusCode, body, body);
        }

        private int calls() {
            return calls.get();
        }

        private URI lastUri() {
            return lastUri;
        }

        private InetAddress lastAddress() {
            return lastAddress;
        }

        private Duration lastTimeout() {
            return lastTimeout;
        }
    }

    private static final class CountingInputStream extends InputStream {
        private final byte[] bytes;
        private int index;
        private int bytesRead;

        private CountingInputStream(String body) {
            bytes = body.getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public int read() {
            if (index >= bytes.length) {
                return -1;
            }
            bytesRead++;
            return bytes[index++] & 0xff;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) {
            if (index >= bytes.length) {
                return -1;
            }
            int count = Math.min(length, bytes.length - index);
            System.arraycopy(bytes, index, buffer, offset, count);
            index += count;
            bytesRead += count;
            return count;
        }

        private int bytesRead() {
            return bytesRead;
        }
    }
}
