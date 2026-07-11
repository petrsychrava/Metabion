# MCP Async Security Context Design

## Problem

Bearer-authenticated MCP requests succeed on their initial servlet dispatch, but Spring Security authorizes later asynchronous and error dispatches without an authenticated principal. The MCP security-context repository currently discards contexts with `NullSecurityContextRepository`, while the custom bearer filter authenticates only the initial dispatch. If the streaming response is already committed, the resulting denial produces repeated container errors.

## Design

MCP authentication remains stateless across separate HTTP requests. Within one servlet request, including asynchronous and error redispatches, the authenticated `SecurityContext` is stored in a `RequestAttributeSecurityContextRepository`.

The same MCP request-scoped repository is shared by `SecurityConfig` and `PatientBearerTokenAuthenticationFilter`. After successful bearer validation, the filter sets the holder context and explicitly saves it to the repository before continuing the chain. Non-MCP browser authentication continues to use the existing request-attribute plus HTTP-session repository.

No dispatcher type is globally permitted and no MCP bearer context is written to the HTTP session.

## Error Handling

Invalid or insufficient-scope bearer tokens retain the existing JSON response, status code, challenge header, and audit behavior. Only successfully authenticated contexts are saved.

## Testing

A regression test exercises an authenticated MCP request followed by an asynchronous redispatch and verifies that security does not deny the redispatch. Existing integration coverage continues to verify that an MCP bearer request does not fail when the JDBC session commits. Focused security tests and the complete Gradle test suite provide final verification.
