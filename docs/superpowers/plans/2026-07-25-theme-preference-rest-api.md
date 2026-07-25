# Theme Preference REST API Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add authenticated GET and CSRF-protected PUT endpoints for the existing user theme preference.

**Architecture:** `AccountController` will follow the existing language-preference REST shape and delegate to `UserPreferenceService`, which already owns current-user lookup and persistence. A small request record will use Jackson enum binding and Jakarta validation to protect the controller boundary; no entity, database, or security-configuration changes are required.

**Tech Stack:** Java 25, Spring Boot 4.0.6, Spring MVC, Spring Security, Jakarta Validation, JUnit 5, MockMvc, Mockito.

## Global Constraints

- Preserve the existing session-based API authentication and CSRF policy.
- Reuse `UserPreferenceService`; do not add parallel persistence or authorization rules in the controller.
- Do not modify Flyway migrations: `users.theme_preference` already exists, is non-null, and defaults to `SYSTEM`.
- Accept only the existing `ThemePreference` enum values: `SYSTEM`, `LIGHT`, and `DARK`.
- Keep the change limited to the REST boundary and focused controller tests.

---

### Task 1: Expose and verify the theme-preference REST boundary

**Files:**
- Create: `src/main/java/com/metabion/dto/ThemePreferenceRequest.java`
- Modify: `src/main/java/com/metabion/controller/api/AccountController.java:3-46`
- Modify: `src/test/java/com/metabion/controller/api/AccountControllerTest.java:3-208`

**Interfaces:**
- Consumes: `UserPreferenceService.currentThemePreference(Authentication): ThemePreference` and `UserPreferenceService.updateThemePreference(Authentication, ThemePreference): void`.
- Produces: `GET /api/account/preferences/theme` returning `Map<String, String>{theme=<enum name>}` and `PUT /api/account/preferences/theme` accepting `ThemePreferenceRequest` and returning `Map<String, String>{status=ok}`.

- [ ] **Step 1: Write the failing controller tests**

  Add `ThemePreference` to the test imports. Append these tests to `AccountControllerTest` before its final brace:

  ```java
  @Test
  void patientCanReadThemePreference() throws Exception {
      when(userPreferenceService.currentThemePreference(any())).thenReturn(ThemePreference.DARK);

      mvc.perform(get("/api/account/preferences/theme")
                      .with(user("patient@example.com").roles(RoleName.PATIENT.name())))
              .andExpect(status().isOk())
              .andExpect(jsonPath("$.theme").value("DARK"));

      verify(userPreferenceService).currentThemePreference(any());
  }

  @Test
  void patientCanUpdateThemePreferenceWithCsrf() throws Exception {
      mvc.perform(put("/api/account/preferences/theme")
                      .with(user("patient@example.com").roles(RoleName.PATIENT.name()))
                      .with(csrf())
                      .contentType(MediaType.APPLICATION_JSON)
                      .content("""
                              {
                                "theme": "DARK"
                              }
                              """))
              .andExpect(status().isOk())
              .andExpect(jsonPath("$.status").value("ok"));

      verify(userPreferenceService).updateThemePreference(any(),
              argThat(preference -> preference == ThemePreference.DARK));
  }

  @Test
  void themePreferenceRequiresAuthentication() throws Exception {
      mvc.perform(get("/api/account/preferences/theme"))
              .andExpect(status().isUnauthorized());

      mvc.perform(put("/api/account/preferences/theme")
                      .with(csrf())
                      .contentType(MediaType.APPLICATION_JSON)
                      .content("""
                              {
                                "theme": "DARK"
                              }
                              """))
              .andExpect(status().isUnauthorized());
  }

  @Test
  void missingThemeReturnsValidationError() throws Exception {
      mvc.perform(put("/api/account/preferences/theme")
                      .with(user("patient@example.com").roles(RoleName.PATIENT.name()))
                      .with(csrf())
                      .contentType(MediaType.APPLICATION_JSON)
                      .content("{}"))
              .andExpect(status().isBadRequest())
              .andExpect(jsonPath("$.error").value("validation_failed"))
              .andExpect(jsonPath("$.fields.theme").exists());
  }

  @Test
  void unknownThemeReturnsBadRequest() throws Exception {
      mvc.perform(put("/api/account/preferences/theme")
                      .with(user("patient@example.com").roles(RoleName.PATIENT.name()))
                      .with(csrf())
                      .contentType(MediaType.APPLICATION_JSON)
                      .content("""
                              {
                                "theme": "SEPIA"
                              }
                              """))
              .andExpect(status().isBadRequest());
  }
  ```

- [ ] **Step 2: Run the focused test class and verify the expected red state**

  Run: `./gradlew test --tests 'com.metabion.controller.api.AccountControllerTest'`

  Expected: the newly added tests fail because `/api/account/preferences/theme` has no controller mapping. Existing tests remain green.

- [ ] **Step 3: Add the validated request DTO and the minimal controller mappings**

  Create `ThemePreferenceRequest`:

  ```java
  package com.metabion.dto;

  import com.metabion.domain.ThemePreference;
  import jakarta.validation.constraints.NotNull;

  public record ThemePreferenceRequest(@NotNull ThemePreference theme) {
  }
  ```

  In `AccountController`, import `ThemePreferenceRequest`, then add:

  ```java
  @GetMapping("/api/account/preferences/theme")
  public Map<String, String> themePreference(Authentication authentication) {
      return Map.of("theme", preferences.currentThemePreference(authentication).name());
  }

  @PutMapping("/api/account/preferences/theme")
  public Map<String, String> updateThemePreference(@Valid @RequestBody ThemePreferenceRequest request,
                                                     Authentication authentication) {
      preferences.updateThemePreference(authentication, request.theme());
      return Map.of("status", "ok");
  }
  ```

- [ ] **Step 4: Run the focused test class and verify green**

  Run: `./gradlew test --tests 'com.metabion.controller.api.AccountControllerTest'`

  Expected: PASS; all existing language and profile tests, plus all five theme tests, pass.

- [ ] **Step 5: Check IDE diagnostics and run the full regression suite**

  Use the IDE build/inspection action for `ThemePreferenceRequest.java`, `AccountController.java`, and `AccountControllerTest.java`, then run: `./gradlew test`

  Expected: no compiler diagnostics and a successful Gradle test task.

- [ ] **Step 6: Commit the focused change**

  ```bash
  git add src/main/java/com/metabion/dto/ThemePreferenceRequest.java \
          src/main/java/com/metabion/controller/api/AccountController.java \
          src/test/java/com/metabion/controller/api/AccountControllerTest.java
  git commit -m "Add theme preference REST endpoint"
  ```
