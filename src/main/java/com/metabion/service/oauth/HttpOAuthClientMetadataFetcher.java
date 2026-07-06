package com.metabion.service.oauth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metabion.config.OAuthAuthorizationProperties;
import com.metabion.dto.oauth.OAuthClientMetadata;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

@Service
public class HttpOAuthClientMetadataFetcher implements OAuthClientMetadataFetcher {

    private static final int DEFAULT_HTTPS_PORT = 443;
    private static final int MAX_STATUS_LINE_BYTES = 1024;
    private static final int MAX_HEADER_LINE_BYTES = 8192;

    private final OAuthAuthorizationProperties properties;
    private final MetadataDocumentTransport transport;
    private final ObjectMapper objectMapper;

    @Autowired
    public HttpOAuthClientMetadataFetcher(OAuthAuthorizationProperties properties,
                                          ObjectProvider<ObjectMapper> objectMapperProvider) {
        this(properties, new HttpsSocketMetadataDocumentTransport(),
                objectMapperProvider.getIfAvailable(ObjectMapper::new));
    }

    HttpOAuthClientMetadataFetcher(OAuthAuthorizationProperties properties,
                                   MetadataDocumentTransport transport,
                                   ObjectMapper objectMapper) {
        this.properties = properties;
        this.transport = transport;
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<OAuthClientMetadata> fetch(String clientId) {
        try {
            var uri = URI.create(clientId);
            var addresses = allowedMetadataAddresses(uri);
            if (addresses.isEmpty()) {
                return Optional.empty();
            }
            try (var response = transport.fetch(uri, addresses.getFirst(), properties.clientMetadata().timeout())) {
                if (response.statusCode() != 200) {
                    return Optional.empty();
                }
                var body = readBounded(response.body(), properties.clientMetadata().maxBytes());
                if (body.isEmpty()) {
                    return Optional.empty();
                }
                return parse(clientId, body.get());
            }
        } catch (IllegalArgumentException | IOException | UncheckedIOException ex) {
            return Optional.empty();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    private List<InetAddress> allowedMetadataAddresses(URI uri) throws IOException {
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null) {
            return List.of();
        }
        var addresses = InetAddress.getAllByName(stripIpv6Brackets(uri.getHost()));
        if (addresses.length == 0) {
            return List.of();
        }
        for (var address : addresses) {
            if (isUnsafeAddress(address)) {
                return List.of();
            }
        }
        return List.of(addresses);
    }

    static boolean isUnsafeAddress(InetAddress address) {
        if (address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return true;
        }
        var bytes = address.getAddress();
        if (address instanceof Inet6Address && isIpv6UniqueLocal(bytes)) {
            return true;
        }
        var ipv4 = ipv4Bytes(bytes);
        return ipv4 != null && isUnsafeIpv4(ipv4);
    }

    private static boolean isIpv6UniqueLocal(byte[] bytes) {
        return bytes.length == 16 && (bytes[0] & 0xfe) == 0xfc;
    }

    private static byte[] ipv4Bytes(byte[] bytes) {
        if (bytes.length == 4) {
            return bytes;
        }
        if (bytes.length == 16
                && bytes[10] == (byte) 0xff
                && bytes[11] == (byte) 0xff
                && allZero(bytes, 0, 10)) {
            return Arrays.copyOfRange(bytes, 12, 16);
        }
        return null;
    }

    private static boolean allZero(byte[] bytes, int from, int to) {
        for (int index = from; index < to; index++) {
            if (bytes[index] != 0) {
                return false;
            }
        }
        return true;
    }

    private static boolean isUnsafeIpv4(byte[] bytes) {
        int first = bytes[0] & 0xff;
        int second = bytes[1] & 0xff;
        return first == 0
                || first == 10
                || first == 127
                || first == 169 && second == 254
                || first == 172 && second >= 16 && second <= 31
                || first == 192 && second == 168
                || first >= 224 && first <= 239;
    }

    private Optional<byte[]> readBounded(InputStream body, int maxBytes) throws IOException {
        var bytes = body.readNBytes(maxBytes + 1);
        if (bytes.length > maxBytes) {
            return Optional.empty();
        }
        return Optional.of(bytes);
    }

    private Optional<OAuthClientMetadata> parse(String clientId, byte[] body) throws IOException {
        JsonNode json = objectMapper.readTree(body);
        var displayName = clientName(json);
        var redirectUris = requiredTextArray(json.get("redirect_uris"));
        var scopes = scopes(json.get("scope"));
        if (displayName.isEmpty() || redirectUris.isEmpty() || scopes.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new OAuthClientMetadata(clientId, displayName.get(), redirectUris.get(), scopes.get()));
    }

    private Optional<String> clientName(JsonNode json) {
        var value = json.get("client_name");
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            return Optional.empty();
        }
        return Optional.of(value.asText());
    }

    private Optional<List<String>> requiredTextArray(JsonNode node) {
        if (node == null || !node.isArray()) {
            return Optional.empty();
        }
        var values = new ArrayList<String>();
        for (var item : node) {
            if (!item.isTextual() || item.asText().isBlank()) {
                return Optional.empty();
            }
            values.add(item.asText());
        }
        return values.isEmpty() ? Optional.empty() : Optional.of(List.copyOf(values));
    }

    private Optional<List<String>> scopes(JsonNode node) {
        if (node == null) {
            return Optional.of(List.of());
        }
        if (node.isTextual()) {
            if (node.asText().isBlank()) {
                return Optional.empty();
            }
            var values = new ArrayList<String>();
            for (var scope : node.asText().split(" ")) {
                if (!scope.isBlank()) {
                    values.add(scope);
                }
            }
            return Optional.of(List.copyOf(values));
        }
        if (!node.isArray()) {
            return Optional.empty();
        }
        var values = new ArrayList<String>();
        for (var item : node) {
            if (!item.isTextual() || item.asText().isBlank()) {
                return Optional.empty();
            }
            values.add(item.asText());
        }
        return Optional.of(List.copyOf(values));
    }

    private static String stripIpv6Brackets(String host) {
        if (host.startsWith("[") && host.endsWith("]")) {
            return host.substring(1, host.length() - 1);
        }
        return host;
    }

    private static final class HttpsSocketMetadataDocumentTransport implements MetadataDocumentTransport {

        @Override
        public MetadataDocumentResponse fetch(URI uri, InetAddress address, Duration timeout) throws IOException {
            var port = uri.getPort() == -1 ? DEFAULT_HTTPS_PORT : uri.getPort();
            var rawSocket = new Socket();
            SSLSocket sslSocket = null;
            var success = false;
            try {
                rawSocket.connect(new InetSocketAddress(address, port), timeoutMillis(timeout));
                rawSocket.setSoTimeout(timeoutMillis(timeout));
                var host = stripIpv6Brackets(uri.getHost());
                var factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
                sslSocket = (SSLSocket) factory.createSocket(rawSocket, host, port, true);
                configureTls(sslSocket, host);
                sslSocket.startHandshake();
                writeRequest(sslSocket, uri);
                var response = readResponse(sslSocket);
                success = true;
                return response;
            } finally {
                if (!success) {
                    if (sslSocket != null) {
                        sslSocket.close();
                    } else {
                        rawSocket.close();
                    }
                }
            }
        }

        private static void configureTls(SSLSocket socket, String host) {
            SSLParameters parameters = socket.getSSLParameters();
            parameters.setEndpointIdentificationAlgorithm("HTTPS");
            if (!isIpLiteral(host)) {
                try {
                    parameters.setServerNames(List.of(new SNIHostName(host)));
                } catch (IllegalArgumentException ignored) {
                    // Endpoint identification still validates the certificate against the original host.
                }
            }
            socket.setSSLParameters(parameters);
        }

        private static boolean isIpLiteral(String host) {
            return host.indexOf(':') >= 0 || host.matches("\\d+\\.\\d+\\.\\d+\\.\\d+");
        }

        private static void writeRequest(SSLSocket socket, URI uri) throws IOException {
            var writer = new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.US_ASCII);
            writer.write("GET ");
            writer.write(requestTarget(uri));
            writer.write(" HTTP/1.1\r\nHost: ");
            writer.write(hostHeader(uri));
            writer.write("\r\nAccept: application/json\r\nConnection: close\r\n\r\n");
            writer.flush();
        }

        private static MetadataDocumentResponse readResponse(SSLSocket socket) throws IOException {
            var input = new BufferedInputStream(socket.getInputStream());
            var statusLine = readAsciiLine(input, MAX_STATUS_LINE_BYTES);
            if (statusLine == null || !statusLine.startsWith("HTTP/")) {
                throw new IOException("Invalid HTTP response");
            }
            var parts = statusLine.split(" ", 3);
            if (parts.length < 2) {
                throw new IOException("Invalid HTTP status");
            }
            var statusCode = Integer.parseInt(parts[1]);
            String line;
            do {
                line = readAsciiLine(input, MAX_HEADER_LINE_BYTES);
            } while (line != null && !line.isEmpty());
            return new MetadataDocumentResponse(statusCode, input, socket);
        }

        private static String readAsciiLine(InputStream input, int maxBytes) throws IOException {
            var line = new ByteArrayOutputStream();
            while (true) {
                int next = input.read();
                if (next == -1) {
                    return line.size() == 0 ? null : line.toString(StandardCharsets.US_ASCII);
                }
                if (next == '\n') {
                    var value = line.toString(StandardCharsets.US_ASCII);
                    return value.endsWith("\r") ? value.substring(0, value.length() - 1) : value;
                }
                if (line.size() >= maxBytes) {
                    throw new IOException("HTTP line too long");
                }
                line.write(next);
            }
        }

        private static String requestTarget(URI uri) {
            var path = uri.getRawPath();
            var target = path == null || path.isBlank() ? "/" : path;
            if (uri.getRawQuery() != null) {
                target += "?" + uri.getRawQuery();
            }
            return target;
        }

        private static String hostHeader(URI uri) {
            var host = stripIpv6Brackets(uri.getHost());
            if (host.indexOf(':') >= 0) {
                host = "[" + host + "]";
            }
            if (uri.getPort() != -1 && uri.getPort() != DEFAULT_HTTPS_PORT) {
                host += ":" + uri.getPort();
            }
            return host;
        }

        private static int timeoutMillis(Duration timeout) {
            var millis = timeout.toMillis();
            if (millis <= 0) {
                return 1;
            }
            return millis > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) millis;
        }
    }

    @FunctionalInterface
    interface MetadataDocumentTransport {
        MetadataDocumentResponse fetch(URI uri, InetAddress address, Duration timeout)
                throws IOException, InterruptedException;
    }

    record MetadataDocumentResponse(
            int statusCode,
            InputStream body,
            AutoCloseable closeable
    ) implements AutoCloseable {

        @Override
        public void close() throws IOException {
            try {
                closeable.close();
            } catch (IOException ex) {
                throw ex;
            } catch (Exception ex) {
                throw new IOException(ex);
            }
        }
    }
}
