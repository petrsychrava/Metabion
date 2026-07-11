# Task 1 Report: Preserve MCP authentication across servlet redispatches

Status: complete

## Implementation

- Replaced the MCP `NullSecurityContextRepository` with `RequestAttributeSecurityContextRepository`, keeping bearer authentication scoped to one servlet request and available to async/error redispatches without writing it to the HTTP session.
- Declared one shared `SecurityContextRepository` bean in `SecurityConfig` and injected it into both the Spring Security filter chain and `PatientBearerTokenAuthenticationFilter`.
- Explicitly saved only successfully authenticated bearer contexts immediately after setting `SecurityContextHolder`; missing, invalid, and insufficient-scope paths still do not save a context.
- Updated the direct filter unit-test construction for the new repository dependency.
- Added a real MCP async-redispatch regression test.
- Updated the tracked implementation plan's Step 1/2 to reflect Spring AI 2.0.0's actual transport behavior.

## Test-fixture deviation

The original brief expected the MCP `initialize` request itself to start servlet async processing. Investigation of Spring AI 2.0.0's `WebMvcStreamableServerTransportProvider.handlePost` showed that `initialize` calls `initResult().block()` and returns a synchronous JSON `ServerResponse.ok()`.

The regression test therefore initializes synchronously, captures `Mcp-Session-Id`, then sends the smallest valid session-bound streaming request (`tools/list`). That request starts SSE/async processing, and `asyncDispatch` exercises the security redispatch boundary requested by the design. This deviation was approved by the parent agent and recorded in the implementation plan.

## RED evidence

Command:

```text
./gradlew test --tests com.metabion.integration.McpBearerSessionPersistenceIT
```

With the MCP repository using `NullSecurityContextRepository`, the real streaming request started async processing and the test failed during `asyncDispatch`:

```text
McpBearerSessionPersistenceIT > bearerAuthenticatedMcpRequestDoesNotFailWhenJdbcSessionCommits() FAILED
    jakarta.servlet.ServletException at McpBearerSessionPersistenceIT.java:93
        Caused by: org.springframework.security.authorization.AuthorizationDeniedException at McpBearerSessionPersistenceIT.java:93
1 test completed, 1 failed
BUILD FAILED
```

An earlier verbatim attempt from the original brief failed at `request().asyncStarted()` with `Async not started`; that evidence led to the Spring AI transport investigation and corrected test sequence above.

## GREEN evidence

Focused command:

```text
./gradlew test --tests com.metabion.integration.McpBearerSessionPersistenceIT --tests com.metabion.config.PatientBearerTokenAuthenticationFilterTest --tests com.metabion.config.SecurityConfigTest
```

Result: `BUILD SUCCESSFUL` in 7s; 39 focused tests passed with zero failures.

Complete-suite command:

```text
./gradlew test
```

Result: `BUILD SUCCESSFUL` in 1m 11s with zero failing tests. Jacoco report generation also completed.

## Files changed

- `docs/superpowers/plans/2026-07-11-mcp-async-security-context.md`
- `src/main/java/com/metabion/config/McpSecurityContextRepository.java`
- `src/main/java/com/metabion/config/SecurityConfig.java`
- `src/main/java/com/metabion/config/PatientBearerTokenAuthenticationFilter.java`
- `src/test/java/com/metabion/config/PatientBearerTokenAuthenticationFilterTest.java`
- `src/test/java/com/metabion/integration/McpBearerSessionPersistenceIT.java`

The task report is committed with the scoped implementation so the RED/GREEN and review evidence travels with the change.

## Self-review

- `git diff --check` passed with no whitespace errors.
- MCP requests select only `RequestAttributeSecurityContextRepository`; the HTTP-session delegate remains restricted to non-MCP requests.
- The filter and chain receive the same repository bean, so a successful bearer context is saved and reloaded consistently.
- `saveContext` occurs only after successful token resolution; missing, invalid, forbidden/insufficient-scope, and non-MCP paths are unchanged.
- The filter still clears the thread-local context in `finally` after the initial dispatch.
- The regression test uses the real Spring AI session and streaming transport rather than mocking the redispatch boundary.
- No dependency, global dispatcher authorization, session policy, CSRF policy, or unrelated production behavior changed.

## Concerns

No functional concerns. The Gradle runs emitted existing JVM native-access/dynamic-agent and test-schema warnings, but all requested focused and complete tests passed.

## Final review fixes

Implemented the final branch-review findings together:

- MCP repository selection now recognizes a servlet `ERROR` redispatch by `RequestDispatcher.ERROR_REQUEST_URI`, so an original `/api/mcp` request continues to use request-attribute-only context storage even when the redispatch URI is `/error`.
- Added `McpSecurityContextRepositoryTest` coverage proving an MCP error redispatch reloads the request-scoped authentication and does not create an HTTP session.
- The MCP integration test now verifies that both initialization and completed async processing leave any HTTP session without `HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY`.
- Moved successful bearer `saveContext` inside the filter's `try/finally`, with unit coverage proving the thread-local security context is cleared when repository saving throws.

### Review-fix RED evidence

Command:

```text
./gradlew test --tests com.metabion.config.McpSecurityContextRepositoryTest --tests com.metabion.config.PatientBearerTokenAuthenticationFilterTest --tests com.metabion.integration.McpBearerSessionPersistenceIT
```

Result before production changes: `BUILD FAILED`; 8 tests ran and 2 failed as intended:

- `errorRedispatchForMcpRequestUsesRequestAttributesWithoutCreatingSession()` found a `MockHttpSession`, proving `/error` selected the session-backed repository.
- `clearsSecurityContextWhenSavingContextFails()` found `PatientAccessTokenAuthentication` still in `SecurityContextHolder`, proving cleanup did not cover save failures.
- The new integration statelessness assertions passed on the existing request-only async path.

### Review-fix GREEN evidence

Focused command:

```text
./gradlew test --tests com.metabion.integration.McpBearerSessionPersistenceIT --tests com.metabion.config.PatientBearerTokenAuthenticationFilterTest --tests com.metabion.config.SecurityConfigTest --tests com.metabion.config.McpSecurityContextRepositoryTest
```

Result: `BUILD SUCCESSFUL` in 7s with zero failures.

Complete-suite command:

```text
./gradlew test
```

Result: `BUILD SUCCESSFUL` in 1m 36s with zero failing tests; Jacoco report generation completed.

### Review-fix self-review and workspace note

- `git diff --check` passed.
- ERROR classification is used only for an actual `DispatcherType.ERROR`; an arbitrary request attribute cannot reclassify a normal request.
- MCP error redispatches remain request-only, while non-MCP error redispatches retain the existing session-backed behavior.
- No context is saved on missing, invalid, or insufficient-scope bearer paths.
- `.idea/.name` is an unrelated untracked workspace file and was deliberately left untouched and excluded from the commit.

Concerns: none beyond the existing non-failing Gradle/JVM warnings already noted above.
