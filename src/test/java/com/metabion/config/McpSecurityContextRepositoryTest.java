package com.metabion.config;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.RequestDispatcher;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;

class McpSecurityContextRepositoryTest {

    @Test
    void errorRedispatchForMcpRequestUsesRequestAttributesWithoutCreatingSession() {
        var repository = new McpSecurityContextRepository();
        var request = new MockHttpServletRequest("GET", "/error");
        request.setRequestURI("/error");
        request.setDispatcherType(DispatcherType.ERROR);
        request.setAttribute(RequestDispatcher.ERROR_REQUEST_URI, "/api/mcp");
        var response = new MockHttpServletResponse();
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new TestingAuthenticationToken("patient", "token"));

        repository.saveContext(context, request, response);

        assertThat(repository.containsContext(request)).isTrue();
        assertThat(repository.loadDeferredContext(request).get().getAuthentication())
                .isSameAs(context.getAuthentication());
        assertThat(request.getSession(false)).isNull();
    }
}
