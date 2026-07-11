# MCP Async Security Context Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Preserve bearer authentication across MCP servlet redispatches without persisting it to the HTTP session.

**Architecture:** Use one shared `McpSecurityContextRepository` in both Spring Security and the custom bearer filter. MCP contexts are saved to request attributes for the lifetime of one servlet request; browser contexts retain the existing request-attribute and HTTP-session delegates.

**Tech Stack:** Java 25, Spring Boot 4.0.6, Spring Security 7, Spring MVC MockMvc, JUnit 5, Gradle

## Global Constraints

- MCP bearer authentication must remain stateless across separate HTTP requests.
- No dispatcher type is globally permitted.
- Invalid-token and insufficient-scope responses and audits remain unchanged.
- No new dependency is introduced.

---

### Task 1: Preserve MCP authentication across servlet redispatches

**Files:**
- Modify: `src/test/java/com/metabion/integration/McpBearerSessionPersistenceIT.java`
- Modify: `src/main/java/com/metabion/config/McpSecurityContextRepository.java`
- Modify: `src/main/java/com/metabion/config/SecurityConfig.java`
- Modify: `src/main/java/com/metabion/config/PatientBearerTokenAuthenticationFilter.java`

**Interfaces:**
- Consumes: Spring Security's `SecurityContextRepository.saveContext(SecurityContext, HttpServletRequest, HttpServletResponse)`.
- Produces: a shared `SecurityContextRepository` bean used by the filter chain and bearer filter; request-scoped MCP authentication available on async and error redispatches.

- [ ] **Step 1: Write the failing async-redispatch regression test**

In `McpBearerSessionPersistenceIT`, statically import `asyncDispatch` and `request`. Spring AI 2.0.0 handles `initialize` synchronously, so first initialize the MCP session and capture its `Mcp-Session-Id`. Then send the smallest session-bound streaming request, retain that `MvcResult`, assert that asynchronous processing starts, and dispatch it:

```java
var initializeResult = mvc.perform(post("/api/mcp")
                .header("Authorization", "Bearer valid-token")
                .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"test","version":"1"}}}
                        """))
        .andExpect(status().isOk())
        .andReturn();

var result = mvc.perform(post("/api/mcp")
                .header("Authorization", "Bearer valid-token")
                .header("Mcp-Session-Id", initializeResult.getResponse().getHeader("Mcp-Session-Id"))
                .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}
                        """))
        .andExpect(request().asyncStarted())
        .andReturn();

mvc.perform(asyncDispatch(result))
        .andExpect(status().isOk());
```

- [ ] **Step 2: Run the regression test to verify RED**

Run:

```bash
./gradlew test --tests com.metabion.integration.McpBearerSessionPersistenceIT
```

Expected: FAIL during the streaming request's `asyncDispatch` with `AuthorizationDeniedException: Access Denied`, demonstrating that its initial authenticated dispatch loses context on redispatch.

- [ ] **Step 3: Make the MCP repository request-scoped**

In `McpSecurityContextRepository`, replace:

```java
private final SecurityContextRepository mcpContexts = new NullSecurityContextRepository();
```

with:

```java
private final SecurityContextRepository mcpContexts = new RequestAttributeSecurityContextRepository();
```

Remove the unused `NullSecurityContextRepository` import.

- [ ] **Step 4: Share the repository through dependency injection**

In `SecurityConfig`, add:

```java
import org.springframework.security.web.context.SecurityContextRepository;
```

Declare:

```java
@Bean
SecurityContextRepository securityContextRepository() {
    return new McpSecurityContextRepository();
}
```

Add `SecurityContextRepository securityContextRepository` to `filterChain` and configure:

```java
.securityContext(context -> context
        .securityContextRepository(securityContextRepository))
```

instead of constructing a separate repository inline.

- [ ] **Step 5: Explicitly save successful bearer authentication**

Add `SecurityContextRepository securityContextRepository` to `PatientBearerTokenAuthenticationFilter` as a final field and constructor argument. Immediately after `SecurityContextHolder.setContext(context)`, add:

```java
securityContextRepository.saveContext(context, request, response);
```

Do not save contexts on missing, invalid, or insufficient-scope bearer tokens.

- [ ] **Step 6: Run focused tests to verify GREEN**

Run:

```bash
./gradlew test --tests com.metabion.integration.McpBearerSessionPersistenceIT --tests com.metabion.config.PatientBearerTokenAuthenticationFilterTest --tests com.metabion.config.SecurityConfigTest
```

Expected: BUILD SUCCESSFUL with the async redispatch authorized and existing bearer error behavior unchanged.

- [ ] **Step 7: Run the complete test suite**

Run:

```bash
./gradlew test
```

Expected: BUILD SUCCESSFUL with zero failing tests.

- [ ] **Step 8: Review the diff and commit**

Confirm that only the design, plan, four scoped Java files, and related test changed. Then run:

```bash
git add docs/superpowers/specs/2026-07-11-mcp-async-security-context-design.md \
  docs/superpowers/plans/2026-07-11-mcp-async-security-context.md \
  src/main/java/com/metabion/config/McpSecurityContextRepository.java \
  src/main/java/com/metabion/config/SecurityConfig.java \
  src/main/java/com/metabion/config/PatientBearerTokenAuthenticationFilter.java \
  src/test/java/com/metabion/integration/McpBearerSessionPersistenceIT.java
git commit -m "Preserve MCP authentication across async dispatches"
```
