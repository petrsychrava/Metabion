package com.metabion.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class McpLocalhostFilterTest {

    @Test
    void localhostMcpRequestIsAllowed() throws Exception {
        var called = new AtomicBoolean();
        var response = doFilter("/api/mcp", "127.0.0.1", true, true, called);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(called).isTrue();
    }

    @Test
    void ipv6LocalhostMcpRequestIsAllowed() throws Exception {
        var called = new AtomicBoolean();
        var response = doFilter("/api/mcp", "::1", true, true, called);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(called).isTrue();
    }

    @Test
    void remoteMcpRequestIsForbiddenWhenLocalhostOnly() throws Exception {
        var called = new AtomicBoolean();
        var response = doFilter("/api/mcp", "203.0.113.10", true, true, called);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).isEqualTo("{\"error\":\"localhost_required\"}");
        assertThat(called).isFalse();
    }

    @Test
    void remoteNonMcpRequestFallsThrough() throws Exception {
        var called = new AtomicBoolean();
        var response = doFilter("/api/whoami", "203.0.113.10", true, true, called);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(called).isTrue();
    }

    private static MockHttpServletResponse doFilter(String path,
                                                    String remoteAddr,
                                                    boolean enabled,
                                                    boolean localhostOnly,
                                                    AtomicBoolean called) throws Exception {
        var filter = new McpLocalhostFilter(enabled, localhostOnly);
        var request = new MockHttpServletRequest("POST", path);
        request.setRequestURI(path);
        request.setRemoteAddr(remoteAddr);
        var response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, resp) -> called.set(true));

        return response;
    }
}
