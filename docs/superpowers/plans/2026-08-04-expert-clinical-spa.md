# Expert Clinical SPA Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an expert clinical area (nutrition specialists, physicians, admins) to the existing patient SPA, backed by two new backend endpoints, covering patient monitoring, check-ins, trends, labs, red flags, and onboarding review.

**Architecture:** One Vue 3 SPA with two role-based areas. New `/clinical/…` route tree guarded by role meta, new `ClinicalShell`, monitor-first overview fed by a new aggregate endpoint `GET /api/clinical/overview` (monitored patients only, no admin bypass), plus a REST wrapper over the existing web-only `ClinicalDailyCheckInService`. All other clinical screens reuse existing `/api/clinical/*` endpoints. Thymeleaf workspace untouched.

**Tech Stack:** Spring Boot 4 / Java 25 backend (Spring MVC, Spring Data JPA, Spring Security session auth); Vue 3 + TypeScript + Vite + Pinia + vue-i18n + Tailwind frontend; Vitest + MSW; JUnit 5 + Mockito + MockMvc.

**Spec:** `docs/superpowers/specs/2026-08-04-expert-clinical-spa-design.md`

## Global Constraints

- No `SecurityConfig` URL changes. `/api/clinical/**` stays under the `/api/**` authenticated rule; role checks (NS/PHYS/ADMIN) live in services and throw `ResponseStatusException`.
- The overview is scoped to **monitored** patients (active cohort-staff or direct expert assignment) for **every role including admin** — never call `findAllPatientOptions()` or `ClinicalPatientDirectoryService.listAccessible()` for it.
- Backend error contract (`GlobalExceptionHandler`): `ResponseStatusException` → `{"error":"forbidden"|"not_found"|"unauthorized"|"conflict"|"request_failed"}` by status; optimistic lock → 409 `{"error":"conflict"}`; bean validation → 400 `{"error":"validation_failed","fields":{…}}`.
- SPA: session cookie auth via `apiFetch` (`credentials: 'same-origin'`), CSRF bootstrapped lazily from `GET /api/csrf` on non-GET. No bearer tokens.
- Every new UI string goes into both `frontend/src/i18n/en.json` and `frontend/src/i18n/cs.json` — key parity is enforced by `frontend/tests/i18n/locale.test.ts`.
- View markup follows the existing idiom: `<script setup lang="ts">`, Tailwind utility classes, `data-testid` markers for test hooks, `useApiError()` for errors.
- Do not modify any Thymeleaf controller/template, education-authoring surface, OAuth/MCP/bearer surface, or coordinator behavior (coordinators keep landing on `/staff-notice`).
- Verification: backend `./gradlew test`; frontend `cd frontend && npm run typecheck && npm run test`.
- Commit style: concise imperative, e.g. `Add clinical daily check-in endpoints`.

---

### Task 1: Backend — clinical daily check-in REST endpoints

Thin `@RestController` over the existing, already-tested `ClinicalDailyCheckInService`
(`src/main/java/com/metabion/service/ClinicalDailyCheckInService.java`), exposing the
merged diet-log + symptom-check-in view that today only exists in Thymeleaf.

**Files:**
- Create: `src/main/java/com/metabion/controller/api/ClinicalDailyCheckInController.java`
- Test: `src/test/java/com/metabion/controller/api/ClinicalDailyCheckInControllerTest.java`

**Interfaces:**
- Consumes: `ClinicalDailyCheckInService.list(Authentication, Long patientProfileId, LocalDate from, LocalDate to)` → `List<ClinicalDailyCheckInSummaryResponse>` (null `patientProfileId` allowed); `ClinicalDailyCheckInService.get(Authentication, Long, LocalDate)` → `ClinicalDailyCheckInDetailResponse` (throws `ResponseStatusException` 404 when neither half exists, 403 for non-clinical/unassigned callers).
- Produces: `GET /api/clinical/daily-check-ins?patientProfileId=&from=&to=` and `GET /api/clinical/daily-check-ins/{patientProfileId}/{date}` — consumed by frontend Tasks 4, 9, 10.

- [ ] **Step 1: Write the failing MVC test**

Mirrors the setup of `src/test/java/com/metabion/controller/api/ClinicalRedFlagControllerTest.java`
(`@SpringBootTest` with H2 + flyway off + session JDBC excluded, hand-built MockMvc with all
`Filter` beans + `springSecurity()`, `@MockitoBean` services).

```java
package com.metabion.controller.api;

import com.metabion.domain.DietAdherenceLevel;
import com.metabion.domain.FlareState;
import com.metabion.domain.RoleName;
import com.metabion.dto.ClinicalDailyCheckInDetailResponse;
import com.metabion.dto.ClinicalDailyCheckInSummaryResponse;
import com.metabion.service.ClinicalDailyCheckInService;
import com.metabion.service.SecurityService;
import com.metabion.service.UserService;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.profiles.active=dev",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.datasource.url=jdbc:h2:mem:clinical_daily_check_in_controller_test;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.autoconfigure.exclude=org.springframework.boot.session.jdbc.autoconfigure.JdbcSessionAutoConfiguration"
})
class ClinicalDailyCheckInControllerTest {

    @Autowired
    WebApplicationContext context;

    @MockitoBean
    FindByIndexNameSessionRepository<Session> sessions;

    @MockitoBean
    UserService userService;

    @MockitoBean
    SecurityService securityService;

    @MockitoBean
    ClinicalDailyCheckInService dailyCheckIns;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        Filter[] filters = context.getBeansOfType(Filter.class).values().toArray(new Filter[0]);
        mvc = MockMvcBuilders
                .webAppContextSetup(context)
                .addFilters(filters)
                .apply(springSecurity())
                .build();
    }

    @Test
    void listForwardsRangeAndReturnsSummaries() throws Exception {
        var from = LocalDate.of(2026, 7, 28);
        var to = LocalDate.of(2026, 8, 3);
        when(dailyCheckIns.list(any(), eq(41L), eq(from), eq(to))).thenReturn(List.of(
                new ClinicalDailyCheckInSummaryResponse(
                        41L, "patient@example.com", to,
                        7L, DietAdherenceLevel.FULL, null, 3, 0, 2,
                        9L, new BigDecimal("4"), FlareState.NO_FLARE)));

        mvc.perform(get("/api/clinical/daily-check-ins")
                        .param("patientProfileId", "41")
                        .param("from", "2026-07-28")
                        .param("to", "2026-08-03")
                        .with(user("doctor@example.com").roles(RoleName.PHYSICIAN.name())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].patientProfileId").value(41))
                .andExpect(jsonPath("$[0].adherenceLevel").value("FULL"))
                .andExpect(jsonPath("$[0].flareState").value("NO_FLARE"));

        verify(dailyCheckIns).list(any(), eq(41L), eq(from), eq(to));
    }

    @Test
    void detailReturnsMergedDay() throws Exception {
        var date = LocalDate.of(2026, 8, 3);
        when(dailyCheckIns.get(any(), eq(41L), eq(date))).thenReturn(
                new ClinicalDailyCheckInDetailResponse(41L, "patient@example.com", date, null, null));

        mvc.perform(get("/api/clinical/daily-check-ins/41/2026-08-03")
                        .with(user("nurse@example.com").roles(RoleName.NUTRITION_SPECIALIST.name())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.patientProfileId").value(41))
                .andExpect(jsonPath("$.date").value("2026-08-03"));

        verify(dailyCheckIns).get(any(), eq(41L), eq(date));
    }

    @Test
    void nonClinicalCallerGetsForbiddenJson() throws Exception {
        when(dailyCheckIns.list(any(), any(), any(), any()))
                .thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "Current user cannot access clinical data"));

        mvc.perform(get("/api/clinical/daily-check-ins")
                        .param("from", "2026-07-28")
                        .param("to", "2026-08-03")
                        .with(user("coordinator@example.com").roles(RoleName.COORDINATOR.name())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("forbidden"));
    }

    @Test
    void anonymousCallerGetsUnauthorizedJson() throws Exception {
        mvc.perform(get("/api/clinical/daily-check-ins")
                        .param("from", "2026-07-28")
                        .param("to", "2026-08-03"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("unauthorized"));
    }

    @Test
    void missingDayGetsNotFoundJson() throws Exception {
        when(dailyCheckIns.get(any(), eq(41L), any()))
                .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Daily check-in not found"));

        mvc.perform(get("/api/clinical/daily-check-ins/41/2026-08-03")
                        .with(user("doctor@example.com").roles(RoleName.PHYSICIAN.name())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("not_found"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests 'com.metabion.controller.api.ClinicalDailyCheckInControllerTest'`
Expected: FAIL — compilation error, `ClinicalDailyCheckInController` does not exist (404s on all routes).

- [ ] **Step 3: Write the controller**

```java
package com.metabion.controller.api;

import com.metabion.dto.ClinicalDailyCheckInDetailResponse;
import com.metabion.dto.ClinicalDailyCheckInSummaryResponse;
import com.metabion.service.ClinicalDailyCheckInService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

@RestController
public class ClinicalDailyCheckInController {

    private final ClinicalDailyCheckInService dailyCheckIns;

    public ClinicalDailyCheckInController(ClinicalDailyCheckInService dailyCheckIns) {
        this.dailyCheckIns = dailyCheckIns;
    }

    @GetMapping("/api/clinical/daily-check-ins")
    public List<ClinicalDailyCheckInSummaryResponse> list(@RequestParam(required = false) Long patientProfileId,
                                                          @RequestParam LocalDate from,
                                                          @RequestParam LocalDate to,
                                                          Authentication authentication) {
        return dailyCheckIns.list(authentication, patientProfileId, from, to);
    }

    @GetMapping("/api/clinical/daily-check-ins/{patientProfileId}/{date}")
    public ClinicalDailyCheckInDetailResponse detail(@PathVariable Long patientProfileId,
                                                     @PathVariable LocalDate date,
                                                     Authentication authentication) {
        return dailyCheckIns.get(authentication, patientProfileId, date);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests 'com.metabion.controller.api.ClinicalDailyCheckInControllerTest'`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/metabion/controller/api/ClinicalDailyCheckInController.java \
        src/test/java/com/metabion/controller/api/ClinicalDailyCheckInControllerTest.java
git commit -m "Add clinical daily check-in REST endpoints"
```

---

### Task 2: Backend — clinical overview aggregate service

New `ClinicalOverviewService` producing one summary row per **monitored** patient.
Monitored = active cohort-staff or direct expert assignment, resolved via
`PatientProfileRepository.findAccessiblePatientOptionsForStaff(staffProfileId)` for every
role — admins get no bypass on this aggregate (data minimization, see spec).

**Files:**
- Create: `src/main/java/com/metabion/dto/ClinicalPatientOverviewResponse.java`
- Create: `src/main/java/com/metabion/service/ClinicalOverviewService.java`
- Modify: `src/main/java/com/metabion/repository/SymptomCheckInRepository.java` (add one derived finder)
- Modify: `src/main/java/com/metabion/repository/DailyDietLogRepository.java` (add one derived finder)
- Modify: `src/main/java/com/metabion/repository/DailyMeasurementEntryRepository.java` (add one derived finder)
- Modify: `src/main/java/com/metabion/repository/OnboardingSubmissionRepository.java` (add one derived count)
- Test: `src/test/java/com/metabion/service/ClinicalOverviewServiceTest.java`

**Interfaces:**
- Consumes: `RedFlagEventQueryService.currentForClinicalPatient(Authentication, Long)` → `ClinicalRedFlagSnapshotResponse(highestSeverity, flags)` (already does the NS/PHYS/ADMIN + per-patient check; called only for monitored patients); existing repositories above; `User.hasAnyRole(RoleName…)`, `UserService.normalize(String)` (package-visible in `com.metabion.service`).
- Produces: `ClinicalOverviewService.overview(Authentication)` → `List<ClinicalPatientOverviewResponse>` — consumed by Task 3. Record shape (used by the frontend Task 4 types):
  `patientProfileId, patientEmail, currentRedFlagCount, highestRedFlagSeverity, latestFlareState, latestSymptomScore, latestSymptomCheckInDate, latestKetoneValue, latestKetoneUnit, latestKetoneMeasuredAt, latestAdherenceLevel, lastActivityDate, pendingOnboardingCount`.

Note on test coverage: the four new derived repository methods are validated by Spring
Data at context bootstrap (every `@SpringBootTest` fails on a bad derivation), and their
semantics are trivial (`findFirst…OrderBy…Desc`, `countBy…`), so no dedicated
`@DataJpaTest` is added — the Mockito service test below covers the aggregation logic.

- [ ] **Step 1: Write the failing service test**

Idiom follows `src/test/java/com/metabion/service/ClinicalPatientDirectoryServiceTest.java`
(plain JUnit 5 + Mockito, entities built via `new User(email, "hash")` / `user.addRole(role)` /
`new StaffProfile(user)`, `TestingAuthenticationToken`).

```java
package com.metabion.service;

import com.metabion.domain.DailyDietLog;
import com.metabion.domain.DailyMeasurementEntry;
import com.metabion.domain.DietAdherenceLevel;
import com.metabion.domain.FlareState;
import com.metabion.domain.MeasurementType;
import com.metabion.domain.MeasurementUnit;
import com.metabion.domain.OnboardingReviewStatus;
import com.metabion.domain.RedFlagSeverity;
import com.metabion.domain.RedFlagSourceType;
import com.metabion.domain.RoleName;
import com.metabion.domain.StaffProfile;
import com.metabion.domain.SymptomCheckIn;
import com.metabion.domain.User;
import com.metabion.dto.PatientOptionResponse;
import com.metabion.dto.redflag.ClinicalRedFlagEventResponse;
import com.metabion.dto.redflag.ClinicalRedFlagSnapshotResponse;
import com.metabion.repository.DailyDietLogRepository;
import com.metabion.repository.DailyMeasurementEntryRepository;
import com.metabion.repository.OnboardingSubmissionRepository;
import com.metabion.repository.PatientProfileRepository;
import com.metabion.repository.StaffProfileRepository;
import com.metabion.repository.SymptomCheckInRepository;
import com.metabion.repository.UserRepository;
import com.metabion.service.redflag.RedFlagEventQueryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ClinicalOverviewServiceTest {

    @Mock UserRepository users;
    @Mock StaffProfileRepository staffProfiles;
    @Mock PatientProfileRepository patientProfiles;
    @Mock SymptomCheckInRepository symptomCheckIns;
    @Mock DailyDietLogRepository dietLogs;
    @Mock DailyMeasurementEntryRepository measurements;
    @Mock OnboardingSubmissionRepository onboardingSubmissions;
    @Mock RedFlagEventQueryService redFlags;

    private ClinicalOverviewService service() {
        return new ClinicalOverviewService(users, staffProfiles, patientProfiles,
                symptomCheckIns, dietLogs, measurements, onboardingSubmissions, redFlags);
    }

    @Test
    void physicianGetsAggregatedRowPerMonitoredPatient() {
        var doctor = user(2L, "doctor@example.com", RoleName.PHYSICIAN);
        var staff = new StaffProfile(doctor);
        staff.setId(20L);
        when(users.findByEmail("doctor@example.com")).thenReturn(Optional.of(doctor));
        when(staffProfiles.findByUserId(doctor.getId())).thenReturn(Optional.of(staff));
        when(patientProfiles.findAccessiblePatientOptionsForStaff(20L))
                .thenReturn(List.of(new PatientOptionResponse(41L, "patient@example.com")));

        when(redFlags.currentForClinicalPatient(any(), eq(41L))).thenReturn(
                new ClinicalRedFlagSnapshotResponse(RedFlagSeverity.URGENT_REVIEW, List.of(
                        new ClinicalRedFlagEventResponse(5L, "SYM_HIGH_SCORE", RedFlagSeverity.URGENT_REVIEW,
                                Instant.parse("2026-08-03T08:00:00Z"), RedFlagSourceType.SYMPTOM_CHECK_IN,
                                9L, true, null, 1, null))));

        // Entities have protected no-arg constructors (JPA), so mock them and stub the
        // getters the service reads.
        var checkIn = mock(SymptomCheckIn.class);
        when(checkIn.getCheckInDate()).thenReturn(LocalDate.of(2026, 8, 2));
        when(checkIn.getFlareState()).thenReturn(FlareState.SUSPECTED_FLARE);
        when(checkIn.getTotalSymptomScore()).thenReturn(new BigDecimal("7"));
        when(symptomCheckIns.findFirstByPatientProfileIdOrderByCheckInDateDesc(41L))
                .thenReturn(Optional.of(checkIn));

        var log = mock(DailyDietLog.class);
        when(log.getLogDate()).thenReturn(LocalDate.of(2026, 8, 3));
        when(log.getAdherenceLevel()).thenReturn(DietAdherenceLevel.MOSTLY);
        when(dietLogs.findFirstByPatientProfileIdOrderByLogDateDesc(41L))
                .thenReturn(Optional.of(log));

        var ketone = mock(DailyMeasurementEntry.class);
        when(ketone.getValue()).thenReturn(new BigDecimal("1.8"));
        when(ketone.getUnit()).thenReturn(MeasurementUnit.MMOL_L);
        when(ketone.getMeasuredAt()).thenReturn(Instant.parse("2026-08-03T06:30:00Z"));
        when(measurements.findFirstByPatientProfileIdAndMeasurementTypeOrderByMeasuredAtDesc(
                41L, MeasurementType.KETONE)).thenReturn(Optional.of(ketone));

        when(onboardingSubmissions.countByPatientProfileIdAndReviewStatus(
                41L, OnboardingReviewStatus.PENDING_REVIEW)).thenReturn(2L);

        var rows = service().overview(auth("doctor@example.com"));

        assertThat(rows).hasSize(1);
        var row = rows.get(0);
        assertThat(row.patientProfileId()).isEqualTo(41L);
        assertThat(row.patientEmail()).isEqualTo("patient@example.com");
        assertThat(row.currentRedFlagCount()).isEqualTo(1);
        assertThat(row.highestRedFlagSeverity()).isEqualTo(RedFlagSeverity.URGENT_REVIEW);
        assertThat(row.latestFlareState()).isEqualTo(FlareState.SUSPECTED_FLARE);
        assertThat(row.latestSymptomScore()).isEqualByComparingTo("7");
        assertThat(row.latestSymptomCheckInDate()).isEqualTo(LocalDate.of(2026, 8, 2));
        assertThat(row.latestKetoneValue()).isEqualByComparingTo("1.8");
        assertThat(row.latestKetoneUnit()).isEqualTo(MeasurementUnit.MMOL_L);
        assertThat(row.latestKetoneMeasuredAt()).isEqualTo(Instant.parse("2026-08-03T06:30:00Z"));
        assertThat(row.latestAdherenceLevel()).isEqualTo(DietAdherenceLevel.MOSTLY);
        // last activity = max(diet log 08-03, check-in 08-02)
        assertThat(row.lastActivityDate()).isEqualTo(LocalDate.of(2026, 8, 3));
        assertThat(row.pendingOnboardingCount()).isEqualTo(2L);
    }

    @Test
    void adminIsScopedToOwnAssignmentsLikeAnyStaffMember() {
        var admin = user(1L, "admin@example.com", RoleName.ADMIN);
        var staff = new StaffProfile(admin);
        staff.setId(10L);
        when(users.findByEmail("admin@example.com")).thenReturn(Optional.of(admin));
        when(staffProfiles.findByUserId(admin.getId())).thenReturn(Optional.of(staff));
        when(patientProfiles.findAccessiblePatientOptionsForStaff(10L)).thenReturn(List.of());

        assertThat(service().overview(auth("admin@example.com"))).isEmpty();
        // Deliberate: no admin bypass on the overview aggregate.
        verifyNoInteractions(redFlags);
    }

    @Test
    void userWithoutStaffProfileGetsEmptyOverview() {
        var doctor = user(2L, "doctor@example.com", RoleName.PHYSICIAN);
        when(users.findByEmail("doctor@example.com")).thenReturn(Optional.of(doctor));
        when(staffProfiles.findByUserId(doctor.getId())).thenReturn(Optional.empty());

        assertThat(service().overview(auth("doctor@example.com"))).isEmpty();
        verifyNoInteractions(patientProfiles, redFlags);
    }

    @Test
    void nonClinicalRoleIsRejected() {
        var patient = user(3L, "patient@example.com", RoleName.PATIENT);
        when(users.findByEmail("patient@example.com")).thenReturn(Optional.of(patient));

        assertThatThrownBy(() -> service().overview(auth("patient@example.com")))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode())
                        .isEqualTo(HttpStatus.FORBIDDEN));
    }

    private User user(Long id, String email, RoleName role) {
        var user = new User(email, "hash");
        user.setId(id);
        user.addRole(role);
        return user;
    }

    private Authentication auth(String email) {
        var authentication = new TestingAuthenticationToken(email, "password");
        authentication.setAuthenticated(true);
        return authentication;
    }
}
```

(Mockito `mock(...)` is used for the JPA entities because their no-arg constructors are
`protected`; the service under test only reads getters, so stubbed mocks keep the test
free of entity-constructor details.)

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests 'com.metabion.service.ClinicalOverviewServiceTest'`
Expected: FAIL — compilation error, `ClinicalOverviewService` and
`ClinicalPatientOverviewResponse` do not exist.

- [ ] **Step 3: Add the derived repository finders**

In `SymptomCheckInRepository` (alongside the existing methods):

```java
    Optional<SymptomCheckIn> findFirstByPatientProfileIdOrderByCheckInDateDesc(Long patientProfileId);
```

In `DailyDietLogRepository`:

```java
    Optional<DailyDietLog> findFirstByPatientProfileIdOrderByLogDateDesc(Long patientProfileId);
```

In `DailyMeasurementEntryRepository` (import `com.metabion.domain.MeasurementType` if not already imported):

```java
    Optional<DailyMeasurementEntry> findFirstByPatientProfileIdAndMeasurementTypeOrderByMeasuredAtDesc(
            Long patientProfileId, MeasurementType measurementType);
```

In `OnboardingSubmissionRepository`:

```java
    long countByPatientProfileIdAndReviewStatus(Long patientProfileId, OnboardingReviewStatus reviewStatus);
```

- [ ] **Step 4: Write the DTO and service**

`src/main/java/com/metabion/dto/ClinicalPatientOverviewResponse.java`:

```java
package com.metabion.dto;

import com.metabion.domain.DietAdherenceLevel;
import com.metabion.domain.FlareState;
import com.metabion.domain.MeasurementUnit;
import com.metabion.domain.RedFlagSeverity;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

public record ClinicalPatientOverviewResponse(
        Long patientProfileId,
        String patientEmail,
        int currentRedFlagCount,
        RedFlagSeverity highestRedFlagSeverity,
        FlareState latestFlareState,
        BigDecimal latestSymptomScore,
        LocalDate latestSymptomCheckInDate,
        BigDecimal latestKetoneValue,
        MeasurementUnit latestKetoneUnit,
        Instant latestKetoneMeasuredAt,
        DietAdherenceLevel latestAdherenceLevel,
        LocalDate lastActivityDate,
        long pendingOnboardingCount) {
}
```

`src/main/java/com/metabion/service/ClinicalOverviewService.java`:

```java
package com.metabion.service;

import com.metabion.domain.MeasurementType;
import com.metabion.domain.OnboardingReviewStatus;
import com.metabion.domain.RoleName;
import com.metabion.domain.User;
import com.metabion.dto.ClinicalPatientOverviewResponse;
import com.metabion.dto.PatientOptionResponse;
import com.metabion.repository.DailyDietLogRepository;
import com.metabion.repository.DailyMeasurementEntryRepository;
import com.metabion.repository.OnboardingSubmissionRepository;
import com.metabion.repository.PatientProfileRepository;
import com.metabion.repository.StaffProfileRepository;
import com.metabion.repository.SymptomCheckInRepository;
import com.metabion.repository.UserRepository;
import com.metabion.service.redflag.RedFlagEventQueryService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;

@Service
@Transactional(readOnly = true)
public class ClinicalOverviewService {

    private final UserRepository users;
    private final StaffProfileRepository staffProfiles;
    private final PatientProfileRepository patientProfiles;
    private final SymptomCheckInRepository symptomCheckIns;
    private final DailyDietLogRepository dietLogs;
    private final DailyMeasurementEntryRepository measurements;
    private final OnboardingSubmissionRepository onboardingSubmissions;
    private final RedFlagEventQueryService redFlags;

    public ClinicalOverviewService(UserRepository users,
                                   StaffProfileRepository staffProfiles,
                                   PatientProfileRepository patientProfiles,
                                   SymptomCheckInRepository symptomCheckIns,
                                   DailyDietLogRepository dietLogs,
                                   DailyMeasurementEntryRepository measurements,
                                   OnboardingSubmissionRepository onboardingSubmissions,
                                   RedFlagEventQueryService redFlags) {
        this.users = users;
        this.staffProfiles = staffProfiles;
        this.patientProfiles = patientProfiles;
        this.symptomCheckIns = symptomCheckIns;
        this.dietLogs = dietLogs;
        this.measurements = measurements;
        this.onboardingSubmissions = onboardingSubmissions;
        this.redFlags = redFlags;
    }

    public List<ClinicalPatientOverviewResponse> overview(Authentication authentication) {
        var user = currentUser(authentication);
        if (!user.hasAnyRole(RoleName.NUTRITION_SPECIALIST, RoleName.PHYSICIAN, RoleName.ADMIN)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Current user cannot access clinical data");
        }
        // Monitored patients only — the admin access bypass deliberately does not apply
        // here: the overview is a personal workload view for every role.
        return staffProfiles.findByUserId(user.getId())
                .map(staff -> patientProfiles.findAccessiblePatientOptionsForStaff(staff.getId()))
                .orElseGet(List::of)
                .stream()
                .map(patient -> rowFor(authentication, patient))
                .toList();
    }

    private ClinicalPatientOverviewResponse rowFor(Authentication authentication,
                                                   PatientOptionResponse patient) {
        Long id = patient.id();
        var redFlagSnapshot = redFlags.currentForClinicalPatient(authentication, id);
        var latestCheckIn = symptomCheckIns.findFirstByPatientProfileIdOrderByCheckInDateDesc(id).orElse(null);
        var latestLog = dietLogs.findFirstByPatientProfileIdOrderByLogDateDesc(id).orElse(null);
        var latestKetone = measurements
                .findFirstByPatientProfileIdAndMeasurementTypeOrderByMeasuredAtDesc(id, MeasurementType.KETONE)
                .orElse(null);
        long pending = onboardingSubmissions.countByPatientProfileIdAndReviewStatus(
                id, OnboardingReviewStatus.PENDING_REVIEW);
        return new ClinicalPatientOverviewResponse(
                id,
                patient.email(),
                redFlagSnapshot.flags().size(),
                redFlagSnapshot.highestSeverity(),
                latestCheckIn == null ? null : latestCheckIn.getFlareState(),
                latestCheckIn == null ? null : latestCheckIn.getTotalSymptomScore(),
                latestCheckIn == null ? null : latestCheckIn.getCheckInDate(),
                latestKetone == null ? null : latestKetone.getValue(),
                latestKetone == null ? null : latestKetone.getUnit(),
                latestKetone == null ? null : latestKetone.getMeasuredAt(),
                latestLog == null ? null : latestLog.getAdherenceLevel(),
                lastActivity(latestCheckIn == null ? null : latestCheckIn.getCheckInDate(),
                        latestLog == null ? null : latestLog.getLogDate()),
                pending);
    }

    private LocalDate lastActivity(LocalDate checkInDate, LocalDate logDate) {
        if (checkInDate == null) {
            return logDate;
        }
        if (logDate == null) {
            return checkInDate;
        }
        return checkInDate.isAfter(logDate) ? checkInDate : logDate;
    }

    private User currentUser(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated() || authentication.getName() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        return users.findByEmail(UserService.normalize(authentication.getName()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                        "Authenticated user not found"));
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew test --tests 'com.metabion.service.ClinicalOverviewServiceTest'`
Expected: PASS (4 tests). Then run the red-flag and directory service tests to confirm no
regression from the repository edits:
`./gradlew test --tests 'com.metabion.service.*' --tests 'com.metabion.repository.*'`

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/metabion/dto/ClinicalPatientOverviewResponse.java \
        src/main/java/com/metabion/service/ClinicalOverviewService.java \
        src/main/java/com/metabion/repository/SymptomCheckInRepository.java \
        src/main/java/com/metabion/repository/DailyDietLogRepository.java \
        src/main/java/com/metabion/repository/DailyMeasurementEntryRepository.java \
        src/main/java/com/metabion/repository/OnboardingSubmissionRepository.java \
        src/test/java/com/metabion/service/ClinicalOverviewServiceTest.java
git commit -m "Add clinical overview aggregate service"
```

---

### Task 3: Backend — clinical overview endpoint

**Files:**
- Create: `src/main/java/com/metabion/controller/api/ClinicalOverviewController.java`
- Test: `src/test/java/com/metabion/controller/api/ClinicalOverviewControllerTest.java`

**Interfaces:**
- Consumes: `ClinicalOverviewService.overview(Authentication)` from Task 2.
- Produces: `GET /api/clinical/overview` → `List<ClinicalPatientOverviewResponse>` with `Cache-Control: no-store` (clinical payload, matching `ClinicalRedFlagController`). Consumed by frontend Tasks 4 and 7.

- [ ] **Step 1: Write the failing MVC test**

Same setup idiom as Task 1's test (copy the class preamble; only the mocked service and
the assertions differ).

```java
package com.metabion.controller.api;

import com.metabion.domain.DietAdherenceLevel;
import com.metabion.domain.FlareState;
import com.metabion.domain.MeasurementUnit;
import com.metabion.domain.RedFlagSeverity;
import com.metabion.domain.RoleName;
import com.metabion.dto.ClinicalPatientOverviewResponse;
import com.metabion.service.ClinicalOverviewService;
import com.metabion.service.SecurityService;
import com.metabion.service.UserService;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.profiles.active=dev",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.datasource.url=jdbc:h2:mem:clinical_overview_controller_test;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.autoconfigure.exclude=org.springframework.boot.session.jdbc.autoconfigure.JdbcSessionAutoConfiguration"
})
class ClinicalOverviewControllerTest {

    @Autowired
    WebApplicationContext context;

    @MockitoBean
    FindByIndexNameSessionRepository<Session> sessions;

    @MockitoBean
    UserService userService;

    @MockitoBean
    SecurityService securityService;

    @MockitoBean
    ClinicalOverviewService overviewService;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        Filter[] filters = context.getBeansOfType(Filter.class).values().toArray(new Filter[0]);
        mvc = MockMvcBuilders
                .webAppContextSetup(context)
                .addFilters(filters)
                .apply(springSecurity())
                .build();
    }

    @Test
    void overviewReturnsNoStoreRows() throws Exception {
        when(overviewService.overview(any())).thenReturn(List.of(
                new ClinicalPatientOverviewResponse(
                        41L, "patient@example.com", 2, RedFlagSeverity.URGENT_REVIEW,
                        FlareState.SUSPECTED_FLARE, new BigDecimal("7"), LocalDate.of(2026, 8, 2),
                        new BigDecimal("1.8"), MeasurementUnit.MMOL_L, Instant.parse("2026-08-03T06:30:00Z"),
                        DietAdherenceLevel.MOSTLY, LocalDate.of(2026, 8, 3), 1L)));

        mvc.perform(get("/api/clinical/overview")
                        .with(user("doctor@example.com").roles(RoleName.PHYSICIAN.name())))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(jsonPath("$[0].patientProfileId").value(41))
                .andExpect(jsonPath("$[0].currentRedFlagCount").value(2))
                .andExpect(jsonPath("$[0].highestRedFlagSeverity").value("URGENT_REVIEW"))
                .andExpect(jsonPath("$[0].latestFlareState").value("SUSPECTED_FLARE"))
                .andExpect(jsonPath("$[0].latestKetoneValue").value(1.8))
                .andExpect(jsonPath("$[0].pendingOnboardingCount").value(1));
    }

    @Test
    void nonClinicalCallerGetsForbiddenJson() throws Exception {
        when(overviewService.overview(any()))
                .thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "Current user cannot access clinical data"));

        mvc.perform(get("/api/clinical/overview")
                        .with(user("coordinator@example.com").roles(RoleName.COORDINATOR.name())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("forbidden"));
    }

    @Test
    void anonymousCallerGetsUnauthorizedJson() throws Exception {
        mvc.perform(get("/api/clinical/overview"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("unauthorized"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests 'com.metabion.controller.api.ClinicalOverviewControllerTest'`
Expected: FAIL — compilation error, `ClinicalOverviewController` does not exist.

- [ ] **Step 3: Write the controller**

```java
package com.metabion.controller.api;

import com.metabion.dto.ClinicalPatientOverviewResponse;
import com.metabion.service.ClinicalOverviewService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class ClinicalOverviewController {

    private final ClinicalOverviewService overviewService;

    public ClinicalOverviewController(ClinicalOverviewService overviewService) {
        this.overviewService = overviewService;
    }

    @GetMapping("/api/clinical/overview")
    public ResponseEntity<List<ClinicalPatientOverviewResponse>> overview(Authentication authentication) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(overviewService.overview(authentication));
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests 'com.metabion.controller.api.ClinicalOverviewControllerTest'`
Expected: PASS (3 tests). Then run the whole backend suite once before moving to the
frontend: `./gradlew test` — Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/metabion/controller/api/ClinicalOverviewController.java \
        src/test/java/com/metabion/controller/api/ClinicalOverviewControllerTest.java
git commit -m "Add clinical overview endpoint"
```

---

### Task 4: Frontend — clinical types, API module, i18n section

Foundation for the expert area: TS mirrors of the clinical DTOs, one `clinicalApi`
module covering every clinical endpoint the screens use, and the `clinical.*` i18n
section (all later view tasks reference these keys — do not add keys ad hoc later).

**Files:**
- Modify: `frontend/src/types/api.ts` (append a Clinical section at the end)
- Create: `frontend/src/api/clinical.ts`
- Modify: `frontend/src/i18n/en.json` (insert `clinical` section after the `checkIn` section)
- Modify: `frontend/src/i18n/cs.json` (same position)
- Test: `frontend/tests/api/clinical.test.ts`

**Interfaces:**
- Consumes: `apiFetch` from `./http`; existing types `DailyDietLogResponse`, `SymptomCheckInResponse`, `DailyTrendResponse`, `LabResultSetRequest`, `LabResultSetResponse`, `LabTrendResponse`, `OnboardingSubmissionResponse`, `OnboardingSubmissionSummary`, `OnboardingReviewStatus`, `RedFlagHistoryParams`, `RedFlagSeverity`, `RedFlagSourceType`, `FlareState`, `DietAdherenceLevel`, `AppetiteLevel`, `MeasurementUnit`.
- Produces: types `ClinicalPatientOverview`, `ClinicalDailyCheckInSummary`, `ClinicalDailyCheckInDetail`, `ClinicalRedFlagEvent`, `ClinicalRedFlagSnapshot`, `ClinicalRedFlagHistoryPage`, `OnboardingReviewRequest`; `clinicalApi` with methods `overview`, `listDailyCheckIns`, `getDailyCheckIn`, `dailyTrend`, `listLabResultSets`, `getLabResultSet`, `createLabResultSet`, `updateLabResultSet`, `requestLabRemoval`, `labTrend`, `currentRedFlags`, `redFlagHistory`, `listOnboardingSubmissions`, `getOnboardingSubmission`, `reviewOnboardingSubmission`. Every later frontend task imports from this task only.

- [ ] **Step 1: Write the failing API test**

Follows the MSW idiom of `frontend/tests/api/redFlags.test.ts` (stub via `server.use`,
call the API, assert on the intercepted request). CSRF header behavior is already covered
by `tests/api/http.test.ts` and is not re-tested here.

```ts
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import { server } from '../msw/server'
import { clinicalApi } from '@/api/clinical'

describe('clinicalApi', () => {
  it('requests the overview', async () => {
    let seenUrl = ''
    server.use(
      http.get('/api/clinical/overview', ({ request }) => {
        seenUrl = request.url
        return HttpResponse.json([])
      }),
    )
    const rows = await clinicalApi.overview()
    expect(rows).toEqual([])
    expect(seenUrl).toContain('/api/clinical/overview')
  })

  it('builds the daily check-ins query string', async () => {
    let seenUrl = ''
    server.use(
      http.get('/api/clinical/daily-check-ins', ({ request }) => {
        seenUrl = request.url
        return HttpResponse.json([])
      }),
    )
    await clinicalApi.listDailyCheckIns(41, '2026-07-28', '2026-08-03')
    expect(seenUrl).toContain('patientProfileId=41')
    expect(seenUrl).toContain('from=2026-07-28')
    expect(seenUrl).toContain('to=2026-08-03')
  })

  it('builds the red-flag history query string', async () => {
    let seenUrl = ''
    server.use(
      http.get('/api/clinical/patients/41/red-flags/history', ({ request }) => {
        seenUrl = request.url
        return HttpResponse.json({ items: [], nextCursor: null })
      }),
    )
    await clinicalApi.redFlagHistory(41, { from: '2026-07-01', severity: 'EMERGENCY', size: 25 })
    expect(seenUrl).toContain('from=2026-07-01')
    expect(seenUrl).toContain('severity=EMERGENCY')
    expect(seenUrl).toContain('size=25')
    expect(seenUrl).not.toContain('cursor=')
  })

  it('posts the onboarding review body', async () => {
    let received: unknown
    server.use(
      http.get('/api/csrf', () => HttpResponse.json({ token: 't', headerName: 'X-XSRF-TOKEN' })),
      http.post('/api/clinical/onboarding/submissions/9/review', async ({ request }) => {
        received = await request.json()
        return HttpResponse.json({ id: 9 })
      }),
    )
    await clinicalApi.reviewOnboardingSubmission(9, { reviewStatus: 'REVIEWED', reviewNotes: 'ok' })
    expect(received).toEqual({ reviewStatus: 'REVIEWED', reviewNotes: 'ok' })
  })

  it('builds onboarding list filters', async () => {
    let seenUrl = ''
    server.use(
      http.get('/api/clinical/onboarding/submissions', ({ request }) => {
        seenUrl = request.url
        return HttpResponse.json([])
      }),
    )
    await clinicalApi.listOnboardingSubmissions(undefined, 'PENDING_REVIEW')
    expect(seenUrl).toContain('status=PENDING_REVIEW')
    expect(seenUrl).not.toContain('context=')
  })
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && npx vitest run tests/api/clinical.test.ts`
Expected: FAIL — `@/api/clinical` does not exist.

- [ ] **Step 3: Add the types**

Append to `frontend/src/types/api.ts`:

```ts
// Clinical (expert area; mirror backend clinical DTOs)
export interface ClinicalPatientOverview {
  patientProfileId: number
  patientEmail: string
  currentRedFlagCount: number
  highestRedFlagSeverity: RedFlagSeverity | null
  latestFlareState: FlareState | null
  latestSymptomScore: number | null
  latestSymptomCheckInDate: string | null
  latestKetoneValue: number | null
  latestKetoneUnit: MeasurementUnit | null
  latestKetoneMeasuredAt: string | null
  latestAdherenceLevel: DietAdherenceLevel | null
  lastActivityDate: string | null
  pendingOnboardingCount: number
}

export interface ClinicalDailyCheckInSummary {
  patientProfileId: number
  patientEmail: string
  date: string
  dietLogId: number | null
  adherenceLevel: DietAdherenceLevel | null
  appetiteLevel: AppetiteLevel | null
  mealCount: number | null
  deviationCount: number | null
  measurementCount: number | null
  symptomCheckInId: number | null
  symptomScore: number | null
  flareState: FlareState | null
}

export interface ClinicalDailyCheckInDetail {
  patientProfileId: number
  patientEmail: string
  date: string
  dietLog: DailyDietLogResponse | null
  symptomCheckIn: SymptomCheckInResponse | null
}

export interface ClinicalRedFlagEvent {
  eventId: number
  ruleKey: string
  severity: RedFlagSeverity
  detectedAt: string
  sourceType: RedFlagSourceType
  sourceId: number
  current: boolean
  supersededAt: string | null
  ruleVersion: number
}

export interface ClinicalRedFlagSnapshot {
  highestSeverity: RedFlagSeverity | null
  flags: ClinicalRedFlagEvent[]
}

export interface ClinicalRedFlagHistoryPage {
  items: ClinicalRedFlagEvent[]
  nextCursor: string | null
}

export interface OnboardingReviewRequest {
  reviewStatus: OnboardingReviewStatus
  reviewNotes?: string
}
```

- [ ] **Step 4: Write the API module**

Create `frontend/src/api/clinical.ts`:

```ts
import { apiFetch } from './http'
import type {
  ClinicalDailyCheckInDetail,
  ClinicalDailyCheckInSummary,
  ClinicalPatientOverview,
  ClinicalRedFlagHistoryPage,
  ClinicalRedFlagSnapshot,
  DailyTrendResponse,
  LabResultSetRequest,
  LabResultSetResponse,
  LabTrendResponse,
  OnboardingReviewRequest,
  OnboardingReviewStatus,
  OnboardingSubmissionResponse,
  OnboardingSubmissionSummary,
  RedFlagHistoryParams,
} from '@/types/api'

export const clinicalApi = {
  overview: () => apiFetch<ClinicalPatientOverview[]>('/api/clinical/overview'),

  listDailyCheckIns: (patientProfileId: number, from: string, to: string) =>
    apiFetch<ClinicalDailyCheckInSummary[]>(
      `/api/clinical/daily-check-ins?patientProfileId=${patientProfileId}&from=${from}&to=${to}`,
    ),
  getDailyCheckIn: (patientProfileId: number, date: string) =>
    apiFetch<ClinicalDailyCheckInDetail>(`/api/clinical/daily-check-ins/${patientProfileId}/${date}`),

  dailyTrend: (patientProfileId: number, from: string, to: string) =>
    apiFetch<DailyTrendResponse>(
      `/api/clinical/trends/daily?patientProfileId=${patientProfileId}&from=${from}&to=${to}`,
    ),

  listLabResultSets: (patientProfileId: number, from: string, to: string) =>
    apiFetch<LabResultSetResponse[]>(
      `/api/clinical/patients/${patientProfileId}/labs/result-sets?from=${from}&to=${to}`,
    ),
  getLabResultSet: (patientProfileId: number, id: number) =>
    apiFetch<LabResultSetResponse>(`/api/clinical/patients/${patientProfileId}/labs/result-sets/${id}`),
  createLabResultSet: (patientProfileId: number, req: LabResultSetRequest) =>
    apiFetch<LabResultSetResponse>(`/api/clinical/patients/${patientProfileId}/labs/result-sets`, {
      method: 'POST',
      body: req,
    }),
  updateLabResultSet: (patientProfileId: number, id: number, req: LabResultSetRequest) =>
    apiFetch<LabResultSetResponse>(`/api/clinical/patients/${patientProfileId}/labs/result-sets/${id}`, {
      method: 'PUT',
      body: req,
    }),
  requestLabRemoval: (patientProfileId: number, id: number, version: number, reason: string) =>
    apiFetch<{ status: string }>(`/api/clinical/patients/${patientProfileId}/labs/result-sets/${id}/removal`, {
      method: 'POST',
      body: { resultSetId: id, version, reason },
    }),
  labTrend: (patientProfileId: number, testCode: string, from: string, to: string) =>
    apiFetch<LabTrendResponse>(
      `/api/clinical/patients/${patientProfileId}/labs/trends/${encodeURIComponent(testCode)}?from=${from}&to=${to}`,
    ),

  currentRedFlags: (patientProfileId: number) =>
    apiFetch<ClinicalRedFlagSnapshot>(`/api/clinical/patients/${patientProfileId}/red-flags/current`),
  redFlagHistory: (patientProfileId: number, params: RedFlagHistoryParams) => {
    const query = new URLSearchParams()
    if (params.from) query.set('from', params.from)
    if (params.to) query.set('to', params.to)
    if (params.severity) query.set('severity', params.severity)
    if (params.cursor) query.set('cursor', params.cursor)
    if (params.size) query.set('size', String(params.size))
    const qs = query.toString()
    return apiFetch<ClinicalRedFlagHistoryPage>(
      `/api/clinical/patients/${patientProfileId}/red-flags/history${qs ? `?${qs}` : ''}`,
    )
  },

  listOnboardingSubmissions: (context?: string, status?: OnboardingReviewStatus) => {
    const query = new URLSearchParams()
    if (context) query.set('context', context)
    if (status) query.set('status', status)
    const qs = query.toString()
    return apiFetch<OnboardingSubmissionSummary[]>(`/api/clinical/onboarding/submissions${qs ? `?${qs}` : ''}`)
  },
  getOnboardingSubmission: (id: number) =>
    apiFetch<OnboardingSubmissionResponse>(`/api/clinical/onboarding/submissions/${id}`),
  reviewOnboardingSubmission: (id: number, req: OnboardingReviewRequest) =>
    apiFetch<OnboardingSubmissionResponse>(`/api/clinical/onboarding/submissions/${id}/review`, {
      method: 'POST',
      body: req,
    }),
}
```

- [ ] **Step 5: Add the i18n section**

In both `frontend/src/i18n/en.json` and `frontend/src/i18n/cs.json`, insert a `clinical`
section immediately after the `checkIn` section's closing brace (keep JSON valid; the
parity test `frontend/tests/i18n/locale.test.ts` enforces identical key sets).

`en.json`:

```json
  "clinical": {
    "navOverview": "Overview",
    "navReview": "Onboarding review",
    "overviewTitle": "Clinical overview",
    "overviewEmpty": "No patients are currently assigned to you.",
    "overviewQueueLink": "Open the onboarding review queue",
    "patientFallback": "Patient #{id}",
    "backToOverview": "Back to overview",
    "backToQueue": "Back to queue",
    "colPatient": "Patient",
    "colRedFlags": "Red flags",
    "colFlare": "Flare",
    "colKetones": "Ketones",
    "colAdherence": "Adherence",
    "colLastActivity": "Last activity",
    "colOnboarding": "Onboarding",
    "colDate": "Date",
    "colDietLog": "Diet log",
    "colSymptomCheckIn": "Symptom check-in",
    "colScore": "Score",
    "colSubmitted": "Submitted",
    "colVersion": "Version",
    "colStatus": "Status",
    "pendingReviews": "{count} awaiting review",
    "stale": "Stale",
    "noValue": "—",
    "tabCheckIns": "Check-ins",
    "tabTrends": "Trends",
    "tabLabs": "Labs",
    "tabRedFlags": "Red flags",
    "tabOnboarding": "Onboarding",
    "checkInsTitle": "Daily check-ins",
    "noDietLog": "No diet log for this day.",
    "noSymptomCheckIn": "No symptom check-in for this day.",
    "dayDetailTitle": "Check-in detail",
    "meals": "Meals",
    "deviations": "Deviations",
    "measurements": "Measurements",
    "photos": "Photos",
    "answers": "Answers",
    "queueTitle": "Onboarding review",
    "allStatuses": "All statuses",
    "openReview": "Review",
    "reviewTitle": "Review submission",
    "reviewDecision": "Decision",
    "reviewNotes": "Review notes",
    "submitReview": "Submit review",
    "alreadyReviewed": "This submission has already been reviewed.",
    "crpMgL": "CRP (mg/L)"
  },
```

`cs.json`:

```json
  "clinical": {
    "navOverview": "Přehled",
    "navReview": "Posouzení onboardingu",
    "overviewTitle": "Klinický přehled",
    "overviewEmpty": "Nemáte aktuálně přiřazené žádné pacienty.",
    "overviewQueueLink": "Otevřít frontu posouzení onboardingu",
    "patientFallback": "Pacient č. {id}",
    "backToOverview": "Zpět na přehled",
    "backToQueue": "Zpět na frontu",
    "colPatient": "Pacient",
    "colRedFlags": "Varovné signály",
    "colFlare": "Vzplanutí",
    "colKetones": "Ketony",
    "colAdherence": "Adherence",
    "colLastActivity": "Poslední aktivita",
    "colOnboarding": "Onboarding",
    "colDate": "Datum",
    "colDietLog": "Denník stravy",
    "colSymptomCheckIn": "Kontrola příznaků",
    "colScore": "Skóre",
    "colSubmitted": "Odesláno",
    "colVersion": "Verze",
    "colStatus": "Stav",
    "pendingReviews": "{count} čeká na posouzení",
    "stale": "Neaktuální",
    "noValue": "—",
    "tabCheckIns": "Kontroly",
    "tabTrends": "Trendy",
    "tabLabs": "Laboratoř",
    "tabRedFlags": "Varovné signály",
    "tabOnboarding": "Onboarding",
    "checkInsTitle": "Denní kontroly",
    "noDietLog": "Tento den nemá denník stravy.",
    "noSymptomCheckIn": "Tento den nemá kontrolu příznaků.",
    "dayDetailTitle": "Detail kontroly",
    "meals": "Jídla",
    "deviations": "Odchylky",
    "measurements": "Měření",
    "photos": "Fotografie",
    "answers": "Odpovědi",
    "queueTitle": "Posouzení onboardingu",
    "allStatuses": "Všechny stavy",
    "openReview": "Posoudit",
    "reviewTitle": "Posoudit žádost",
    "reviewDecision": "Rozhodnutí",
    "reviewNotes": "Poznámky k posouzení",
    "submitReview": "Odeslat posouzení",
    "alreadyReviewed": "Tato žádost již byla posouzena.",
    "crpMgL": "CRP (mg/L)"
  },
```

- [ ] **Step 6: Run tests to verify they pass**

Run: `cd frontend && npx vitest run tests/api/clinical.test.ts tests/i18n/locale.test.ts`
Expected: PASS. Also `npm run typecheck` — Expected: no errors.

- [ ] **Step 7: Commit**

```bash
git add frontend/src/types/api.ts frontend/src/api/clinical.ts \
        frontend/src/i18n/en.json frontend/src/i18n/cs.json \
        frontend/tests/api/clinical.test.ts
git commit -m "Add clinical API module, types, and i18n section"
```

---

### Task 5: Frontend — role-aware auth store, router guard, and routes

Wires the role model into the SPA: `canAccessClinical` + `homePath` on the auth store,
role-meta guard, the full `/clinical` route tree, and role-aware post-login navigation.
The view components referenced by the routes are created by later tasks — this task
creates minimal placeholder-free stubs only where the task ordering requires it; in
practice, implement Tasks 5 and 6 before any view task and let the router import the
real views as they land (each view task runs its own tests; route-level tests here use
stub routes of their own).

Note: the router file imports the view components statically. Until Tasks 7–16 create the
real views, keep the clinical children pointing at `ClinicalStubView` (created below) and
swap each import in as its view task lands. Each view task states the exact import swap.

**Files:**
- Modify: `frontend/src/stores/auth.ts`
- Modify: `frontend/src/router/index.ts`
- Modify: `frontend/src/views/LoginView.vue:26-27`
- Create: `frontend/src/views/clinical/ClinicalStubView.vue` (temporary; deleted by Task 16)
- Test: `frontend/tests/router/guards.test.ts`

**Interfaces:**
- Consumes: existing `useAuthStore`, route records.
- Produces: `CLINICAL_ROLES` (`['NUTRITION_SPECIALIST', 'PHYSICIAN', 'ADMIN']`, exported from `stores/auth.ts`); auth computeds `canAccessClinical: boolean`, `homePath: '/' | '/clinical' | '/staff-notice'`; route meta flag `roles?: string[]`; clinical route paths used by every later task:
  `/clinical`, `/clinical/onboarding`, `/clinical/onboarding/:submissionId`, `/clinical/education`, `/clinical/education/:moduleSlug`, `/clinical/patients/:patientProfileId/{check-ins,check-ins/:date,trends,labs,labs/new,labs/:resultSetId,red-flags,onboarding}`.

- [ ] **Step 1: Write the failing guard tests**

Replace the existing `redirects non-patient staff to /staff-notice` test in
`frontend/tests/router/guards.test.ts` (a `PHYSICIAN` no longer lands there — that is the
intended behavior change) and add the role-matrix tests:

```ts
  it('redirects coordinator staff to /staff-notice', async () => {
    server.use(http.get('/api/auth/me', () => HttpResponse.json({ email: 'c@example.com', roles: ['COORDINATOR'] })))
    const router = makeRouter()
    await router.push('/diet-logs')
    expect(router.currentRoute.value.path).toBe('/staff-notice')
  })

  it('redirects clinical experts from patient routes to /clinical', async () => {
    server.use(http.get('/api/auth/me', () => HttpResponse.json({ email: 'd@example.com', roles: ['PHYSICIAN'] })))
    const router = makeRouter()
    await router.push('/diet-logs')
    expect(router.currentRoute.value.path).toBe('/clinical')
  })

  it('lets clinical experts into the clinical area', async () => {
    server.use(http.get('/api/auth/me', () => HttpResponse.json({ email: 'n@example.com', roles: ['NUTRITION_SPECIALIST'] })))
    const router = makeRouter()
    await router.push('/clinical')
    expect(router.currentRoute.value.path).toBe('/clinical')
  })

  it('lets admins into the clinical area', async () => {
    server.use(http.get('/api/auth/me', () => HttpResponse.json({ email: 'a@example.com', roles: ['ADMIN'] })))
    const router = makeRouter()
    await router.push('/clinical')
    expect(router.currentRoute.value.path).toBe('/clinical')
  })

  it('keeps patients out of the clinical area', async () => {
    server.use(http.get('/api/auth/me', () => HttpResponse.json({ email: 'p@example.com', roles: ['PATIENT'] })))
    const router = makeRouter()
    await router.push('/clinical')
    expect(router.currentRoute.value.path).toBe('/')
  })

  it('keeps coordinators out of the clinical area', async () => {
    server.use(http.get('/api/auth/me', () => HttpResponse.json({ email: 'c@example.com', roles: ['COORDINATOR'] })))
    const router = makeRouter()
    await router.push('/clinical')
    expect(router.currentRoute.value.path).toBe('/staff-notice')
  })

  it('sends experts away from /login to /clinical', async () => {
    server.use(http.get('/api/auth/me', () => HttpResponse.json({ email: 'd@example.com', roles: ['PHYSICIAN'] })))
    const router = makeRouter()
    await router.push('/login')
    expect(router.currentRoute.value.path).toBe('/clinical')
  })
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd frontend && npx vitest run tests/router/guards.test.ts`
Expected: FAIL — experts still redirect to `/staff-notice` and `/clinical` does not exist
as a guarded route.

- [ ] **Step 3: Extend the auth store**

In `frontend/src/stores/auth.ts`, add above the store definition:

```ts
export const CLINICAL_ROLES = ['NUTRITION_SPECIALIST', 'PHYSICIAN', 'ADMIN']
```

Inside the store, after the `isPatient` computed:

```ts
  const canAccessClinical = computed(() => roles.value.some((role) => CLINICAL_ROLES.includes(role)))
  const homePath = computed(() => {
    if (isPatient.value) return '/'
    return canAccessClinical.value ? '/clinical' : '/staff-notice'
  })
```

Add `canAccessClinical` and `homePath` to the returned object.

- [ ] **Step 4: Rewrite the guard and add the clinical routes**

In `frontend/src/router/index.ts`, replace `installAuthGuard` with:

```ts
export function installAuthGuard(router: Router): void {
  router.beforeEach(async (to) => {
    const auth = useAuthStore()
    if (auth.status === 'unknown') {
      await auth.fetchMe()
    }
    if (to.meta.requiresAuth && !auth.isAuthenticated) {
      return { path: '/login', query: { redirect: to.fullPath } }
    }
    if (to.path === '/login' && auth.isAuthenticated) {
      return { path: auth.homePath }
    }
    if (to.meta.requiresAuth && auth.isAuthenticated) {
      const requiredRoles = to.meta.roles as string[] | undefined
      if (requiredRoles !== undefined) {
        // Route meta merges across matched records, so children inherit the parent's roles.
        return requiredRoles.some((role) => auth.roles.includes(role))
          ? true
          : { path: auth.isPatient ? '/' : '/staff-notice' }
      }
      if (!to.meta.allowStaff && !auth.isPatient) {
        return { path: auth.homePath }
      }
    }
    return true
  })
}
```

Add the clinical route tree to `routes` (before the `/staff-notice` record). Import
`ClinicalShell` (created in Task 6 — for this task's test run, point the parent at the
stub below) and the view components; per the task note, use the stub until each view
lands:

```ts
import ClinicalStubView from '@/views/clinical/ClinicalStubView.vue'
```

```ts
  {
    path: '/clinical',
    component: ClinicalStubView, // Task 6 swaps in ClinicalShell
    meta: { requiresAuth: true, roles: CLINICAL_ROLES },
    children: [
      { path: '', component: ClinicalStubView },
      { path: 'onboarding', component: ClinicalStubView },
      { path: 'onboarding/:submissionId', component: ClinicalStubView },
      { path: 'education', component: EducationListView },
      { path: 'education/:moduleSlug', component: EducationModuleView },
      {
        path: 'patients/:patientProfileId',
        component: ClinicalStubView,
        children: [
          { path: '', redirect: (to) => `/clinical/patients/${to.params.patientProfileId}/check-ins` },
          { path: 'check-ins', component: ClinicalStubView },
          { path: 'check-ins/:date', component: ClinicalStubView },
          { path: 'trends', component: ClinicalStubView },
          { path: 'labs', component: ClinicalStubView },
          { path: 'labs/new', component: ClinicalStubView },
          { path: 'labs/:resultSetId', component: ClinicalStubView },
          { path: 'red-flags', component: ClinicalStubView },
          { path: 'onboarding', component: ClinicalStubView },
        ],
      },
    ],
  },
```

Import `CLINICAL_ROLES` from `@/stores/auth` alongside `useAuthStore`.

Create `frontend/src/views/clinical/ClinicalStubView.vue`:

```vue
<template>
  <router-view />
</template>
```

- [ ] **Step 5: Make LoginView role-aware**

In `frontend/src/views/LoginView.vue`, replace:

```ts
    const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : '/'
    await router.push(redirect)
```

with:

```ts
    const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : null
    await router.push(redirect ?? auth.homePath)
```

- [ ] **Step 6: Run tests to verify they pass**

Run: `cd frontend && npx vitest run tests/router tests/views tests/stores`
Expected: PASS (existing login-view tests log in as a patient, so `homePath` stays `/`;
the replaced and new guard tests cover the role matrix). Also `npm run typecheck`.

- [ ] **Step 7: Commit**

```bash
git add frontend/src/stores/auth.ts frontend/src/router/index.ts \
        frontend/src/views/LoginView.vue frontend/src/views/clinical/ClinicalStubView.vue \
        frontend/tests/router/guards.test.ts
git commit -m "Add role-aware routing with clinical area skeleton"
```

---

### Task 6: Frontend — ClinicalShell and shared education library

The expert chrome: nav (Overview, Onboarding review, Education), locale/theme selects,
logout — `AppShell` minus the patient red-flag banner and patient nav. Also makes the
published education library reachable from the clinical area, read-only for staff.

**Files:**
- Create: `frontend/src/components/ClinicalShell.vue`
- Modify: `frontend/src/router/index.ts` (swap the `/clinical` parent component and add real education routes — the education children already point at the real views from Task 5)
- Modify: `frontend/src/views/EducationListView.vue` (route-aware link base)
- Modify: `frontend/src/views/EducationModuleView.vue` (route-aware back link, patient-only completion toggle)
- Test: `frontend/tests/components/ClinicalShell.test.ts`
- Modify: `frontend/tests/views/EducationModuleView.test.ts` (seed patient role for the completion test; add staff test)

**Interfaces:**
- Consumes: `CLINICAL_ROLES` route tree (Task 5); `useAuthStore().isPatient`; `accountApi` preference endpoints; `setLocale`/`setTheme`.
- Produces: `ClinicalShell` component used as the `/clinical` layout; education views usable under both `/education` and `/clinical/education` bases.

- [ ] **Step 1: Write the failing shell test**

Follows `frontend/tests/components/AppShell.test.ts` (matchMedia stub, memory router).

```ts
import { mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createMemoryHistory } from 'vue-router'
import { createI18n } from 'vue-i18n'
import ClinicalShell from '@/components/ClinicalShell.vue'
import en from '@/i18n/en.json'

Object.defineProperty(window, 'matchMedia', {
  writable: true,
  configurable: true,
  value: (query: string) => ({
    matches: false,
    media: query,
    addEventListener: () => undefined,
    removeEventListener: () => undefined,
  }),
})

describe('ClinicalShell', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    localStorage.clear()
    document.documentElement.classList.remove('dark')
  })

  it('renders the clinical nav without a red-flag banner', async () => {
    const stub = { template: '<div />' }
    const router = createRouter({
      history: createMemoryHistory(),
      routes: ['/clinical', '/clinical/onboarding', '/clinical/education'].map((path) => ({
        path,
        component: stub,
      })),
    })
    await router.push('/clinical')
    const i18n = createI18n({ legacy: false, locale: 'en', messages: { en } })
    const wrapper = mount(ClinicalShell, { global: { plugins: [createPinia(), i18n, router] } })

    expect(wrapper.text()).toContain(en.clinical.navOverview)
    expect(wrapper.text()).toContain(en.clinical.navReview)
    expect(wrapper.text()).toContain(en.nav.education)
    expect(wrapper.html()).toContain('href="/clinical/onboarding"')
    expect(wrapper.find('[data-testid="red-flag-banner"]').exists()).toBe(false)
  })
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && npx vitest run tests/components/ClinicalShell.test.ts`
Expected: FAIL — `ClinicalShell.vue` does not exist.

- [ ] **Step 3: Write ClinicalShell**

```vue
<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { useAuthStore } from '@/stores/auth'
import { setLocale, type AppLocale } from '@/i18n'
import { setTheme, currentTheme, type ThemePreference } from '@/theme'
import { accountApi } from '@/api/account'

const { t, locale } = useI18n()
const auth = useAuthStore()
const router = useRouter()

const links = computed(() => [
  { to: '/clinical', label: t('clinical.navOverview') },
  { to: '/clinical/onboarding', label: t('clinical.navReview') },
  { to: '/clinical/education', label: t('nav.education') },
])

async function switchLocale(event: Event) {
  const next = (event.target as HTMLSelectElement).value as AppLocale
  setLocale(next)
  try {
    await accountApi.updateLanguagePreference(next === 'cs' ? 'CS' : 'EN')
  } catch {
    // Preference persistence is best-effort; the local choice still applies.
  }
}

const theme = ref<ThemePreference>(currentTheme())

async function switchTheme() {
  setTheme(theme.value)
  try {
    await accountApi.updateThemePreference(theme.value)
  } catch {
    // Preference persistence is best-effort; the local choice still applies.
  }
}

async function logout() {
  try {
    await auth.logout()
  } catch {
    // Local auth state is already cleared; a failed request must not strand the
    // user on an authenticated page. The server session expires on its own.
  } finally {
    await router.push('/login')
  }
}
</script>

<template>
  <div class="min-h-screen bg-gray-50 dark:bg-gray-900">
    <header class="border-b bg-white dark:border-gray-700 dark:bg-gray-800">
      <div class="mx-auto flex max-w-5xl items-center gap-4 px-4 py-3">
        <span class="text-lg font-semibold">{{ t('app.title') }}</span>
        <nav class="flex flex-1 flex-wrap gap-3 text-sm">
          <router-link v-for="link in links" :key="link.to" :to="link.to"
                       class="text-gray-700 hover:text-blue-700 dark:text-gray-300 dark:hover:text-blue-300"
                       :active-class="link.to === '/clinical' ? '' : 'font-semibold text-blue-700 dark:text-blue-300'"
                       exact-active-class="font-semibold text-blue-700 dark:text-blue-300">
            {{ link.label }}
          </router-link>
        </nav>
        <select :value="locale" :aria-label="t('nav.language')"
                class="rounded border border-gray-300 px-2 py-1 text-sm dark:border-gray-600 dark:bg-gray-800" @change="switchLocale">
          <option value="en">EN</option>
          <option value="cs">CS</option>
        </select>
        <select v-model="theme" :aria-label="t('theme.label')"
                class="rounded border border-gray-300 px-2 py-1 text-sm dark:border-gray-600 dark:bg-gray-800"
                @change="switchTheme">
          <option value="SYSTEM">{{ t('theme.system') }}</option>
          <option value="LIGHT">{{ t('theme.light') }}</option>
          <option value="DARK">{{ t('theme.dark') }}</option>
        </select>
        <button class="text-sm text-gray-700 hover:text-blue-700 dark:text-gray-300 dark:hover:text-blue-300" @click="logout">{{ t('nav.logout') }}</button>
      </div>
    </header>
    <main class="mx-auto max-w-5xl px-4 py-6">
      <router-view />
    </main>
  </div>
</template>
```

- [ ] **Step 4: Point the /clinical parent at ClinicalShell**

In `frontend/src/router/index.ts`, replace the `/clinical` parent's
`component: ClinicalStubView, // Task 6 swaps in ClinicalShell` with:

```ts
    component: ClinicalShell,
```

and add the import:

```ts
import ClinicalShell from '@/components/ClinicalShell.vue'
```

(The stub stays in use for the not-yet-built child views.)

- [ ] **Step 5: Make the education views base-aware and staff read-only**

In `frontend/src/views/EducationListView.vue`, add to the script:

```ts
import { computed } from 'vue' // merge with the existing vue import
import { useRoute } from 'vue-router'

const route = useRoute()
const educationBase = computed(() => (route.path.startsWith('/clinical') ? '/clinical/education' : '/education'))
```

and change the module link to:

```vue
      <router-link v-for="m in modules" :key="m.moduleSlug" :to="`${educationBase}/${m.moduleSlug}`"
```

In `frontend/src/views/EducationModuleView.vue`, add to the script:

```ts
import { computed } from 'vue' // merge with the existing vue import
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const educationBase = computed(() => (route.path.startsWith('/clinical') ? '/clinical/education' : '/education'))
```

change the back link to:

```vue
    <router-link :to="educationBase" class="text-sm text-blue-600 dark:text-blue-400">← {{ t('education.backToModules') }}</router-link>
```

and gate the completion toggle (staff read the library; completion is patient-only on
the API and would 403):

```vue
            <button v-if="auth.isPatient" :data-testid="`lesson-toggle-${lesson.lessonSlug}`"
                    class="mt-4 rounded border px-3 py-1 text-sm"
                    @click="toggleLesson(lesson)">
```

- [ ] **Step 6: Update the education module tests**

In `frontend/tests/views/EducationModuleView.test.ts`, the completion test mounts with a
fresh `createPinia()` whose auth store has no roles — the toggle is now patient-gated, so
seed the patient role. Add `import { useAuthStore } from '@/stores/auth'` at the top of
the file and replace the mount line in
`renders lesson content and toggles completion`:

```ts
    const pinia = createPinia()
    const wrapper = mount(EducationModuleView, { global: { plugins: [pinia, i18n, router] } })
    useAuthStore(pinia).roles = ['PATIENT']
```

Add a staff test at the end of the describe block:

```ts
  it('hides the completion toggle for staff roles', async () => {
    server.use(http.get('/api/education/modules/ibd-basics', () => HttpResponse.json(moduleDetail)))
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/clinical/education/:moduleSlug', component: EducationModuleView }],
    })
    await router.push('/clinical/education/ibd-basics')
    const pinia = createPinia()
    const wrapper = mount(EducationModuleView, { global: { plugins: [pinia, i18n, router] } })
    useAuthStore(pinia).roles = ['PHYSICIAN']
    await flushPromises()

    expect(wrapper.text()).toContain('IBD Basics')
    expect(wrapper.find('[data-testid="lesson-toggle-what-is-ibd"]').exists()).toBe(false)
    expect(wrapper.find('a[href="/clinical/education"]').exists()).toBe(true)
  })
```

- [ ] **Step 7: Run tests to verify they pass**

Run: `cd frontend && npx vitest run tests/components/ClinicalShell.test.ts tests/views/EducationModuleView.test.ts tests/views/EducationListView.test.ts tests/router`
Expected: PASS. Also `npm run typecheck`.

- [ ] **Step 8: Commit**

```bash
git add frontend/src/components/ClinicalShell.vue frontend/src/router/index.ts \
        frontend/src/views/EducationListView.vue frontend/src/views/EducationModuleView.vue \
        frontend/tests/components/ClinicalShell.test.ts \
        frontend/tests/views/EducationModuleView.test.ts
git commit -m "Add clinical shell and share education library with staff"
```

---

### Task 7: Frontend — clinical overview view

The monitor-first landing page: one row per monitored patient with attention signals,
sorted needs-attention-first client-side. Row click opens the patient workspace and
passes the patient email along as a query param (the workspace header uses it; survives
reload, no extra endpoint).

**Files:**
- Create: `frontend/src/views/clinical/ClinicalOverviewView.vue`
- Modify: `frontend/src/router/index.ts` (swap the `/clinical` index child from `ClinicalStubView` to the real view)
- Test: `frontend/tests/views/clinical/ClinicalOverviewView.test.ts`

**Interfaces:**
- Consumes: `clinicalApi.overview()` → `ClinicalPatientOverview[]` (Task 4); i18n `clinical.*` (Task 4); `severityBadgeClass` from `@/utils/redFlags`.
- Produces: navigation convention `/clinical/patients/{id}/check-ins?email={email}` consumed by Task 8's workspace header.

- [ ] **Step 1: Write the failing view test**

```ts
import { flushPromises, mount } from '@vue/test-utils'
import { http, HttpResponse } from 'msw'
import { beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { createI18n } from 'vue-i18n'
import { createRouter, createMemoryHistory } from 'vue-router'
import { server } from '../../msw/server'
import ClinicalOverviewView from '@/views/clinical/ClinicalOverviewView.vue'
import en from '@/i18n/en.json'

const i18n = createI18n({ legacy: false, locale: 'en', messages: { en } })

function isoDaysAgo(days: number): string {
  const d = new Date()
  d.setDate(d.getDate() - days)
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

const rows = [
  {
    patientProfileId: 1,
    patientEmail: 'ok@example.com',
    currentRedFlagCount: 0,
    highestRedFlagSeverity: null,
    latestFlareState: 'NO_FLARE',
    latestSymptomScore: 2,
    latestSymptomCheckInDate: isoDaysAgo(0),
    latestKetoneValue: 1.2,
    latestKetoneUnit: 'MMOL_L',
    latestKetoneMeasuredAt: '2026-08-04T06:30:00Z',
    latestAdherenceLevel: 'FULL',
    lastActivityDate: isoDaysAgo(0),
    pendingOnboardingCount: 0,
  },
  {
    patientProfileId: 2,
    patientEmail: 'flagged@example.com',
    currentRedFlagCount: 2,
    highestRedFlagSeverity: 'EMERGENCY',
    latestFlareState: 'ACTIVE_FLARE',
    latestSymptomScore: 9,
    latestSymptomCheckInDate: isoDaysAgo(1),
    latestKetoneValue: null,
    latestKetoneUnit: null,
    latestKetoneMeasuredAt: null,
    latestAdherenceLevel: 'PARTIAL',
    lastActivityDate: isoDaysAgo(1),
    pendingOnboardingCount: 1,
  },
  {
    patientProfileId: 3,
    patientEmail: 'stale@example.com',
    currentRedFlagCount: 0,
    highestRedFlagSeverity: null,
    latestFlareState: 'NO_FLARE',
    latestSymptomScore: 1,
    latestSymptomCheckInDate: isoDaysAgo(9),
    latestKetoneValue: 0.8,
    latestKetoneUnit: 'MMOL_L',
    latestKetoneMeasuredAt: '2026-07-26T06:30:00Z',
    latestAdherenceLevel: 'MOSTLY',
    lastActivityDate: isoDaysAgo(9),
    pendingOnboardingCount: 0,
  },
]

function makeRouter() {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/clinical', component: ClinicalOverviewView },
      { path: '/clinical/patients/:patientProfileId/check-ins', component: { template: '<div />' } },
    ],
  })
}

describe('ClinicalOverviewView', () => {
  beforeEach(() => setActivePinia(createPinia()))

  it('sorts needs-attention-first and renders badges', async () => {
    server.use(http.get('/api/clinical/overview', () => HttpResponse.json(rows)))
    const router = makeRouter()
    await router.push('/clinical')
    const wrapper = mount(ClinicalOverviewView, { global: { plugins: [createPinia(), i18n, router] } })
    await flushPromises()

    const emails = wrapper.findAll('[data-testid="overview-row"]').map((r) => r.attributes('data-email'))
    expect(emails).toEqual(['flagged@example.com', 'stale@example.com', 'ok@example.com'])

    const flagged = wrapper.find('[data-testid="overview-row"][data-email="flagged@example.com"]')
    expect(flagged.text()).toContain(en.redFlags.severity.EMERGENCY)
    expect(flagged.text()).toContain(en.clinical.pendingReviews.replace('{count}', '1'))

    const stale = wrapper.find('[data-testid="overview-row"][data-email="stale@example.com"]')
    expect(stale.text()).toContain(en.clinical.stale)
  })

  it('navigates to the patient workspace with the email query param', async () => {
    server.use(http.get('/api/clinical/overview', () => HttpResponse.json(rows)))
    const router = makeRouter()
    await router.push('/clinical')
    const wrapper = mount(ClinicalOverviewView, { global: { plugins: [createPinia(), i18n, router] } })
    await flushPromises()

    await wrapper.find('[data-testid="overview-row"][data-email="flagged@example.com"]').trigger('click')
    expect(router.currentRoute.value.path).toBe('/clinical/patients/2/check-ins')
    expect(router.currentRoute.value.query.email).toBe('flagged@example.com')
  })

  it('shows the empty state with a link to the review queue', async () => {
    server.use(http.get('/api/clinical/overview', () => HttpResponse.json([])))
    const router = makeRouter()
    await router.push('/clinical')
    const wrapper = mount(ClinicalOverviewView, { global: { plugins: [createPinia(), i18n, router] } })
    await flushPromises()

    expect(wrapper.text()).toContain(en.clinical.overviewEmpty)
    expect(wrapper.html()).toContain('href="/clinical/onboarding"')
  })
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && npx vitest run tests/views/clinical/ClinicalOverviewView.test.ts`
Expected: FAIL — the view does not exist.

- [ ] **Step 3: Write the view**

```vue
<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { clinicalApi } from '@/api/clinical'
import { useApiError } from '@/composables/useApiError'
import { severityBadgeClass } from '@/utils/redFlags'
import type { ClinicalPatientOverview, FlareState, RedFlagSeverity } from '@/types/api'

const { t } = useI18n()
const { message, capture } = useApiError()
const router = useRouter()

const rows = ref<ClinicalPatientOverview[]>([])
const loading = ref(true)

function iso(d: Date): string {
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

// Logging is daily; anything quieter than 2 days is stale.
function isStale(lastActivityDate: string | null): boolean {
  if (lastActivityDate === null) return true
  const cutoff = new Date()
  cutoff.setDate(cutoff.getDate() - 2)
  return lastActivityDate < iso(cutoff)
}

const SEVERITY_RANK: Record<RedFlagSeverity, number> = {
  EMERGENCY: 0,
  URGENT_REVIEW: 1,
  ROUTINE_REVIEW: 2,
}
const FLARE_RANK: Partial<Record<FlareState, number>> = {
  ACTIVE_FLARE: 3,
  SUSPECTED_FLARE: 4,
}

function rank(row: ClinicalPatientOverview): number {
  if (row.highestRedFlagSeverity) return SEVERITY_RANK[row.highestRedFlagSeverity]
  if (row.latestFlareState && row.latestFlareState !== 'NO_FLARE') return FLARE_RANK[row.latestFlareState] ?? 5
  if (isStale(row.lastActivityDate)) return 6
  return 7
}

const sortedRows = computed(() =>
  [...rows.value].sort((a, b) => rank(a) - rank(b) || a.patientEmail.localeCompare(b.patientEmail)),
)

function ketones(row: ClinicalPatientOverview): string {
  if (row.latestKetoneValue === null) return t('clinical.noValue')
  return `${row.latestKetoneValue} ${t(`enums.MeasurementUnit.${row.latestKetoneUnit}`)}`
}

function open(row: ClinicalPatientOverview) {
  void router.push({
    path: `/clinical/patients/${row.patientProfileId}/check-ins`,
    query: { email: row.patientEmail },
  })
}

onMounted(async () => {
  try {
    rows.value = await clinicalApi.overview()
  } catch (e) {
    capture(e)
  } finally {
    loading.value = false
  }
})
</script>

<template>
  <section>
    <h1 class="text-2xl font-semibold">{{ t('clinical.overviewTitle') }}</h1>

    <p v-if="message" class="mt-4 rounded bg-red-50 p-3 text-sm text-red-700 dark:bg-red-950 dark:text-red-300">{{ message }}</p>
    <p v-if="loading" class="mt-4">{{ t('common.loading') }}</p>
    <template v-else>
      <div v-if="sortedRows.length === 0" class="mt-4">
        <p class="text-sm text-gray-600 dark:text-gray-400">{{ t('clinical.overviewEmpty') }}</p>
        <router-link to="/clinical/onboarding" class="mt-2 inline-block text-sm text-blue-600 dark:text-blue-400">
          {{ t('clinical.overviewQueueLink') }}
        </router-link>
      </div>
      <table v-else class="mt-4 w-full border-collapse bg-white text-sm dark:bg-gray-800">
        <thead>
          <tr class="border-b text-left">
            <th class="p-2">{{ t('clinical.colPatient') }}</th>
            <th class="p-2">{{ t('clinical.colRedFlags') }}</th>
            <th class="p-2">{{ t('clinical.colFlare') }}</th>
            <th class="p-2">{{ t('clinical.colKetones') }}</th>
            <th class="p-2">{{ t('clinical.colAdherence') }}</th>
            <th class="p-2">{{ t('clinical.colLastActivity') }}</th>
            <th class="p-2">{{ t('clinical.colOnboarding') }}</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="row in sortedRows" :key="row.patientProfileId"
              data-testid="overview-row" :data-email="row.patientEmail"
              class="cursor-pointer border-b hover:bg-gray-50 dark:hover:bg-gray-700"
              @click="open(row)">
            <td class="p-2">{{ row.patientEmail }}</td>
            <td class="p-2">
              <span v-if="row.highestRedFlagSeverity" class="rounded px-2 py-0.5"
                    :class="severityBadgeClass(row.highestRedFlagSeverity)">
                {{ t(`redFlags.severity.${row.highestRedFlagSeverity}`) }} ({{ row.currentRedFlagCount }})
              </span>
              <span v-else>{{ t('clinical.noValue') }}</span>
            </td>
            <td class="p-2">
              {{ row.latestFlareState ? t(`checkIn.FlareState.${row.latestFlareState}`) : t('clinical.noValue') }}
            </td>
            <td class="p-2">{{ ketones(row) }}</td>
            <td class="p-2">
              {{ row.latestAdherenceLevel ? t(`enums.DietAdherenceLevel.${row.latestAdherenceLevel}`) : t('clinical.noValue') }}
            </td>
            <td class="p-2">
              {{ row.lastActivityDate ?? t('clinical.noValue') }}
              <span v-if="isStale(row.lastActivityDate)"
                    class="ml-1 rounded bg-amber-100 px-2 py-0.5 text-amber-800 dark:bg-amber-950 dark:text-amber-200">
                {{ t('clinical.stale') }}
              </span>
            </td>
            <td class="p-2">
              <span v-if="row.pendingOnboardingCount > 0"
                    class="rounded bg-blue-100 px-2 py-0.5 text-blue-800 dark:bg-blue-950 dark:text-blue-200">
                {{ t('clinical.pendingReviews', { count: row.pendingOnboardingCount }) }}
              </span>
              <span v-else>{{ t('clinical.noValue') }}</span>
            </td>
          </tr>
        </tbody>
      </table>
    </template>
  </section>
</template>
```

- [ ] **Step 4: Swap the route to the real view**

In `frontend/src/router/index.ts`, replace the clinical index child
`{ path: '', component: ClinicalStubView },` with:

```ts
      { path: '', component: ClinicalOverviewView },
```

and add `import ClinicalOverviewView from '@/views/clinical/ClinicalOverviewView.vue'`.

- [ ] **Step 5: Run tests to verify they pass**

Run: `cd frontend && npx vitest run tests/views/clinical/ClinicalOverviewView.test.ts tests/router`
Expected: PASS. Also `npm run typecheck`.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/views/clinical/ClinicalOverviewView.vue \
        frontend/src/router/index.ts \
        frontend/tests/views/clinical/ClinicalOverviewView.test.ts
git commit -m "Add clinical overview view"
```

---

### Task 8: Frontend — patient workspace layout (tabs)

The drill-down shell for one patient: back link, patient identity header (email from the
`?email=` query param, localized fallback), tab nav that preserves the query, and a
nested `<router-view>` for the tab views of Tasks 9–16.

**Files:**
- Create: `frontend/src/views/clinical/ClinicalPatientWorkspaceView.vue`
- Modify: `frontend/src/router/index.ts` (swap the `patients/:patientProfileId` component from `ClinicalStubView`)
- Test: `frontend/tests/views/clinical/ClinicalPatientWorkspaceView.test.ts`

**Interfaces:**
- Consumes: route params `patientProfileId`, query `email` (Task 7's convention).
- Produces: tab paths relative to `/clinical/patients/{id}/` consumed by Tasks 9–16; query-preservation convention for tab links.

- [ ] **Step 1: Write the failing test**

```ts
import { mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { createI18n } from 'vue-i18n'
import { createRouter, createMemoryHistory } from 'vue-router'
import ClinicalPatientWorkspaceView from '@/views/clinical/ClinicalPatientWorkspaceView.vue'
import en from '@/i18n/en.json'

const i18n = createI18n({ legacy: false, locale: 'en', messages: { en } })

function makeRouter() {
  const stub = { template: '<div />' }
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/clinical', component: stub },
      {
        path: '/clinical/patients/:patientProfileId',
        component: ClinicalPatientWorkspaceView,
        children: ['check-ins', 'trends', 'labs', 'red-flags', 'onboarding'].map((path) => ({
          path,
          component: stub,
        })),
      },
    ],
  })
}

describe('ClinicalPatientWorkspaceView', () => {
  beforeEach(() => setActivePinia(createPinia()))

  it('renders the patient email header and query-preserving tabs', async () => {
    const router = makeRouter()
    await router.push('/clinical/patients/41/check-ins?email=patient%40example.com')
    const wrapper = mount(ClinicalPatientWorkspaceView, { global: { plugins: [createPinia(), i18n, router] } })

    expect(wrapper.text()).toContain('patient@example.com')
    const html = wrapper.html()
    expect(html).toContain('href="/clinical/patients/41/trends?email=patient%40example.com"')
    expect(html).toContain('href="/clinical/patients/41/red-flags?email=patient%40example.com"')
    expect(html).toContain('href="/clinical"')
  })

  it('falls back to a localized patient label without the email param', async () => {
    const router = makeRouter()
    await router.push('/clinical/patients/41/check-ins')
    const wrapper = mount(ClinicalPatientWorkspaceView, { global: { plugins: [createPinia(), i18n, router] } })

    expect(wrapper.text()).toContain(en.clinical.patientFallback.replace('{id}', '41'))
  })
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && npx vitest run tests/views/clinical/ClinicalPatientWorkspaceView.test.ts`
Expected: FAIL — the view does not exist.

- [ ] **Step 3: Write the view**

```vue
<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { useI18n } from 'vue-i18n'

const { t } = useI18n()
const route = useRoute()

const patientProfileId = computed(() => Number(route.params.patientProfileId))
const patientEmail = computed(() => {
  const email = route.query.email
  return typeof email === 'string' && email.length > 0 ? email : null
})

const tabs = computed(() => {
  const base = `/clinical/patients/${patientProfileId.value}`
  return [
    { to: `${base}/check-ins`, label: t('clinical.tabCheckIns') },
    { to: `${base}/trends`, label: t('clinical.tabTrends') },
    { to: `${base}/labs`, label: t('clinical.tabLabs') },
    { to: `${base}/red-flags`, label: t('clinical.tabRedFlags') },
    { to: `${base}/onboarding`, label: t('clinical.tabOnboarding') },
  ]
})
</script>

<template>
  <section>
    <router-link to="/clinical" class="text-sm text-blue-600 dark:text-blue-400">← {{ t('clinical.backToOverview') }}</router-link>
    <h1 class="mt-2 text-2xl font-semibold">
      {{ patientEmail ?? t('clinical.patientFallback', { id: patientProfileId }) }}
    </h1>
    <nav class="mt-4 flex flex-wrap gap-3 border-b pb-2 text-sm dark:border-gray-700">
      <router-link v-for="tab in tabs" :key="tab.to"
                   :to="{ path: tab.to, query: route.query }"
                   class="text-gray-700 hover:text-blue-700 dark:text-gray-300 dark:hover:text-blue-300"
                   active-class="font-semibold text-blue-700 dark:text-blue-300">
        {{ tab.label }}
      </router-link>
    </nav>
    <router-view />
  </section>
</template>
```

- [ ] **Step 4: Swap the route to the real view**

In `frontend/src/router/index.ts`, replace the workspace record's
`component: ClinicalStubView,` with:

```ts
        component: ClinicalPatientWorkspaceView,
```

and add `import ClinicalPatientWorkspaceView from '@/views/clinical/ClinicalPatientWorkspaceView.vue'`.

- [ ] **Step 5: Run tests to verify they pass**

Run: `cd frontend && npx vitest run tests/views/clinical/ClinicalPatientWorkspaceView.test.ts tests/router`
Expected: PASS. Also `npm run typecheck`.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/views/clinical/ClinicalPatientWorkspaceView.vue \
        frontend/src/router/index.ts \
        frontend/tests/views/clinical/ClinicalPatientWorkspaceView.test.ts
git commit -m "Add clinical patient workspace layout"
```

---

### Task 9: Frontend — check-ins tab (merged daily list)

Date-ranged list of merged diet-log + symptom-check-in days for one patient (default
last 7 days, like the Thymeleaf page). Row click opens the day detail (Task 10).

**Files:**
- Create: `frontend/src/views/clinical/ClinicalCheckInsView.vue`
- Modify: `frontend/src/router/index.ts` (swap the `check-ins` child from `ClinicalStubView`)
- Test: `frontend/tests/views/clinical/ClinicalCheckInsView.test.ts`

**Interfaces:**
- Consumes: `clinicalApi.listDailyCheckIns(patientProfileId, from, to)` (Task 4); `dateRangeError` from `@/utils/dateRange`.
- Produces: day-detail navigation `/clinical/patients/{id}/check-ins/{date}?email=…` consumed by Task 10.

- [ ] **Step 1: Write the failing test**

```ts
import { flushPromises, mount } from '@vue/test-utils'
import { http, HttpResponse } from 'msw'
import { beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { createI18n } from 'vue-i18n'
import { createRouter, createMemoryHistory } from 'vue-router'
import { server } from '../../msw/server'
import ClinicalCheckInsView from '@/views/clinical/ClinicalCheckInsView.vue'
import en from '@/i18n/en.json'

const i18n = createI18n({ legacy: false, locale: 'en', messages: { en } })

const summaries = [
  {
    patientProfileId: 41,
    patientEmail: 'patient@example.com',
    date: '2026-08-03',
    dietLogId: 7,
    adherenceLevel: 'FULL',
    appetiteLevel: 'NORMAL',
    mealCount: 3,
    deviationCount: 0,
    measurementCount: 2,
    symptomCheckInId: 9,
    symptomScore: 4,
    flareState: 'NO_FLARE',
  },
  {
    patientProfileId: 41,
    patientEmail: 'patient@example.com',
    date: '2026-08-02',
    dietLogId: null,
    adherenceLevel: null,
    appetiteLevel: null,
    mealCount: null,
    deviationCount: null,
    measurementCount: null,
    symptomCheckInId: 8,
    symptomScore: 11,
    flareState: 'SUSPECTED_FLARE',
  },
]

function makeRouter() {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/clinical/patients/:patientProfileId/check-ins', component: ClinicalCheckInsView },
      { path: '/clinical/patients/:patientProfileId/check-ins/:date', component: { template: '<div />' } },
    ],
  })
}

describe('ClinicalCheckInsView', () => {
  beforeEach(() => setActivePinia(createPinia()))

  it('loads the default range and renders both halves per day', async () => {
    let seenUrl = ''
    server.use(
      http.get('/api/clinical/daily-check-ins', ({ request }) => {
        seenUrl = request.url
        return HttpResponse.json(summaries)
      }),
    )
    const router = makeRouter()
    await router.push('/clinical/patients/41/check-ins?email=patient%40example.com')
    const wrapper = mount(ClinicalCheckInsView, { global: { plugins: [createPinia(), i18n, router] } })
    await flushPromises()

    expect(seenUrl).toContain('patientProfileId=41')
    const rows = wrapper.findAll('[data-testid="checkin-row"]')
    expect(rows).toHaveLength(2)
    expect(rows[0].text()).toContain(en.enums.DietAdherenceLevel.FULL)
    expect(rows[1].text()).toContain(en.checkIn.FlareState.SUSPECTED_FLARE)
    expect(rows[1].text()).toContain(en.clinical.noValue)
  })

  it('opens the day detail with the email query preserved', async () => {
    server.use(http.get('/api/clinical/daily-check-ins', () => HttpResponse.json(summaries)))
    const router = makeRouter()
    await router.push('/clinical/patients/41/check-ins?email=patient%40example.com')
    const wrapper = mount(ClinicalCheckInsView, { global: { plugins: [createPinia(), i18n, router] } })
    await flushPromises()

    await wrapper.findAll('[data-testid="checkin-row"]')[1].trigger('click')
    expect(router.currentRoute.value.path).toBe('/clinical/patients/41/check-ins/2026-08-02')
    expect(router.currentRoute.value.query.email).toBe('patient@example.com')
  })
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && npx vitest run tests/views/clinical/ClinicalCheckInsView.test.ts`
Expected: FAIL — the view does not exist.

- [ ] **Step 3: Write the view**

```vue
<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { clinicalApi } from '@/api/clinical'
import { useApiError } from '@/composables/useApiError'
import { dateRangeError } from '@/utils/dateRange'
import type { ClinicalDailyCheckInSummary } from '@/types/api'

const { t } = useI18n()
const route = useRoute()
const router = useRouter()
const { message, capture, clear } = useApiError()

const patientProfileId = Number(route.params.patientProfileId)

function iso(d: Date): string {
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

const today = new Date()
const weekAgo = new Date(today)
weekAgo.setDate(weekAgo.getDate() - 6) // 7-day default, like the Thymeleaf page

const from = ref(iso(weekAgo))
const to = ref(iso(today))
const items = ref<ClinicalDailyCheckInSummary[]>([])
const loading = ref(true)

async function load() {
  clear()
  const rangeError = dateRangeError(from.value, to.value)
  if (rangeError) {
    message.value = t(`errors.date_range_${rangeError === 'too_long' ? 'too_long' : 'invalid'}`)
    return
  }
  loading.value = true
  try {
    items.value = await clinicalApi.listDailyCheckIns(patientProfileId, from.value, to.value)
  } catch (e) {
    capture(e)
  } finally {
    loading.value = false
  }
}

function open(item: ClinicalDailyCheckInSummary) {
  void router.push({
    path: `/clinical/patients/${patientProfileId}/check-ins/${item.date}`,
    query: route.query,
  })
}

onMounted(load)
</script>

<template>
  <section class="mt-4">
    <h2 class="text-lg font-medium">{{ t('clinical.checkInsTitle') }}</h2>
    <div class="mt-2 flex flex-wrap items-end gap-3">
      <label class="text-sm">{{ t('common.from') }}
        <input v-model="from" type="date" class="ml-1 rounded border border-gray-300 px-2 py-1 dark:border-gray-600 dark:bg-gray-800" />
      </label>
      <label class="text-sm">{{ t('common.to') }}
        <input v-model="to" type="date" class="ml-1 rounded border border-gray-300 px-2 py-1 dark:border-gray-600 dark:bg-gray-800" />
      </label>
      <button class="rounded bg-blue-600 px-3 py-1 text-sm text-white" @click="load">{{ t('common.apply') }}</button>
    </div>

    <p v-if="message" class="mt-4 rounded bg-red-50 p-3 text-sm text-red-700 dark:bg-red-950 dark:text-red-300">{{ message }}</p>
    <p v-if="loading" class="mt-4">{{ t('common.loading') }}</p>
    <table v-else data-testid="checkins-table" class="mt-4 w-full border-collapse bg-white text-sm dark:bg-gray-800">
      <thead>
        <tr class="border-b text-left">
          <th class="p-2">{{ t('clinical.colDate') }}</th>
          <th class="p-2">{{ t('clinical.colDietLog') }}</th>
          <th class="p-2">{{ t('clinical.colSymptomCheckIn') }}</th>
          <th class="p-2">{{ t('clinical.colScore') }}</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="item in items" :key="item.date" data-testid="checkin-row"
            class="cursor-pointer border-b hover:bg-gray-50 dark:hover:bg-gray-700"
            @click="open(item)">
          <td class="p-2">{{ item.date }}</td>
          <td class="p-2">
            {{ item.adherenceLevel ? t(`enums.DietAdherenceLevel.${item.adherenceLevel}`) : t('clinical.noValue') }}
          </td>
          <td class="p-2">
            {{ item.flareState ? t(`checkIn.FlareState.${item.flareState}`) : t('clinical.noValue') }}
          </td>
          <td class="p-2">{{ item.symptomScore ?? t('clinical.noValue') }}</td>
        </tr>
      </tbody>
    </table>
  </section>
</template>
```

- [ ] **Step 4: Swap the route to the real view**

In `frontend/src/router/index.ts`, replace `{ path: 'check-ins', component: ClinicalStubView },` with:

```ts
          { path: 'check-ins', component: ClinicalCheckInsView },
```

and add `import ClinicalCheckInsView from '@/views/clinical/ClinicalCheckInsView.vue'`.

- [ ] **Step 5: Run tests to verify they pass**

Run: `cd frontend && npx vitest run tests/views/clinical/ClinicalCheckInsView.test.ts tests/router`
Expected: PASS. Also `npm run typecheck`.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/views/clinical/ClinicalCheckInsView.vue \
        frontend/src/router/index.ts \
        frontend/tests/views/clinical/ClinicalCheckInsView.test.ts
git commit -m "Add clinical check-ins tab"
```

---

### Task 10: Frontend — check-in day detail

The merged single-day view: full diet log (meals, deviations, measurements, photos) plus
the symptom check-in (flare, score, answers), each half nullable. Photo bytes come from
the staff-readable `GET /api/diet-log-photos/{id}/content` via the `contentUrl` already
present on `PhotoReferenceResponse` — plain `<img>` tags work with the session cookie.

**Files:**
- Create: `frontend/src/views/clinical/ClinicalCheckInDayView.vue`
- Modify: `frontend/src/router/index.ts` (swap the `check-ins/:date` child from `ClinicalStubView`)
- Test: `frontend/tests/views/clinical/ClinicalCheckInDayView.test.ts`

**Interfaces:**
- Consumes: `clinicalApi.getDailyCheckIn(patientProfileId, date)` → `ClinicalDailyCheckInDetail` (Task 4); `formatDateTime` from `@/utils/dateTime`.
- Produces: nothing later tasks depend on.

- [ ] **Step 1: Write the failing test**

```ts
import { flushPromises, mount } from '@vue/test-utils'
import { http, HttpResponse } from 'msw'
import { beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { createI18n } from 'vue-i18n'
import { createRouter, createMemoryHistory } from 'vue-router'
import { server } from '../../msw/server'
import ClinicalCheckInDayView from '@/views/clinical/ClinicalCheckInDayView.vue'
import en from '@/i18n/en.json'

const i18n = createI18n({ legacy: false, locale: 'en', messages: { en } })

const detail = {
  patientProfileId: 41,
  patientEmail: 'patient@example.com',
  date: '2026-08-03',
  dietLog: {
    id: 7,
    patientProfileId: 41,
    patientEmail: 'patient@example.com',
    logDate: '2026-08-03',
    adherenceLevel: 'MOSTLY',
    appetiteLevel: 'NORMAL',
    notes: 'felt ok',
    metadata: null,
    createdAt: '2026-08-03T08:00:00Z',
    updatedAt: '2026-08-03T08:00:00Z',
    meals: [
      { id: 1, mealType: 'BREAKFAST', foodDescription: 'eggs', notes: null, sortOrder: 1 },
    ],
    deviations: [],
    photoReferences: [
      {
        id: 5,
        mealId: 1,
        originalFilename: 'eggs.jpg',
        contentType: 'image/jpeg',
        sizeBytes: 1234,
        caption: 'breakfast',
        contentUrl: '/api/diet-log-photos/5/content',
        sortOrder: 1,
      },
    ],
    measurements: [
      {
        id: 3,
        patientProfileId: 41,
        dailyDietLogId: 7,
        measurementType: 'KETONE',
        value: 1.8,
        unit: 'MMOL_L',
        measuredAt: '2026-08-03T06:30:00Z',
        context: 'FASTING',
        notes: null,
        metadata: null,
        createdAt: '2026-08-03T06:31:00Z',
      },
    ],
  },
  symptomCheckIn: {
    id: 9,
    patientProfileId: 41,
    questionnaireVersionId: 2,
    checkInDate: '2026-08-03',
    flareState: 'NO_FLARE',
    totalSymptomScore: 4,
    notes: null,
    answers: [
      {
        questionId: 1,
        questionStableKey: 'pain',
        label: 'Abdominal pain',
        answerType: 'SINGLE_CHOICE',
        optionId: 11,
        optionStableKey: 'mild',
        optionLabel: 'Mild',
        answerText: null,
        answerNumeric: null,
        numericScore: 1,
      },
    ],
    createdAt: '2026-08-03T07:00:00Z',
    updatedAt: '2026-08-03T07:00:00Z',
  },
}

describe('ClinicalCheckInDayView', () => {
  beforeEach(() => setActivePinia(createPinia()))

  it('renders both halves including the photo and answers', async () => {
    server.use(http.get('/api/clinical/daily-check-ins/41/2026-08-03', () => HttpResponse.json(detail)))
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/clinical/patients/:patientProfileId/check-ins/:date', component: ClinicalCheckInDayView }],
    })
    await router.push('/clinical/patients/41/check-ins/2026-08-03')
    const wrapper = mount(ClinicalCheckInDayView, { global: { plugins: [createPinia(), i18n, router] } })
    await flushPromises()

    expect(wrapper.text()).toContain('eggs')
    expect(wrapper.text()).toContain(en.enums.DietAdherenceLevel.MOSTLY)
    expect(wrapper.text()).toContain('Abdominal pain')
    expect(wrapper.text()).toContain('Mild')
    const img = wrapper.find('img')
    expect(img.attributes('src')).toBe('/api/diet-log-photos/5/content')
  })

  it('renders the empty halves when a side is missing', async () => {
    server.use(
      http.get('/api/clinical/daily-check-ins/41/2026-08-03', () =>
        HttpResponse.json({ ...detail, dietLog: null }),
      ),
    )
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/clinical/patients/:patientProfileId/check-ins/:date', component: ClinicalCheckInDayView }],
    })
    await router.push('/clinical/patients/41/check-ins/2026-08-03')
    const wrapper = mount(ClinicalCheckInDayView, { global: { plugins: [createPinia(), i18n, router] } })
    await flushPromises()

    expect(wrapper.text()).toContain(en.clinical.noDietLog)
    expect(wrapper.text()).toContain('Abdominal pain')
  })
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && npx vitest run tests/views/clinical/ClinicalCheckInDayView.test.ts`
Expected: FAIL — the view does not exist.

- [ ] **Step 3: Write the view**

```vue
<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { clinicalApi } from '@/api/clinical'
import { useApiError } from '@/composables/useApiError'
import { formatDateTime } from '@/utils/dateTime'
import type { AnswerResponse, ClinicalDailyCheckInDetail } from '@/types/api'

const { t, locale } = useI18n()
const route = useRoute()
const { message, capture } = useApiError()

const patientProfileId = Number(route.params.patientProfileId)
const date = route.params.date as string

const detail = ref<ClinicalDailyCheckInDetail | null>(null)
const loading = ref(true)

function answerValue(answer: AnswerResponse): string {
  const value = answer.optionLabel ?? answer.answerNumeric ?? answer.answerText
  return value === null || value === undefined ? t('clinical.noValue') : String(value)
}

onMounted(async () => {
  try {
    detail.value = await clinicalApi.getDailyCheckIn(patientProfileId, date)
  } catch (e) {
    capture(e)
  } finally {
    loading.value = false
  }
})
</script>

<template>
  <section class="mt-4">
    <h2 class="text-lg font-medium">{{ t('clinical.dayDetailTitle') }} — {{ date }}</h2>

    <p v-if="message" class="mt-4 rounded bg-red-50 p-3 text-sm text-red-700 dark:bg-red-950 dark:text-red-300">{{ message }}</p>
    <p v-if="loading" class="mt-4">{{ t('common.loading') }}</p>
    <template v-else-if="detail">
      <div class="mt-4 rounded border bg-white p-4 dark:bg-gray-800">
        <h3 class="font-medium">{{ t('clinical.colDietLog') }}</h3>
        <p v-if="!detail.dietLog" class="mt-2 text-sm text-gray-600 dark:text-gray-400">{{ t('clinical.noDietLog') }}</p>
        <template v-else>
          <p class="mt-2 text-sm">
            {{ t(`enums.DietAdherenceLevel.${detail.dietLog.adherenceLevel}`) }}
            · {{ t(`enums.AppetiteLevel.${detail.dietLog.appetiteLevel}`) }}
          </p>
          <p v-if="detail.dietLog.notes" class="mt-1 text-sm text-gray-600 dark:text-gray-400">{{ detail.dietLog.notes }}</p>

          <h4 class="mt-4 text-sm font-medium">{{ t('clinical.meals') }}</h4>
          <table class="mt-1 w-full border-collapse text-sm">
            <tbody>
              <tr v-for="meal in detail.dietLog.meals" :key="meal.id" class="border-b">
                <td class="p-2">{{ t(`enums.MealType.${meal.mealType}`) }}</td>
                <td class="p-2">{{ meal.foodDescription ?? t('clinical.noValue') }}</td>
                <td class="p-2 text-gray-600 dark:text-gray-400">{{ meal.notes ?? '' }}</td>
              </tr>
            </tbody>
          </table>

          <template v-if="detail.dietLog.deviations.length > 0">
            <h4 class="mt-4 text-sm font-medium">{{ t('clinical.deviations') }}</h4>
            <table class="mt-1 w-full border-collapse text-sm">
              <tbody>
                <tr v-for="deviation in detail.dietLog.deviations" :key="deviation.id" class="border-b">
                  <td class="p-2">{{ t(`enums.DietDeviationCategory.${deviation.deviationCategory}`) }}</td>
                  <td class="p-2">{{ t(`enums.DietDeviationSeverity.${deviation.severity}`) }}</td>
                  <td class="p-2 text-gray-600 dark:text-gray-400">{{ deviation.notes ?? '' }}</td>
                </tr>
              </tbody>
            </table>
          </template>

          <template v-if="detail.dietLog.measurements.length > 0">
            <h4 class="mt-4 text-sm font-medium">{{ t('clinical.measurements') }}</h4>
            <table class="mt-1 w-full border-collapse text-sm">
              <tbody>
                <tr v-for="m in detail.dietLog.measurements" :key="m.id" class="border-b">
                  <td class="p-2">{{ t(`enums.MeasurementType.${m.measurementType}`) }}</td>
                  <td class="p-2">{{ m.value }} {{ t(`enums.MeasurementUnit.${m.unit}`) }}</td>
                  <td class="p-2">{{ t(`enums.MeasurementContext.${m.context}`) }}</td>
                  <td class="p-2 text-gray-600 dark:text-gray-400">{{ formatDateTime(m.measuredAt, locale) }}</td>
                </tr>
              </tbody>
            </table>
          </template>

          <template v-if="detail.dietLog.photoReferences.length > 0">
            <h4 class="mt-4 text-sm font-medium">{{ t('clinical.photos') }}</h4>
            <div class="mt-2 flex flex-wrap gap-2">
              <a v-for="photo in detail.dietLog.photoReferences" :key="photo.id"
                 :href="photo.contentUrl" target="_blank" rel="noopener">
                <img :src="photo.contentUrl" :alt="photo.caption ?? photo.originalFilename"
                     class="h-24 w-24 rounded border object-cover dark:border-gray-600" />
              </a>
            </div>
          </template>
        </template>
      </div>

      <div class="mt-4 rounded border bg-white p-4 dark:bg-gray-800">
        <h3 class="font-medium">{{ t('clinical.colSymptomCheckIn') }}</h3>
        <p v-if="!detail.symptomCheckIn" class="mt-2 text-sm text-gray-600 dark:text-gray-400">{{ t('clinical.noSymptomCheckIn') }}</p>
        <template v-else>
          <p class="mt-2 text-sm">
            {{ t(`checkIn.FlareState.${detail.symptomCheckIn.flareState}`) }}
            · {{ t('clinical.colScore') }}: {{ detail.symptomCheckIn.totalSymptomScore ?? t('clinical.noValue') }}
          </p>
          <p v-if="detail.symptomCheckIn.notes" class="mt-1 text-sm text-gray-600 dark:text-gray-400">{{ detail.symptomCheckIn.notes }}</p>
          <h4 class="mt-4 text-sm font-medium">{{ t('clinical.answers') }}</h4>
          <table class="mt-1 w-full border-collapse text-sm">
            <tbody>
              <tr v-for="answer in detail.symptomCheckIn.answers" :key="answer.questionId" class="border-b">
                <td class="p-2">{{ answer.label }}</td>
                <td class="p-2">{{ answerValue(answer) }}</td>
              </tr>
            </tbody>
          </table>
        </template>
      </div>
    </template>
  </section>
</template>
```

- [ ] **Step 4: Swap the route to the real view**

In `frontend/src/router/index.ts`, replace `{ path: 'check-ins/:date', component: ClinicalStubView },` with:

```ts
          { path: 'check-ins/:date', component: ClinicalCheckInDayView },
```

and add `import ClinicalCheckInDayView from '@/views/clinical/ClinicalCheckInDayView.vue'`.

- [ ] **Step 5: Run tests to verify they pass**

Run: `cd frontend && npx vitest run tests/views/clinical/ClinicalCheckInDayView.test.ts tests/router`
Expected: PASS. Also `npm run typecheck`.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/views/clinical/ClinicalCheckInDayView.vue \
        frontend/src/router/index.ts \
        frontend/tests/views/clinical/ClinicalCheckInDayView.test.ts
git commit -m "Add clinical check-in day detail"
```

---

### Task 11: Frontend — trends tab

Daily symptom/glucose/ketone charts for one patient, reusing `LineChart.vue` and the
patient `TrendsView` data mapping (glucose normalized to the response's unit, daily
average per measurement kind). Server-side SVG renderers are deliberately not ported.

**Files:**
- Create: `frontend/src/views/clinical/ClinicalPatientTrendsView.vue`
- Modify: `frontend/src/router/index.ts` (swap the `trends` child from `ClinicalStubView`)
- Test: `frontend/tests/views/clinical/ClinicalPatientTrendsView.test.ts`

**Interfaces:**
- Consumes: `clinicalApi.dailyTrend(patientProfileId, from, to)` → `DailyTrendResponse` (Task 4); `convertGlucose` from `@/utils/glucose`; `LineChart` props `labels: string[]`, `datasets: { label: string; data: (number | null)[] }[]`.
- Produces: nothing later tasks depend on.

- [ ] **Step 1: Write the failing test**

`LineChart` is stubbed (jsdom has no canvas); the assertion targets the request and the
rendered sections.

```ts
import { flushPromises, mount } from '@vue/test-utils'
import { http, HttpResponse } from 'msw'
import { beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { createI18n } from 'vue-i18n'
import { createRouter, createMemoryHistory } from 'vue-router'
import { server } from '../../msw/server'
import ClinicalPatientTrendsView from '@/views/clinical/ClinicalPatientTrendsView.vue'
import en from '@/i18n/en.json'

const i18n = createI18n({ legacy: false, locale: 'en', messages: { en } })

const trend = {
  patientProfileId: 41,
  from: '2026-07-05',
  to: '2026-08-04',
  glucoseUnit: 'MMOL_L',
  timezone: 'Europe/Prague',
  days: [
    {
      date: '2026-08-03',
      symptomCheckInId: 9,
      symptomScore: 4,
      flareState: 'NO_FLARE',
      dietLogId: 7,
      adherenceLevel: 'FULL',
      appetiteLevel: 'NORMAL',
      glucoseMeasurements: [
        { id: 1, measurementType: 'GLUCOSE', value: 5.5, unit: 'MMOL_L', measuredAt: '2026-08-03T07:00:00Z', context: 'FASTING' },
      ],
      ketoneMeasurements: [
        { id: 2, measurementType: 'KETONE', value: 1.8, unit: 'MMOL_L', measuredAt: '2026-08-03T07:00:00Z', context: 'FASTING' },
      ],
    },
  ],
}

describe('ClinicalPatientTrendsView', () => {
  beforeEach(() => setActivePinia(createPinia()))

  it('loads the clinical trend for the patient and renders the chart sections', async () => {
    let seenUrl = ''
    server.use(
      http.get('/api/clinical/trends/daily', ({ request }) => {
        seenUrl = request.url
        return HttpResponse.json(trend)
      }),
    )
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/clinical/patients/:patientProfileId/trends', component: ClinicalPatientTrendsView }],
    })
    await router.push('/clinical/patients/41/trends')
    const wrapper = mount(ClinicalPatientTrendsView, {
      global: { plugins: [createPinia(), i18n, router], stubs: { LineChart: true } },
    })
    await flushPromises()

    expect(seenUrl).toContain('patientProfileId=41')
    expect(wrapper.text()).toContain(en.trends.symptomScore)
    expect(wrapper.text()).toContain(en.trends.glucose)
    expect(wrapper.text()).toContain(en.trends.ketones)
  })
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && npx vitest run tests/views/clinical/ClinicalPatientTrendsView.test.ts`
Expected: FAIL — the view does not exist.

- [ ] **Step 3: Write the view**

```vue
<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { clinicalApi } from '@/api/clinical'
import { useApiError } from '@/composables/useApiError'
import { dateRangeError } from '@/utils/dateRange'
import { convertGlucose } from '@/utils/glucose'
import LineChart from '@/components/LineChart.vue'
import type { DailyTrendResponse } from '@/types/api'

const { t } = useI18n()
const route = useRoute()
const { message, capture, clear } = useApiError()

const patientProfileId = Number(route.params.patientProfileId)

function iso(d: Date): string {
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

const today = new Date()
const monthAgo = new Date(today)
monthAgo.setDate(monthAgo.getDate() - 30)

const from = ref(iso(monthAgo))
const to = ref(iso(today))
const trend = ref<DailyTrendResponse | null>(null)
const loading = ref(true)

async function load() {
  clear()
  const rangeError = dateRangeError(from.value, to.value)
  if (rangeError) {
    message.value = t(`errors.date_range_${rangeError === 'too_long' ? 'too_long' : 'invalid'}`)
    return
  }
  loading.value = true
  try {
    trend.value = await clinicalApi.dailyTrend(patientProfileId, from.value, to.value)
  } catch (e) {
    capture(e)
  } finally {
    loading.value = false
  }
}

onMounted(load)

const labels = computed(() => trend.value?.days.map((d) => d.date) ?? [])

const symptomDataset = computed(() => [
  { label: t('trends.symptomScore'), data: trend.value?.days.map((d) => d.symptomScore) ?? [] },
])

function measurementData(kind: 'glucoseMeasurements' | 'ketoneMeasurements') {
  // Average per day when multiple measurements exist. The backend returns each
  // glucose point in its own unit, so normalize to the trend unit first.
  return trend.value?.days.map((d) => {
    const points = d[kind]
    if (points.length === 0) return null
    const target = trend.value!.glucoseUnit
    return points.reduce((sum, p) => sum + (kind === 'glucoseMeasurements' ? convertGlucose(p.value, p.unit, target) : p.value), 0) / points.length
  }) ?? []
}
</script>

<template>
  <section class="mt-4">
    <div class="flex flex-wrap items-end gap-3">
      <label class="text-sm">{{ t('common.from') }}
        <input v-model="from" type="date" class="ml-1 rounded border border-gray-300 px-2 py-1 dark:border-gray-600 dark:bg-gray-800" />
      </label>
      <label class="text-sm">{{ t('common.to') }}
        <input v-model="to" type="date" class="ml-1 rounded border border-gray-300 px-2 py-1 dark:border-gray-600 dark:bg-gray-800" />
      </label>
      <button class="rounded bg-blue-600 px-3 py-1 text-sm text-white" @click="load">{{ t('common.apply') }}</button>
    </div>

    <p v-if="message" class="mt-4 rounded bg-red-50 p-3 text-sm text-red-700 dark:bg-red-950 dark:text-red-300">{{ message }}</p>
    <p v-if="loading" class="mt-4">{{ t('common.loading') }}</p>
    <template v-else-if="trend">
      <div class="mt-6 rounded border bg-white p-4 dark:bg-gray-800">
        <h3 class="mb-2 font-medium">{{ t('trends.symptomScore') }}</h3>
        <LineChart :labels="labels" :datasets="symptomDataset" />
      </div>
      <div class="mt-6 rounded border bg-white p-4 dark:bg-gray-800">
        <h3 class="mb-2 font-medium">
          {{ t('trends.glucose') }} ({{ t(`enums.MeasurementUnit.${trend.glucoseUnit}`) }})
        </h3>
        <LineChart :labels="labels" :datasets="[{ label: t('trends.glucose'), data: measurementData('glucoseMeasurements') }]" />
      </div>
      <div class="mt-6 rounded border bg-white p-4 dark:bg-gray-800">
        <h3 class="mb-2 font-medium">{{ t('trends.ketones') }}</h3>
        <LineChart :labels="labels" :datasets="[{ label: t('trends.ketones'), data: measurementData('ketoneMeasurements') }]" />
      </div>
    </template>
  </section>
</template>
```

- [ ] **Step 4: Swap the route to the real view**

In `frontend/src/router/index.ts`, replace `{ path: 'trends', component: ClinicalStubView },` with:

```ts
          { path: 'trends', component: ClinicalPatientTrendsView },
```

and add `import ClinicalPatientTrendsView from '@/views/clinical/ClinicalPatientTrendsView.vue'`.

- [ ] **Step 5: Run tests to verify they pass**

Run: `cd frontend && npx vitest run tests/views/clinical/ClinicalPatientTrendsView.test.ts tests/router`
Expected: PASS. Also `npm run typecheck`.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/views/clinical/ClinicalPatientTrendsView.vue \
        frontend/src/router/index.ts \
        frontend/tests/views/clinical/ClinicalPatientTrendsView.test.ts
git commit -m "Add clinical trends tab"
```

---

### Task 12: Frontend — labs tab (result sets + per-test trend)

Result-set list (12-month default, like the Thymeleaf clinical labs page) plus a
per-test trend chart fed by the lab catalog. Entry/edit happens in Task 13's view.

**Files:**
- Create: `frontend/src/views/clinical/ClinicalPatientLabsView.vue`
- Modify: `frontend/src/router/index.ts` (swap the `labs` child from `ClinicalStubView`)
- Test: `frontend/tests/views/clinical/ClinicalPatientLabsView.test.ts`

**Interfaces:**
- Consumes: `clinicalApi.listLabResultSets`, `clinicalApi.labTrend` (Task 4); `labApi.listTests()` (existing `frontend/src/api/labs.ts` — the catalog is role-neutral); `LineChart`.
- Produces: navigation to `/clinical/patients/{id}/labs/new` and `/clinical/patients/{id}/labs/{resultSetId}` consumed by Task 13.

- [ ] **Step 1: Write the failing test**

```ts
import { flushPromises, mount } from '@vue/test-utils'
import { http, HttpResponse } from 'msw'
import { beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { createI18n } from 'vue-i18n'
import { createRouter, createMemoryHistory } from 'vue-router'
import { server } from '../../msw/server'
import ClinicalPatientLabsView from '@/views/clinical/ClinicalPatientLabsView.vue'
import en from '@/i18n/en.json'

const i18n = createI18n({ legacy: false, locale: 'en', messages: { en } })

const catalog = [
  { code: 'CRP', label: 'C-reactive protein', category: 'INFLAMMATION', canonicalUnit: 'mg/L', displayScale: 1, allowedUnits: ['mg/L'] },
]

const resultSets = [
  {
    id: 3,
    version: 2,
    patientProfileId: 41,
    collectionDate: '2026-07-10',
    notes: null,
    source: 'MANUAL',
    confirmationStatus: 'UNCONFIRMED',
    createdByCurrentPatient: false,
    createdAt: '2026-07-10T08:00:00Z',
    updatedAt: '2026-07-10T08:00:00Z',
    results: [
      { id: 31, testCode: 'CRP', label: 'C-reactive protein', reportedValue: 4.2, reportedUnit: 'mg/L', canonicalValue: 4.2, canonicalUnit: 'mg/L', referenceLower: null, referenceUpper: 5 },
    ],
  },
]

const trend = {
  patientProfileId: 41,
  testCode: 'CRP',
  label: 'C-reactive protein',
  canonicalUnit: 'mg/L',
  displayScale: 1,
  from: '2025-08-04',
  to: '2026-08-04',
  points: [
    { resultSetId: 3, resultSetVersion: 2, collectionDate: '2026-07-10', canonicalValue: 4.2, reportedValue: 4.2, reportedUnit: 'mg/L', referenceLower: null, referenceUpper: 5, editable: true },
  ],
}

describe('ClinicalPatientLabsView', () => {
  beforeEach(() => setActivePinia(createPinia()))

  it('lists result sets and links to the editor', async () => {
    let seenUrl = ''
    server.use(
      http.get('/api/clinical/patients/41/labs/result-sets', ({ request }) => {
        seenUrl = request.url
        return HttpResponse.json(resultSets)
      }),
      http.get('/api/lab-tests', () => HttpResponse.json(catalog)),
    )
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/clinical/patients/:patientProfileId/labs', component: ClinicalPatientLabsView },
        { path: '/clinical/patients/:patientProfileId/labs/:resultSetId', component: { template: '<div />' } },
      ],
    })
    await router.push('/clinical/patients/41/labs?email=patient%40example.com')
    const wrapper = mount(ClinicalPatientLabsView, {
      global: { plugins: [createPinia(), i18n, router], stubs: { LineChart: true } },
    })
    await flushPromises()

    expect(seenUrl).toContain('/api/clinical/patients/41/labs/result-sets')
    expect(wrapper.text()).toContain('CRP')
    expect(wrapper.html()).toContain('href="/clinical/patients/41/labs/3?email=patient%40example.com"')
    expect(wrapper.html()).toContain('href="/clinical/patients/41/labs/new?email=patient%40example.com"')
  })

  it('loads the per-test trend when a test is selected', async () => {
    let trendUrl = ''
    server.use(
      http.get('/api/clinical/patients/41/labs/result-sets', () => HttpResponse.json(resultSets)),
      http.get('/api/lab-tests', () => HttpResponse.json(catalog)),
      http.get('/api/clinical/patients/41/labs/trends/CRP', ({ request }) => {
        trendUrl = request.url
        return HttpResponse.json(trend)
      }),
    )
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/clinical/patients/:patientProfileId/labs', component: ClinicalPatientLabsView }],
    })
    await router.push('/clinical/patients/41/labs')
    const wrapper = mount(ClinicalPatientLabsView, {
      global: { plugins: [createPinia(), i18n, router], stubs: { LineChart: true } },
    })
    await flushPromises()

    await wrapper.find('[data-testid="test-select"]').setValue('CRP')
    await flushPromises()
    expect(trendUrl).toContain('/api/clinical/patients/41/labs/trends/CRP')
  })
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && npx vitest run tests/views/clinical/ClinicalPatientLabsView.test.ts`
Expected: FAIL — the view does not exist.

- [ ] **Step 3: Write the view**

```vue
<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { clinicalApi } from '@/api/clinical'
import { labApi } from '@/api/labs'
import { useApiError } from '@/composables/useApiError'
import { dateRangeError } from '@/utils/dateRange'
import LineChart from '@/components/LineChart.vue'
import type { LabResultSetResponse, LabTestDefinition, LabTrendResponse } from '@/types/api'

const { t } = useI18n()
const route = useRoute()
const { message, capture, clear } = useApiError()

const patientProfileId = Number(route.params.patientProfileId)

function iso(d: Date): string {
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

const today = new Date()
const yearAgo = new Date(today)
yearAgo.setFullYear(yearAgo.getFullYear() - 1) // 12-month default, like the Thymeleaf page

const from = ref(iso(yearAgo))
const to = ref(iso(today))
const resultSets = ref<LabResultSetResponse[]>([])
const tests = ref<LabTestDefinition[]>([])
const selectedTest = ref('')
const trend = ref<LabTrendResponse | null>(null)
const loading = ref(true)

function rangeInvalid(): boolean {
  const rangeError = dateRangeError(from.value, to.value)
  if (rangeError) {
    message.value = t(`errors.date_range_${rangeError === 'too_long' ? 'too_long' : 'invalid'}`)
    return true
  }
  return false
}

async function loadList() {
  clear()
  if (rangeInvalid()) return
  loading.value = true
  try {
    resultSets.value = await clinicalApi.listLabResultSets(patientProfileId, from.value, to.value)
  } catch (e) {
    capture(e)
  } finally {
    loading.value = false
  }
}

async function loadTrend() {
  if (!selectedTest.value || rangeInvalid()) {
    trend.value = null
    return
  }
  try {
    trend.value = await clinicalApi.labTrend(patientProfileId, selectedTest.value, from.value, to.value)
  } catch (e) {
    capture(e)
  }
}

onMounted(async () => {
  try {
    tests.value = await labApi.listTests()
  } catch (e) {
    capture(e)
  }
  await loadList()
})

watch(selectedTest, loadTrend)
</script>

<template>
  <section class="mt-4">
    <div class="flex flex-wrap items-center justify-between gap-3">
      <h2 class="text-lg font-medium">{{ t('labs.title') }}</h2>
      <router-link :to="{ path: `/clinical/patients/${patientProfileId}/labs/new`, query: route.query }"
                   class="rounded bg-blue-600 px-3 py-1 text-sm text-white">
        {{ t('labs.newResultSet') }}
      </router-link>
    </div>

    <div class="mt-2 flex flex-wrap items-end gap-3">
      <label class="text-sm">{{ t('common.from') }}
        <input v-model="from" type="date" class="ml-1 rounded border border-gray-300 px-2 py-1 dark:border-gray-600 dark:bg-gray-800" />
      </label>
      <label class="text-sm">{{ t('common.to') }}
        <input v-model="to" type="date" class="ml-1 rounded border border-gray-300 px-2 py-1 dark:border-gray-600 dark:bg-gray-800" />
      </label>
      <button class="rounded bg-blue-600 px-3 py-1 text-sm text-white" @click="loadList">{{ t('common.apply') }}</button>
    </div>

    <p v-if="message" class="mt-4 rounded bg-red-50 p-3 text-sm text-red-700 dark:bg-red-950 dark:text-red-300">{{ message }}</p>
    <p v-if="loading" class="mt-4">{{ t('common.loading') }}</p>
    <table v-else data-testid="resultsets-table" class="mt-4 w-full border-collapse bg-white text-sm dark:bg-gray-800">
      <thead>
        <tr class="border-b text-left">
          <th class="p-2">{{ t('labs.collectionDate') }}</th>
          <th class="p-2">{{ t('labs.results') }}</th>
          <th class="p-2">{{ t('labs.status') }}</th>
          <th class="p-2"></th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="rs in resultSets" :key="rs.id" class="border-b">
          <td class="p-2">{{ rs.collectionDate }}</td>
          <td class="p-2">{{ rs.results.map((r) => r.testCode).join(', ') }}</td>
          <td class="p-2">{{ t(rs.confirmationStatus === 'CONFIRMED' ? 'labs.confirmed' : 'labs.unconfirmed') }}</td>
          <td class="p-2">
            <router-link :to="{ path: `/clinical/patients/${patientProfileId}/labs/${rs.id}`, query: route.query }"
                         class="text-blue-600 dark:text-blue-400">
              {{ t('labs.edit') }}
            </router-link>
          </td>
        </tr>
      </tbody>
    </table>

    <div class="mt-6 rounded border bg-white p-4 dark:bg-gray-800">
      <label class="text-sm">{{ t('labs.selectTest') }}
        <select v-model="selectedTest" data-testid="test-select"
                class="ml-1 rounded border border-gray-300 px-2 py-1 dark:border-gray-600 dark:bg-gray-800">
          <option value="">—</option>
          <option v-for="test in tests" :key="test.code" :value="test.code">{{ test.label }}</option>
        </select>
      </label>
      <template v-if="trend">
        <h3 class="mb-2 mt-4 font-medium">{{ trend.label }} ({{ trend.canonicalUnit }})</h3>
        <LineChart
          :labels="trend.points.map((p) => p.collectionDate)"
          :datasets="[{ label: trend.label, data: trend.points.map((p) => p.canonicalValue) }]" />
      </template>
    </div>
  </section>
</template>
```

- [ ] **Step 4: Swap the route to the real view**

In `frontend/src/router/index.ts`, replace `{ path: 'labs', component: ClinicalStubView },` with:

```ts
          { path: 'labs', component: ClinicalPatientLabsView },
```

and add `import ClinicalPatientLabsView from '@/views/clinical/ClinicalPatientLabsView.vue'`.

- [ ] **Step 5: Run tests to verify they pass**

Run: `cd frontend && npx vitest run tests/views/clinical/ClinicalPatientLabsView.test.ts tests/router`
Expected: PASS. Also `npm run typecheck`.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/views/clinical/ClinicalPatientLabsView.vue \
        frontend/src/router/index.ts \
        frontend/tests/views/clinical/ClinicalPatientLabsView.test.ts
git commit -m "Add clinical labs tab"
```

---

### Task 13: Frontend — lab result-set editor (entry/edit/removal)

The clinical write flow: staff enter and edit lab result sets for their patients and
request soft-removal with a reason. Mirrors the patient `LabResultSetEditView` behavior —
optimistic-lock `version`, 409 → conflict message + reload button, create-then-switch-to-
edit — but calls the `clinicalApi` endpoints and preserves the workspace query.

**Files:**
- Create: `frontend/src/views/clinical/ClinicalLabResultSetEditView.vue`
- Modify: `frontend/src/router/index.ts` (swap the `labs/new` and `labs/:resultSetId` children from `ClinicalStubView`)
- Test: `frontend/tests/views/clinical/ClinicalLabResultSetEditView.test.ts`

**Interfaces:**
- Consumes: `clinicalApi.getLabResultSet/createLabResultSet/updateLabResultSet/requestLabRemoval` (Task 4); `labApi.listTests()`; `FieldError` component (`:message` prop); `LabResultRequest` shape `{ testCode, value, unit, referenceLower?, referenceUpper? }`.
- Produces: nothing later tasks depend on.

- [ ] **Step 1: Write the failing tests**

```ts
import { flushPromises, mount } from '@vue/test-utils'
import { http, HttpResponse } from 'msw'
import { beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { createI18n } from 'vue-i18n'
import { createRouter, createMemoryHistory } from 'vue-router'
import { server } from '../../msw/server'
import ClinicalLabResultSetEditView from '@/views/clinical/ClinicalLabResultSetEditView.vue'
import en from '@/i18n/en.json'

const i18n = createI18n({ legacy: false, locale: 'en', messages: { en } })

const catalog = [
  { code: 'CRP', label: 'C-reactive protein', category: 'INFLAMMATION', canonicalUnit: 'mg/L', displayScale: 1, allowedUnits: ['mg/L'] },
]

const existing = {
  id: 3,
  version: 2,
  patientProfileId: 41,
  collectionDate: '2026-07-10',
  notes: 'note',
  source: 'MANUAL',
  confirmationStatus: 'UNCONFIRMED',
  createdByCurrentPatient: false,
  createdAt: '2026-07-10T08:00:00Z',
  updatedAt: '2026-07-10T08:00:00Z',
  results: [
    { id: 31, testCode: 'CRP', label: 'C-reactive protein', reportedValue: 4.2, reportedUnit: 'mg/L', canonicalValue: 4.2, canonicalUnit: 'mg/L', referenceLower: null, referenceUpper: 5 },
  ],
}

function makeRouter() {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/clinical/patients/:patientProfileId/labs', component: { template: '<div />' } },
      { path: '/clinical/patients/:patientProfileId/labs/new', component: ClinicalLabResultSetEditView },
      { path: '/clinical/patients/:patientProfileId/labs/:resultSetId', component: ClinicalLabResultSetEditView },
    ],
  })
}

describe('ClinicalLabResultSetEditView', () => {
  beforeEach(() => setActivePinia(createPinia()))

  it('shows conflict message and reload button on 409', async () => {
    server.use(
      http.get('/api/lab-tests', () => HttpResponse.json(catalog)),
      http.get('/api/clinical/patients/41/labs/result-sets/3', () => HttpResponse.json(existing)),
      http.get('/api/csrf', () => HttpResponse.json({ token: 't', headerName: 'X-XSRF-TOKEN' })),
      http.put('/api/clinical/patients/41/labs/result-sets/3', () =>
        HttpResponse.json({ error: 'conflict' }, { status: 409 })),
    )
    const router = makeRouter()
    await router.push('/clinical/patients/41/labs/3')
    const wrapper = mount(ClinicalLabResultSetEditView, { global: { plugins: [createPinia(), i18n, router] } })
    await flushPromises()
    expect(wrapper.find('input[type="date"]').element).toHaveProperty('value', '2026-07-10')

    await wrapper.find('[data-testid="save"]').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain(en.errors.conflict)
    expect(wrapper.find('[data-testid="reload"]').exists()).toBe(true)
  })

  it('requests removal with a reason and returns to the labs tab', async () => {
    let received: unknown
    server.use(
      http.get('/api/lab-tests', () => HttpResponse.json(catalog)),
      http.get('/api/clinical/patients/41/labs/result-sets/3', () => HttpResponse.json(existing)),
      http.get('/api/csrf', () => HttpResponse.json({ token: 't', headerName: 'X-XSRF-TOKEN' })),
      http.post('/api/clinical/patients/41/labs/result-sets/3/removal', async ({ request }) => {
        received = await request.json()
        return HttpResponse.json({ status: 'removed' })
      }),
    )
    const router = makeRouter()
    await router.push('/clinical/patients/41/labs/3?email=patient%40example.com')
    const wrapper = mount(ClinicalLabResultSetEditView, { global: { plugins: [createPinia(), i18n, router] } })
    await flushPromises()

    await wrapper.find('[data-testid="removal-reason"]').setValue('entered in error')
    await wrapper.find('[data-testid="remove"]').trigger('click')
    await flushPromises()

    expect(received).toEqual({ resultSetId: 3, version: 2, reason: 'entered in error' })
    expect(router.currentRoute.value.path).toBe('/clinical/patients/41/labs')
    expect(router.currentRoute.value.query.email).toBe('patient@example.com')
  })
})
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd frontend && npx vitest run tests/views/clinical/ClinicalLabResultSetEditView.test.ts`
Expected: FAIL — the view does not exist.

- [ ] **Step 3: Write the view**

```vue
<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { clinicalApi } from '@/api/clinical'
import { labApi } from '@/api/labs'
import { ApiError } from '@/api/http'
import { useApiError } from '@/composables/useApiError'
import FieldError from '@/components/FieldError.vue'
import type { LabTestDefinition } from '@/types/api'

interface ResultRow {
  testCode: string
  value: number | null
  unit: string
  referenceLower: number | null
  referenceUpper: number | null
}

const { t } = useI18n()
const route = useRoute()
const router = useRouter()
const { message, fieldErrors, capture, clear } = useApiError()

const patientProfileId = Number(route.params.patientProfileId)
const id = ref<number | null>(route.params.resultSetId ? Number(route.params.resultSetId) : null)
const isNew = computed(() => id.value === null)

const tests = ref<LabTestDefinition[]>([])
const collectionDate = ref('')
const notes = ref('')
const version = ref<number | null>(null)
const results = reactive<ResultRow[]>([])
const loading = ref(true)
const saved = ref(false)
const conflict = ref(false)
const removalReason = ref('')

function numOrNull(v: number | null): number | null {
  return v === null || Number.isNaN(v) ? null : v
}

function allowedUnits(testCode: string): string[] {
  return tests.value.find((test) => test.code === testCode)?.allowedUnits ?? []
}

async function loadExisting() {
  if (id.value === null) return
  const existing = await clinicalApi.getLabResultSet(patientProfileId, id.value)
  collectionDate.value = existing.collectionDate
  notes.value = existing.notes ?? ''
  version.value = existing.version
  results.splice(0, results.length, ...existing.results.map((r) => ({
    testCode: r.testCode,
    value: r.reportedValue,
    unit: r.reportedUnit,
    referenceLower: r.referenceLower,
    referenceUpper: r.referenceUpper,
  })))
}

onMounted(async () => {
  try {
    tests.value = await labApi.listTests()
    await loadExisting()
  } catch (e) {
    capture(e)
  } finally {
    loading.value = false
  }
})

function addResult() {
  const first = tests.value[0]
  results.push({
    testCode: first?.code ?? '',
    value: null,
    unit: first?.allowedUnits[0] ?? '',
    referenceLower: null,
    referenceUpper: null,
  })
}

async function reload() {
  conflict.value = false
  clear()
  loading.value = true
  try {
    await loadExisting()
  } finally {
    loading.value = false
  }
}

async function save() {
  clear()
  saved.value = false
  conflict.value = false
  try {
    const payload = {
      resultSetId: isNew.value ? null : id.value,
      version: isNew.value ? null : version.value,
      collectionDate: collectionDate.value,
      notes: notes.value || undefined,
      results: results.map((r) => ({
        testCode: r.testCode,
        value: numOrNull(r.value) as number,
        unit: r.unit,
        referenceLower: numOrNull(r.referenceLower),
        referenceUpper: numOrNull(r.referenceUpper),
      })),
    }
    if (isNew.value) {
      const res = await clinicalApi.createLabResultSet(patientProfileId, payload)
      version.value = res.version
      id.value = res.id
      // Switch into edit mode so a second Save updates instead of duplicating.
      await router.replace({ path: `/clinical/patients/${patientProfileId}/labs/${res.id}`, query: route.query })
    } else {
      const res = await clinicalApi.updateLabResultSet(patientProfileId, id.value!, payload)
      version.value = res.version
    }
    saved.value = true
  } catch (e) {
    if (e instanceof ApiError && e.status === 409) {
      conflict.value = true
      message.value = t('errors.conflict')
      return
    }
    capture(e)
  }
}

async function requestRemoval() {
  clear()
  try {
    await clinicalApi.requestLabRemoval(patientProfileId, id.value!, version.value!, removalReason.value)
    await router.push({ path: `/clinical/patients/${patientProfileId}/labs`, query: route.query })
  } catch (e) {
    capture(e)
  }
}
</script>

<template>
  <section class="mt-4">
    <h2 class="text-lg font-medium">{{ isNew ? t('labs.newResultSet') : t('labs.edit') }}</h2>

    <p v-if="loading" class="mt-4">{{ t('common.loading') }}</p>
    <template v-else>
      <p v-if="message" class="mt-4 rounded bg-red-50 p-3 text-sm text-red-700 dark:bg-red-950 dark:text-red-300">{{ message }}</p>
      <p v-if="saved" class="mt-4 rounded bg-green-50 p-3 text-sm text-green-700 dark:bg-green-950 dark:text-green-300">{{ t('common.saved') }}</p>
      <button v-if="conflict" data-testid="reload" class="mt-2 rounded border px-3 py-1 text-sm" @click="reload">
        {{ t('labs.reload') }}
      </button>

      <form class="mt-4 space-y-4" @submit.prevent="save">
        <div>
          <label class="block text-sm font-medium">{{ t('labs.collectionDate') }}</label>
          <input v-model="collectionDate" type="date" required
                 class="mt-1 rounded border border-gray-300 px-3 py-2 dark:border-gray-600 dark:bg-gray-800" />
          <FieldError :message="fieldErrors.collectionDate" />
        </div>
        <div>
          <label class="block text-sm font-medium">{{ t('labs.notes') }}</label>
          <input v-model="notes" type="text"
                 class="mt-1 w-full rounded border border-gray-300 px-3 py-2 dark:border-gray-600 dark:bg-gray-800" />
        </div>

        <h3 class="text-sm font-medium">{{ t('labs.results') }}</h3>
        <div v-for="(result, index) in results" :key="index" class="flex flex-wrap items-end gap-2">
          <label class="text-sm">{{ t('labs.test') }}
            <select v-model="result.testCode"
                    class="ml-1 rounded border border-gray-300 px-2 py-1 dark:border-gray-600 dark:bg-gray-800">
              <option v-for="test in tests" :key="test.code" :value="test.code">{{ test.label }}</option>
            </select>
          </label>
          <label class="text-sm">{{ t('labs.value') }}
            <input v-model.number="result.value" type="number" step="any" :data-testid="`result-value-${index}`"
                   class="ml-1 w-28 rounded border border-gray-300 px-2 py-1 dark:border-gray-600 dark:bg-gray-800" />
          </label>
          <label class="text-sm">{{ t('labs.unit') }}
            <select v-model="result.unit"
                    class="ml-1 rounded border border-gray-300 px-2 py-1 dark:border-gray-600 dark:bg-gray-800">
              <option v-for="unit in allowedUnits(result.testCode)" :key="unit" :value="unit">{{ unit }}</option>
            </select>
          </label>
          <label class="text-sm">{{ t('labs.referenceLower') }}
            <input v-model.number="result.referenceLower" type="number" step="any"
                   class="ml-1 w-24 rounded border border-gray-300 px-2 py-1 dark:border-gray-600 dark:bg-gray-800" />
          </label>
          <label class="text-sm">{{ t('labs.referenceUpper') }}
            <input v-model.number="result.referenceUpper" type="number" step="any"
                   class="ml-1 w-24 rounded border border-gray-300 px-2 py-1 dark:border-gray-600 dark:bg-gray-800" />
          </label>
        </div>
        <button type="button" data-testid="add-result" class="rounded border px-3 py-1 text-sm" @click="addResult">
          {{ t('labs.addResult') }}
        </button>

        <div>
          <button type="submit" data-testid="save" class="rounded bg-blue-600 px-4 py-2 text-white">
            {{ t('common.save') }}
          </button>
        </div>
      </form>

      <div v-if="!isNew" class="mt-6 rounded border border-red-200 p-4 dark:border-red-900">
        <label class="block text-sm font-medium">{{ t('labs.removalReason') }}</label>
        <input v-model="removalReason" type="text" data-testid="removal-reason"
               class="mt-1 w-full rounded border border-gray-300 px-3 py-2 dark:border-gray-600 dark:bg-gray-800" />
        <button data-testid="remove" class="mt-2 rounded border border-red-300 px-3 py-1 text-sm text-red-700 dark:text-red-300"
                @click="requestRemoval">
          {{ t('labs.requestRemoval') }}
        </button>
      </div>
    </template>
  </section>
</template>
```

- [ ] **Step 4: Swap the routes to the real view**

In `frontend/src/router/index.ts`, replace both lab edit children:

```ts
          { path: 'labs/new', component: ClinicalLabResultSetEditView },
          { path: 'labs/:resultSetId', component: ClinicalLabResultSetEditView },
```

and add `import ClinicalLabResultSetEditView from '@/views/clinical/ClinicalLabResultSetEditView.vue'`.

- [ ] **Step 5: Run tests to verify they pass**

Run: `cd frontend && npx vitest run tests/views/clinical/ClinicalLabResultSetEditView.test.ts tests/router`
Expected: PASS. Also `npm run typecheck`.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/views/clinical/ClinicalLabResultSetEditView.vue \
        frontend/src/router/index.ts \
        frontend/tests/views/clinical/ClinicalLabResultSetEditView.test.ts
git commit -m "Add clinical lab result-set editor"
```

---

### Task 14: Frontend — red flags tab

Clinical variant of the patient `RedFlagsView`: current snapshot + cursor-paginated
history for one patient, with the same generation-guard against stale responses. Uses a
local ref for the snapshot (the app-wide patient banner store stays patient-side).

**Files:**
- Create: `frontend/src/views/clinical/ClinicalPatientRedFlagsView.vue`
- Modify: `frontend/src/router/index.ts` (swap the `red-flags` child from `ClinicalStubView`)
- Test: `frontend/tests/views/clinical/ClinicalPatientRedFlagsView.test.ts`

**Interfaces:**
- Consumes: `clinicalApi.currentRedFlags(patientProfileId)`, `clinicalApi.redFlagHistory(patientProfileId, params)` (Task 4); `severityBadgeClass` (`@/utils/redFlags`); `formatDateTime` (`@/utils/dateTime`); `dateRangeError` with the 369-day history cap.
- Produces: nothing later tasks depend on.

- [ ] **Step 1: Write the failing test**

```ts
import { flushPromises, mount } from '@vue/test-utils'
import { http, HttpResponse } from 'msw'
import { beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { createI18n } from 'vue-i18n'
import { createRouter, createMemoryHistory } from 'vue-router'
import { server } from '../../msw/server'
import ClinicalPatientRedFlagsView from '@/views/clinical/ClinicalPatientRedFlagsView.vue'
import en from '@/i18n/en.json'

const i18n = createI18n({ legacy: false, locale: 'en', messages: { en } })

function event(eventId: number, severity: string, current: boolean) {
  return {
    eventId,
    ruleKey: 'LAB_CRP_HIGH',
    severity,
    detectedAt: '2026-08-01T10:15:30Z',
    sourceType: 'LAB_RESULT_SET',
    sourceId: 91,
    current,
    supersededAt: current ? null : '2026-08-02T10:15:30Z',
    ruleVersion: 1,
  }
}

describe('ClinicalPatientRedFlagsView', () => {
  beforeEach(() => setActivePinia(createPinia()))

  it('renders the current snapshot and paginates history with the cursor', async () => {
    const historyUrls: string[] = []
    server.use(
      http.get('/api/clinical/patients/41/red-flags/current', () =>
        HttpResponse.json({ highestSeverity: 'URGENT_REVIEW', flags: [event(701, 'URGENT_REVIEW', true)] }),
      ),
      http.get('/api/clinical/patients/41/red-flags/history', ({ request }) => {
        historyUrls.push(request.url)
        if (!request.url.includes('cursor=')) {
          return HttpResponse.json({ items: [event(701, 'URGENT_REVIEW', true)], nextCursor: 'abc' })
        }
        return HttpResponse.json({ items: [event(700, 'ROUTINE_REVIEW', false)], nextCursor: null })
      }),
    )
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/clinical/patients/:patientProfileId/red-flags', component: ClinicalPatientRedFlagsView }],
    })
    await router.push('/clinical/patients/41/red-flags')
    const wrapper = mount(ClinicalPatientRedFlagsView, { global: { plugins: [createPinia(), i18n, router] } })
    await flushPromises()

    expect(wrapper.find('[data-testid="current-table"]').text()).toContain(en.redFlags.severity.URGENT_REVIEW)
    expect(wrapper.findAll('[data-testid="history-row"]')).toHaveLength(1)

    await wrapper.find('[data-testid="load-more"]').trigger('click')
    await flushPromises()
    expect(historyUrls[1]).toContain('cursor=abc')
    expect(wrapper.findAll('[data-testid="history-row"]')).toHaveLength(2)
    expect(wrapper.find('[data-testid="load-more"]').exists()).toBe(false)
  })
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && npx vitest run tests/views/clinical/ClinicalPatientRedFlagsView.test.ts`
Expected: FAIL — the view does not exist.

- [ ] **Step 3: Write the view**

```vue
<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { clinicalApi } from '@/api/clinical'
import { useApiError } from '@/composables/useApiError'
import { dateRangeError } from '@/utils/dateRange'
import { formatDateTime } from '@/utils/dateTime'
import { severityBadgeClass } from '@/utils/redFlags'
import type { ClinicalRedFlagEvent, ClinicalRedFlagSnapshot, RedFlagSeverity } from '@/types/api'

const { t, te, locale } = useI18n()
const route = useRoute()
const { message, capture, clear } = useApiError()

const patientProfileId = Number(route.params.patientProfileId)

function iso(d: Date): string {
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

const today = new Date()
const monthAgo = new Date(today)
monthAgo.setDate(monthAgo.getDate() - 30)

const snapshot = ref<ClinicalRedFlagSnapshot | null>(null)
const snapshotFailed = ref(false)
const from = ref(iso(monthAgo))
const to = ref(iso(today))
const severity = ref<RedFlagSeverity | ''>('')
const appliedFrom = ref(from.value)
const appliedTo = ref(to.value)
const appliedSeverity = ref<RedFlagSeverity | ''>(severity.value)
const items = ref<ClinicalRedFlagEvent[]>([])
const nextCursor = ref<string | null>(null)
const loading = ref(true)
const loadingMore = ref(false)

const severityOptions: RedFlagSeverity[] = ['ROUTINE_REVIEW', 'URGENT_REVIEW', 'EMERGENCY']

function ruleLabel(ruleKey: string): string {
  const key = `redFlags.rules.${ruleKey}`
  return te(key) ? t(key) : ruleKey
}

let historyGeneration = 0

async function loadSnapshot() {
  try {
    snapshot.value = await clinicalApi.currentRedFlags(patientProfileId)
  } catch {
    snapshotFailed.value = true
  }
}

async function load() {
  clear()
  const rangeError = dateRangeError(from.value, to.value, 369)
  if (rangeError) {
    message.value = t(`errors.date_range_${rangeError === 'too_long' ? 'too_long' : 'invalid'}`)
    return
  }
  // Capture before the await: controls edited mid-flight must not leak into
  // the cursor bookkeeping, and a stale response must not overwrite a newer one.
  const requestFrom = from.value
  const requestTo = to.value
  const requestSeverity = severity.value
  const gen = ++historyGeneration
  loading.value = true
  loadingMore.value = false
  items.value = []
  nextCursor.value = null
  try {
    const page = await clinicalApi.redFlagHistory(patientProfileId, {
      from: requestFrom,
      to: requestTo,
      severity: requestSeverity || undefined,
      size: 25,
    })
    if (gen !== historyGeneration) return
    items.value = page.items
    nextCursor.value = page.nextCursor
    appliedFrom.value = requestFrom
    appliedTo.value = requestTo
    appliedSeverity.value = requestSeverity
  } catch (e) {
    if (gen !== historyGeneration) return
    capture(e)
  } finally {
    if (gen === historyGeneration) loading.value = false
  }
}

async function loadMore() {
  if (!nextCursor.value) return
  clear()
  const gen = historyGeneration
  loadingMore.value = true
  try {
    const page = await clinicalApi.redFlagHistory(patientProfileId, {
      from: appliedFrom.value,
      to: appliedTo.value,
      severity: appliedSeverity.value || undefined,
      cursor: nextCursor.value,
      size: 25,
    })
    if (gen !== historyGeneration) return
    items.value = [...items.value, ...page.items]
    nextCursor.value = page.nextCursor
  } catch (e) {
    if (gen !== historyGeneration) return
    capture(e)
  } finally {
    if (gen === historyGeneration) loadingMore.value = false
  }
}

onMounted(() => {
  void loadSnapshot()
  void load()
})
</script>

<template>
  <section class="mt-4">
    <h2 class="text-lg font-medium">{{ t('redFlags.currentTitle') }}</h2>
    <p v-if="snapshotFailed" class="mt-2 rounded bg-red-50 p-3 text-sm text-red-700 dark:bg-red-950 dark:text-red-300">
      {{ t('redFlags.currentLoadFailed') }}
    </p>
    <p v-else-if="!snapshot" class="mt-2">{{ t('common.loading') }}</p>
    <p v-else-if="snapshot.flags.length === 0" class="mt-2 text-sm">{{ t('redFlags.noCurrent') }}</p>
    <table v-else data-testid="current-table" class="mt-2 w-full border-collapse bg-white text-sm dark:bg-gray-800">
      <thead>
        <tr class="border-b text-left">
          <th class="p-2">{{ t('redFlags.rule') }}</th>
          <th class="p-2">{{ t('redFlags.severityHeader') }}</th>
          <th class="p-2">{{ t('redFlags.detected') }}</th>
          <th class="p-2">{{ t('redFlags.source') }}</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="f in snapshot.flags" :key="f.eventId" class="border-b">
          <td class="p-2">{{ ruleLabel(f.ruleKey) }}</td>
          <td class="p-2">
            <span class="rounded px-2 py-0.5" :class="severityBadgeClass(f.severity)">
              {{ t(`redFlags.severity.${f.severity}`) }}
            </span>
          </td>
          <td class="p-2">{{ formatDateTime(f.detectedAt, locale) }}</td>
          <td class="p-2">{{ t(`redFlags.sourceType.${f.sourceType}`) }}</td>
        </tr>
      </tbody>
    </table>

    <h2 class="mt-6 text-lg font-medium">{{ t('redFlags.historyTitle') }}</h2>
    <div class="mt-2 flex flex-wrap items-end gap-3">
      <label class="text-sm">{{ t('common.from') }}
        <input v-model="from" type="date" class="ml-1 rounded border border-gray-300 px-2 py-1 dark:border-gray-600 dark:bg-gray-800" />
      </label>
      <label class="text-sm">{{ t('common.to') }}
        <input v-model="to" type="date" class="ml-1 rounded border border-gray-300 px-2 py-1 dark:border-gray-600 dark:bg-gray-800" />
      </label>
      <label class="text-sm">{{ t('redFlags.severityHeader') }}
        <select v-model="severity" class="ml-1 rounded border border-gray-300 px-2 py-1 dark:border-gray-600 dark:bg-gray-800">
          <option value="">{{ t('redFlags.severityAll') }}</option>
          <option v-for="s in severityOptions" :key="s" :value="s">{{ t(`redFlags.severity.${s}`) }}</option>
        </select>
      </label>
      <button class="rounded bg-blue-600 px-3 py-1 text-sm text-white" @click="load">{{ t('common.apply') }}</button>
    </div>

    <p v-if="message" class="mt-4 rounded bg-red-50 p-3 text-sm text-red-700 dark:bg-red-950 dark:text-red-300">{{ message }}</p>
    <p v-if="loading" class="mt-4">{{ t('common.loading') }}</p>
    <template v-else>
      <table class="mt-4 w-full border-collapse bg-white text-sm dark:bg-gray-800">
        <thead>
          <tr class="border-b text-left">
            <th class="p-2">{{ t('redFlags.rule') }}</th>
            <th class="p-2">{{ t('redFlags.severityHeader') }}</th>
            <th class="p-2">{{ t('redFlags.detected') }}</th>
            <th class="p-2">{{ t('redFlags.source') }}</th>
            <th class="p-2">{{ t('redFlags.status') }}</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="f in items" :key="f.eventId" data-testid="history-row" class="border-b">
            <td class="p-2">{{ ruleLabel(f.ruleKey) }}</td>
            <td class="p-2">
              <span class="rounded px-2 py-0.5" :class="severityBadgeClass(f.severity)">
                {{ t(`redFlags.severity.${f.severity}`) }}
              </span>
            </td>
            <td class="p-2">{{ formatDateTime(f.detectedAt, locale) }}</td>
            <td class="p-2">{{ t(`redFlags.sourceType.${f.sourceType}`) }}</td>
            <td class="p-2">{{ f.current ? t('redFlags.statusCurrent') : t('redFlags.statusSuperseded') }}</td>
          </tr>
        </tbody>
      </table>
      <button v-if="nextCursor" data-testid="load-more" :disabled="loadingMore"
              class="mt-3 rounded border px-3 py-1 text-sm" @click="loadMore">
        {{ t('redFlags.loadMore') }}
      </button>
    </template>
  </section>
</template>
```

- [ ] **Step 4: Swap the route to the real view**

In `frontend/src/router/index.ts`, replace `{ path: 'red-flags', component: ClinicalStubView },` with:

```ts
          { path: 'red-flags', component: ClinicalPatientRedFlagsView },
```

and add `import ClinicalPatientRedFlagsView from '@/views/clinical/ClinicalPatientRedFlagsView.vue'`.

- [ ] **Step 5: Run tests to verify they pass**

Run: `cd frontend && npx vitest run tests/views/clinical/ClinicalPatientRedFlagsView.test.ts tests/router`
Expected: PASS. Also `npm run typecheck`.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/views/clinical/ClinicalPatientRedFlagsView.vue \
        frontend/src/router/index.ts \
        frontend/tests/views/clinical/ClinicalPatientRedFlagsView.test.ts
git commit -m "Add clinical red flags tab"
```

---

### Task 15: Frontend — onboarding review panel, queue, and review view

The review workflow: a shared panel (read-only submission display + decision form) used
by the cross-patient queue's detail route and, in Task 16, by the workspace tab. The
decision form shows only while the submission is `PENDING_REVIEW`; the backend enforces
`REVIEWED` / `NEEDS_FOLLOW_UP` as the only actionable statuses.

**Files:**
- Create: `frontend/src/views/clinical/ClinicalOnboardingReviewPanel.vue`
- Create: `frontend/src/views/clinical/ClinicalOnboardingQueueView.vue`
- Create: `frontend/src/views/clinical/ClinicalOnboardingReviewView.vue`
- Modify: `frontend/src/router/index.ts` (swap the `onboarding` and `onboarding/:submissionId` children from `ClinicalStubView`)
- Test: `frontend/tests/views/clinical/ClinicalOnboarding.test.ts`

**Interfaces:**
- Consumes: `clinicalApi.listOnboardingSubmissions/getOnboardingSubmission/reviewOnboardingSubmission` (Task 4); `formatDateTime`; i18n `onboarding.*`, `account.dateOfBirth/sex/countryRegion/timezone`, `sex.*`, `clinical.*`.
- Produces: `ClinicalOnboardingReviewPanel` with props `{ submissionId: number }` and emit `reviewed` — consumed by Task 16's workspace tab; navigation `/clinical/onboarding/{id}`.

- [ ] **Step 1: Write the failing test**

```ts
import { flushPromises, mount } from '@vue/test-utils'
import { http, HttpResponse } from 'msw'
import { beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { createI18n } from 'vue-i18n'
import { createRouter, createMemoryHistory } from 'vue-router'
import { server } from '../../msw/server'
import ClinicalOnboardingQueueView from '@/views/clinical/ClinicalOnboardingQueueView.vue'
import ClinicalOnboardingReviewView from '@/views/clinical/ClinicalOnboardingReviewView.vue'
import en from '@/i18n/en.json'

const i18n = createI18n({ legacy: false, locale: 'en', messages: { en } })

const submission = {
  id: 9,
  patientProfileId: 41,
  patientEmail: 'patient@example.com',
  onboardingContext: 'baseline',
  version: 1,
  createdAt: '2026-08-01T09:00:00Z',
  submittedAt: '2026-08-01T09:00:00Z',
  dateOfBirth: '1990-05-12',
  sex: 'FEMALE',
  countryRegion: 'CZ',
  timezone: 'Europe/Prague',
  diagnosisType: 'CROHNS_DISEASE',
  diagnosisYear: 2019,
  diseaseLocation: 'ileum',
  diseaseBehavior: 'inflammatory',
  activityEstimate: 'MILD',
  currentMedications: 'mesalazine',
  steroidUse: 'NONE',
  advancedTherapyExposure: 'NEVER_USED',
  medicationNotes: null,
  labsCollectedAt: '2026-07-20',
  crpMgL: 3.2,
  fecalCalprotectinUgG: 180,
  hemoglobinGDl: 13.5,
  albuminGDl: 4.1,
  labNotes: null,
  reviewStatus: 'PENDING_REVIEW',
}

const summary = {
  id: 9,
  patientProfileId: 41,
  patientEmail: 'patient@example.com',
  onboardingContext: 'baseline',
  version: 1,
  submittedAt: '2026-08-01T09:00:00Z',
  diagnosisType: 'CROHNS_DISEASE',
  reviewStatus: 'PENDING_REVIEW',
}

function makeRouter() {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/clinical/onboarding', component: ClinicalOnboardingQueueView },
      { path: '/clinical/onboarding/:submissionId', component: ClinicalOnboardingReviewView },
    ],
  })
}

describe('clinical onboarding review', () => {
  beforeEach(() => setActivePinia(createPinia()))

  it('lists the queue with a status filter and opens the review view', async () => {
    let seenUrl = ''
    server.use(
      http.get('/api/clinical/onboarding/submissions', ({ request }) => {
        seenUrl = request.url
        return HttpResponse.json([summary])
      }),
    )
    const router = makeRouter()
    await router.push('/clinical/onboarding')
    const wrapper = mount(ClinicalOnboardingQueueView, { global: { plugins: [createPinia(), i18n, router] } })
    await flushPromises()

    expect(wrapper.text()).toContain('patient@example.com')
    await wrapper.find('[data-testid="status-filter"]').setValue('PENDING_REVIEW')
    await wrapper.find('[data-testid="apply-filter"]').trigger('click')
    await flushPromises()
    expect(seenUrl).toContain('status=PENDING_REVIEW')

    await wrapper.find('[data-testid="queue-row"]').trigger('click')
    expect(router.currentRoute.value.path).toBe('/clinical/onboarding/9')
  })

  it('renders the submission and submits a review', async () => {
    let received: unknown
    server.use(
      http.get('/api/clinical/onboarding/submissions/9', () => HttpResponse.json(submission)),
      http.get('/api/csrf', () => HttpResponse.json({ token: 't', headerName: 'X-XSRF-TOKEN' })),
      http.post('/api/clinical/onboarding/submissions/9/review', async ({ request }) => {
        received = await request.json()
        return HttpResponse.json({ ...submission, reviewStatus: 'REVIEWED' })
      }),
    )
    const router = makeRouter()
    await router.push('/clinical/onboarding/9')
    const wrapper = mount(ClinicalOnboardingReviewView, { global: { plugins: [createPinia(), i18n, router] } })
    await flushPromises()

    expect(wrapper.text()).toContain(en.onboarding.diagnosis.CROHNS_DISEASE)
    expect(wrapper.text()).toContain('mesalazine')

    await wrapper.find('[data-testid="review-notes"]').setValue('looks fine')
    await wrapper.find('[data-testid="submit-review"]').trigger('click')
    await flushPromises()

    expect(received).toEqual({ reviewStatus: 'REVIEWED', reviewNotes: 'looks fine' })
    expect(wrapper.find('[data-testid="submit-review"]').exists()).toBe(false)
    expect(wrapper.text()).toContain(en.onboarding.reviewStatus.REVIEWED)
  })

  it('hides the decision form for an already-reviewed submission', async () => {
    server.use(
      http.get('/api/clinical/onboarding/submissions/9', () =>
        HttpResponse.json({ ...submission, reviewStatus: 'NEEDS_FOLLOW_UP' }),
      ),
    )
    const router = makeRouter()
    await router.push('/clinical/onboarding/9')
    const wrapper = mount(ClinicalOnboardingReviewView, { global: { plugins: [createPinia(), i18n, router] } })
    await flushPromises()

    expect(wrapper.find('[data-testid="submit-review"]').exists()).toBe(false)
    expect(wrapper.text()).toContain(en.clinical.alreadyReviewed)
  })
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && npx vitest run tests/views/clinical/ClinicalOnboarding.test.ts`
Expected: FAIL — the views do not exist.

- [ ] **Step 3: Write the review panel**

```vue
<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { clinicalApi } from '@/api/clinical'
import { useApiError } from '@/composables/useApiError'
import { formatDateTime } from '@/utils/dateTime'
import type { OnboardingSubmissionResponse } from '@/types/api'

const props = defineProps<{ submissionId: number }>()
const emit = defineEmits<(e: 'reviewed') => void>()

const { t, locale } = useI18n()
const { message, capture, clear } = useApiError()

const submission = ref<OnboardingSubmissionResponse | null>(null)
const loading = ref(true)
const decision = ref<'REVIEWED' | 'NEEDS_FOLLOW_UP'>('REVIEWED')
const reviewNotes = ref('')
const submitting = ref(false)

function val(v: string | number | null | undefined): string {
  return v === null || v === undefined || v === '' ? t('onboarding.notProvided') : String(v)
}

async function load() {
  clear()
  loading.value = true
  try {
    submission.value = await clinicalApi.getOnboardingSubmission(props.submissionId)
  } catch (e) {
    capture(e)
  } finally {
    loading.value = false
  }
}

async function submitReview() {
  clear()
  submitting.value = true
  try {
    submission.value = await clinicalApi.reviewOnboardingSubmission(props.submissionId, {
      reviewStatus: decision.value,
      reviewNotes: reviewNotes.value || undefined,
    })
    emit('reviewed')
  } catch (e) {
    capture(e)
  } finally {
    submitting.value = false
  }
}

onMounted(load)
watch(() => props.submissionId, load)
</script>

<template>
  <section class="rounded border bg-white p-4 dark:bg-gray-800">
    <p v-if="message" class="rounded bg-red-50 p-3 text-sm text-red-700 dark:bg-red-950 dark:text-red-300">{{ message }}</p>
    <p v-if="loading">{{ t('common.loading') }}</p>
    <template v-else-if="submission">
      <dl class="grid grid-cols-1 gap-x-6 gap-y-2 text-sm sm:grid-cols-2">
        <div><dt class="inline font-medium">{{ t('clinical.colPatient') }}: </dt><dd class="inline">{{ submission.patientEmail }}</dd></div>
        <div><dt class="inline font-medium">{{ t('clinical.colSubmitted') }}: </dt><dd class="inline">{{ formatDateTime(submission.submittedAt, locale) }}</dd></div>
        <div><dt class="inline font-medium">{{ t('account.dateOfBirth') }}: </dt><dd class="inline">{{ val(submission.dateOfBirth) }}</dd></div>
        <div><dt class="inline font-medium">{{ t('account.sex') }}: </dt><dd class="inline">{{ submission.sex ? t(`sex.${submission.sex}`) : t('onboarding.notProvided') }}</dd></div>
        <div><dt class="inline font-medium">{{ t('account.countryRegion') }}: </dt><dd class="inline">{{ val(submission.countryRegion) }}</dd></div>
        <div><dt class="inline font-medium">{{ t('account.timezone') }}: </dt><dd class="inline">{{ val(submission.timezone) }}</dd></div>
        <div><dt class="inline font-medium">{{ t('onboarding.diagnosisType') }}: </dt><dd class="inline">{{ t(`onboarding.diagnosis.${submission.diagnosisType}`) }}</dd></div>
        <div><dt class="inline font-medium">{{ t('onboarding.diagnosisYear') }}: </dt><dd class="inline">{{ val(submission.diagnosisYear) }}</dd></div>
        <div><dt class="inline font-medium">{{ t('onboarding.diseaseLocation') }}: </dt><dd class="inline">{{ val(submission.diseaseLocation) }}</dd></div>
        <div><dt class="inline font-medium">{{ t('onboarding.diseaseBehavior') }}: </dt><dd class="inline">{{ val(submission.diseaseBehavior) }}</dd></div>
        <div><dt class="inline font-medium">{{ t('onboarding.activityEstimate') }}: </dt><dd class="inline">{{ t(`onboarding.activity.${submission.activityEstimate}`) }}</dd></div>
        <div><dt class="inline font-medium">{{ t('onboarding.currentMedications') }}: </dt><dd class="inline">{{ val(submission.currentMedications) }}</dd></div>
        <div><dt class="inline font-medium">{{ t('onboarding.steroidUse') }}: </dt><dd class="inline">{{ t(`onboarding.steroid.${submission.steroidUse}`) }}</dd></div>
        <div><dt class="inline font-medium">{{ t('onboarding.advancedTherapy') }}: </dt><dd class="inline">{{ t(`onboarding.therapy.${submission.advancedTherapyExposure}`) }}</dd></div>
        <div><dt class="inline font-medium">{{ t('onboarding.medicationNotes') }}: </dt><dd class="inline">{{ val(submission.medicationNotes) }}</dd></div>
        <div><dt class="inline font-medium">{{ t('onboarding.labsCollectedAt') }}: </dt><dd class="inline">{{ val(submission.labsCollectedAt) }}</dd></div>
        <div><dt class="inline font-medium">{{ t('clinical.crpMgL') }}: </dt><dd class="inline">{{ val(submission.crpMgL) }}</dd></div>
        <div><dt class="inline font-medium">{{ t('onboarding.calprotectin') }}: </dt><dd class="inline">{{ val(submission.fecalCalprotectinUgG) }}</dd></div>
        <div><dt class="inline font-medium">{{ t('onboarding.hemoglobin') }}: </dt><dd class="inline">{{ val(submission.hemoglobinGDl) }}</dd></div>
        <div><dt class="inline font-medium">{{ t('onboarding.albumin') }}: </dt><dd class="inline">{{ val(submission.albuminGDl) }}</dd></div>
      </dl>

      <p class="mt-4 text-sm">
        {{ t('clinical.colStatus') }}:
        <strong>{{ t(`onboarding.reviewStatus.${submission.reviewStatus}`) }}</strong>
      </p>
      <template v-if="submission.reviewStatus === 'PENDING_REVIEW'">
        <label class="mt-3 block text-sm font-medium">{{ t('clinical.reviewDecision') }}</label>
        <select v-model="decision" data-testid="review-decision"
                class="mt-1 rounded border border-gray-300 px-2 py-1 dark:border-gray-600 dark:bg-gray-800">
          <option value="REVIEWED">{{ t('onboarding.reviewStatus.REVIEWED') }}</option>
          <option value="NEEDS_FOLLOW_UP">{{ t('onboarding.reviewStatus.NEEDS_FOLLOW_UP') }}</option>
        </select>
        <label class="mt-3 block text-sm font-medium">{{ t('clinical.reviewNotes') }}</label>
        <textarea v-model="reviewNotes" data-testid="review-notes" rows="3"
                  class="mt-1 w-full rounded border border-gray-300 px-3 py-2 dark:border-gray-600 dark:bg-gray-800" />
        <button data-testid="submit-review" :disabled="submitting"
                class="mt-3 rounded bg-blue-600 px-4 py-2 text-white disabled:opacity-50"
                @click="submitReview">
          {{ t('clinical.submitReview') }}
        </button>
      </template>
      <p v-else class="mt-3 text-sm text-gray-600 dark:text-gray-400">{{ t('clinical.alreadyReviewed') }}</p>
    </template>
  </section>
</template>
```

- [ ] **Step 4: Write the queue view and review view**

`frontend/src/views/clinical/ClinicalOnboardingQueueView.vue`:

```vue
<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { clinicalApi } from '@/api/clinical'
import { useApiError } from '@/composables/useApiError'
import { formatDateTime } from '@/utils/dateTime'
import type { OnboardingReviewStatus, OnboardingSubmissionSummary } from '@/types/api'

const { t, locale } = useI18n()
const router = useRouter()
const { message, capture, clear } = useApiError()

const status = ref<OnboardingReviewStatus | ''>('')
const items = ref<OnboardingSubmissionSummary[]>([])
const loading = ref(true)

const statusOptions: OnboardingReviewStatus[] = ['PENDING_REVIEW', 'REVIEWED', 'NEEDS_FOLLOW_UP']

async function load() {
  clear()
  loading.value = true
  try {
    items.value = await clinicalApi.listOnboardingSubmissions(undefined, status.value || undefined)
  } catch (e) {
    capture(e)
  } finally {
    loading.value = false
  }
}

function open(item: OnboardingSubmissionSummary) {
  void router.push(`/clinical/onboarding/${item.id}`)
}

onMounted(load)
</script>

<template>
  <section>
    <h1 class="text-2xl font-semibold">{{ t('clinical.queueTitle') }}</h1>
    <div class="mt-2 flex flex-wrap items-end gap-3">
      <label class="text-sm">{{ t('clinical.colStatus') }}
        <select v-model="status" data-testid="status-filter"
                class="ml-1 rounded border border-gray-300 px-2 py-1 dark:border-gray-600 dark:bg-gray-800">
          <option value="">{{ t('clinical.allStatuses') }}</option>
          <option v-for="s in statusOptions" :key="s" :value="s">{{ t(`onboarding.reviewStatus.${s}`) }}</option>
        </select>
      </label>
      <button data-testid="apply-filter" class="rounded bg-blue-600 px-3 py-1 text-sm text-white" @click="load">
        {{ t('common.apply') }}
      </button>
    </div>

    <p v-if="message" class="mt-4 rounded bg-red-50 p-3 text-sm text-red-700 dark:bg-red-950 dark:text-red-300">{{ message }}</p>
    <p v-if="loading" class="mt-4">{{ t('common.loading') }}</p>
    <table v-else class="mt-4 w-full border-collapse bg-white text-sm dark:bg-gray-800">
      <thead>
        <tr class="border-b text-left">
          <th class="p-2">{{ t('clinical.colPatient') }}</th>
          <th class="p-2">{{ t('clinical.colSubmitted') }}</th>
          <th class="p-2">{{ t('clinical.colVersion') }}</th>
          <th class="p-2">{{ t('onboarding.diagnosisType') }}</th>
          <th class="p-2">{{ t('clinical.colStatus') }}</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="item in items" :key="item.id" data-testid="queue-row"
            class="cursor-pointer border-b hover:bg-gray-50 dark:hover:bg-gray-700"
            @click="open(item)">
          <td class="p-2">{{ item.patientEmail }}</td>
          <td class="p-2">{{ formatDateTime(item.submittedAt, locale) }}</td>
          <td class="p-2">{{ item.version }}</td>
          <td class="p-2">{{ t(`onboarding.diagnosis.${item.diagnosisType}`) }}</td>
          <td class="p-2">{{ t(`onboarding.reviewStatus.${item.reviewStatus}`) }}</td>
        </tr>
      </tbody>
    </table>
  </section>
</template>
```

`frontend/src/views/clinical/ClinicalOnboardingReviewView.vue`:

```vue
<script setup lang="ts">
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import ClinicalOnboardingReviewPanel from '@/views/clinical/ClinicalOnboardingReviewPanel.vue'

const { t } = useI18n()
const route = useRoute()
const router = useRouter()

const submissionId = Number(route.params.submissionId)

async function backToQueue() {
  await router.push('/clinical/onboarding')
}
</script>

<template>
  <section>
    <router-link to="/clinical/onboarding" class="text-sm text-blue-600 dark:text-blue-400">← {{ t('clinical.backToQueue') }}</router-link>
    <h1 class="mb-4 mt-2 text-2xl font-semibold">{{ t('clinical.reviewTitle') }}</h1>
    <ClinicalOnboardingReviewPanel :submission-id="submissionId" @reviewed="backToQueue" />
  </section>
</template>
```

- [ ] **Step 5: Swap the routes to the real views**

In `frontend/src/router/index.ts`, replace the top-level clinical onboarding children:

```ts
      { path: 'onboarding', component: ClinicalOnboardingQueueView },
      { path: 'onboarding/:submissionId', component: ClinicalOnboardingReviewView },
```

and add the imports for both views.

- [ ] **Step 6: Run tests to verify they pass**

Run: `cd frontend && npx vitest run tests/views/clinical/ClinicalOnboarding.test.ts tests/router`
Expected: PASS. Also `npm run typecheck`.

- [ ] **Step 7: Commit**

```bash
git add frontend/src/views/clinical/ClinicalOnboardingReviewPanel.vue \
        frontend/src/views/clinical/ClinicalOnboardingQueueView.vue \
        frontend/src/views/clinical/ClinicalOnboardingReviewView.vue \
        frontend/src/router/index.ts \
        frontend/tests/views/clinical/ClinicalOnboarding.test.ts
git commit -m "Add clinical onboarding review queue and panel"
```

---

### Task 16: Frontend — workspace onboarding tab + stub cleanup

The per-patient onboarding history inside the workspace: reuses the reviewable list
endpoint filtered client-side by the route's `patientProfileId` (the endpoint has no
patient filter; the result set is bounded by the staff member's assignments) and embeds
Task 15's panel inline. After a review, the list reloads. Also deletes the last
`ClinicalStubView` usages — every clinical route now points at a real view.

**Files:**
- Create: `frontend/src/views/clinical/ClinicalPatientOnboardingView.vue`
- Modify: `frontend/src/router/index.ts` (swap the workspace `onboarding` child; remove the `ClinicalStubView` import)
- Delete: `frontend/src/views/clinical/ClinicalStubView.vue`
- Test: `frontend/tests/views/clinical/ClinicalPatientOnboardingView.test.ts`

**Interfaces:**
- Consumes: `clinicalApi.listOnboardingSubmissions()`; `ClinicalOnboardingReviewPanel` (Task 15) with `submissionId` prop + `reviewed` emit.
- Produces: nothing — final clinical view.

- [ ] **Step 1: Write the failing test**

```ts
import { flushPromises, mount } from '@vue/test-utils'
import { http, HttpResponse } from 'msw'
import { beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { createI18n } from 'vue-i18n'
import { createRouter, createMemoryHistory } from 'vue-router'
import { server } from '../../msw/server'
import ClinicalPatientOnboardingView from '@/views/clinical/ClinicalPatientOnboardingView.vue'
import en from '@/i18n/en.json'

const i18n = createI18n({ legacy: false, locale: 'en', messages: { en } })

const summaries = [
  {
    id: 9,
    patientProfileId: 41,
    patientEmail: 'patient@example.com',
    onboardingContext: 'baseline',
    version: 1,
    submittedAt: '2026-08-01T09:00:00Z',
    diagnosisType: 'CROHNS_DISEASE',
    reviewStatus: 'PENDING_REVIEW',
  },
  {
    id: 10,
    patientProfileId: 42,
    patientEmail: 'other@example.com',
    onboardingContext: 'baseline',
    version: 2,
    submittedAt: '2026-08-02T09:00:00Z',
    diagnosisType: 'ULCERATIVE_COLITIS',
    reviewStatus: 'REVIEWED',
  },
]

const submission = {
  id: 9,
  patientProfileId: 41,
  patientEmail: 'patient@example.com',
  onboardingContext: 'baseline',
  version: 1,
  createdAt: '2026-08-01T09:00:00Z',
  submittedAt: '2026-08-01T09:00:00Z',
  dateOfBirth: null,
  sex: null,
  countryRegion: null,
  timezone: null,
  diagnosisType: 'CROHNS_DISEASE',
  diagnosisYear: 2019,
  diseaseLocation: null,
  diseaseBehavior: null,
  activityEstimate: 'MILD',
  currentMedications: null,
  steroidUse: 'NONE',
  advancedTherapyExposure: 'NEVER_USED',
  medicationNotes: null,
  labsCollectedAt: null,
  crpMgL: null,
  fecalCalprotectinUgG: null,
  hemoglobinGDl: null,
  albuminGDl: null,
  labNotes: null,
  reviewStatus: 'PENDING_REVIEW',
}

describe('ClinicalPatientOnboardingView', () => {
  beforeEach(() => setActivePinia(createPinia()))

  it('lists only the workspace patient and embeds the review panel on selection', async () => {
    server.use(
      http.get('/api/clinical/onboarding/submissions', () => HttpResponse.json(summaries)),
      http.get('/api/clinical/onboarding/submissions/9', () => HttpResponse.json(submission)),
    )
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/clinical/patients/:patientProfileId/onboarding', component: ClinicalPatientOnboardingView }],
    })
    await router.push('/clinical/patients/41/onboarding')
    const wrapper = mount(ClinicalPatientOnboardingView, { global: { plugins: [createPinia(), i18n, router] } })
    await flushPromises()

    const rows = wrapper.findAll('[data-testid="submission-row"]')
    expect(rows).toHaveLength(1)
    expect(rows[0].text()).toContain(en.onboarding.reviewStatus.PENDING_REVIEW)

    await rows[0].trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain(en.onboarding.diagnosis.CROHNS_DISEASE)
    expect(wrapper.find('[data-testid="submit-review"]').exists()).toBe(true)
  })
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && npx vitest run tests/views/clinical/ClinicalPatientOnboardingView.test.ts`
Expected: FAIL — the view does not exist.

- [ ] **Step 3: Write the view**

```vue
<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { clinicalApi } from '@/api/clinical'
import { useApiError } from '@/composables/useApiError'
import { formatDateTime } from '@/utils/dateTime'
import ClinicalOnboardingReviewPanel from '@/views/clinical/ClinicalOnboardingReviewPanel.vue'
import type { OnboardingSubmissionSummary } from '@/types/api'

const { t, locale } = useI18n()
const route = useRoute()
const { message, capture } = useApiError()

const patientProfileId = Number(route.params.patientProfileId)

const items = ref<OnboardingSubmissionSummary[]>([])
const selectedId = ref<number | null>(null)
const loading = ref(true)

const patientItems = computed(() => items.value.filter((item) => item.patientProfileId === patientProfileId))

async function load() {
  loading.value = true
  try {
    items.value = await clinicalApi.listOnboardingSubmissions()
  } catch (e) {
    capture(e)
  } finally {
    loading.value = false
  }
}

async function onReviewed() {
  selectedId.value = null
  await load()
}

onMounted(load)
</script>

<template>
  <section class="mt-4">
    <h2 class="text-lg font-medium">{{ t('clinical.tabOnboarding') }}</h2>

    <p v-if="message" class="mt-4 rounded bg-red-50 p-3 text-sm text-red-700 dark:bg-red-950 dark:text-red-300">{{ message }}</p>
    <p v-if="loading" class="mt-4">{{ t('common.loading') }}</p>
    <template v-else>
      <table class="mt-2 w-full border-collapse bg-white text-sm dark:bg-gray-800">
        <thead>
          <tr class="border-b text-left">
            <th class="p-2">{{ t('clinical.colSubmitted') }}</th>
            <th class="p-2">{{ t('clinical.colVersion') }}</th>
            <th class="p-2">{{ t('onboarding.diagnosisType') }}</th>
            <th class="p-2">{{ t('clinical.colStatus') }}</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="item in patientItems" :key="item.id" data-testid="submission-row"
              class="cursor-pointer border-b hover:bg-gray-50 dark:hover:bg-gray-700"
              @click="selectedId = item.id">
            <td class="p-2">{{ formatDateTime(item.submittedAt, locale) }}</td>
            <td class="p-2">{{ item.version }}</td>
            <td class="p-2">{{ t(`onboarding.diagnosis.${item.diagnosisType}`) }}</td>
            <td class="p-2">{{ t(`onboarding.reviewStatus.${item.reviewStatus}`) }}</td>
          </tr>
        </tbody>
      </table>
      <ClinicalOnboardingReviewPanel v-if="selectedId !== null" :key="selectedId"
                                     :submission-id="selectedId" class="mt-4"
                                     @reviewed="onReviewed" />
    </template>
  </section>
</template>
```

- [ ] **Step 4: Swap the route, remove the stub**

In `frontend/src/router/index.ts`, replace the workspace onboarding child:

```ts
          { path: 'onboarding', component: ClinicalPatientOnboardingView },
```

add `import ClinicalPatientOnboardingView from '@/views/clinical/ClinicalPatientOnboardingView.vue'`,
delete the `import ClinicalStubView from '@/views/clinical/ClinicalStubView.vue'` line, and
delete `frontend/src/views/clinical/ClinicalStubView.vue`. Verify no `ClinicalStubView`
references remain: `grep -rn ClinicalStubView frontend/src` → no output.

- [ ] **Step 5: Run tests to verify they pass**

Run: `cd frontend && npx vitest run tests/views/clinical tests/router`
Expected: PASS. Also `npm run typecheck`.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/views/clinical/ClinicalPatientOnboardingView.vue \
        frontend/src/router/index.ts \
        frontend/tests/views/clinical/ClinicalPatientOnboardingView.test.ts \
        && git rm -q --cached frontend/src/views/clinical/ClinicalStubView.vue 2>/dev/null; \
        rm -f frontend/src/views/clinical/ClinicalStubView.vue
git commit -m "Add workspace onboarding tab and remove routing stub"
```

---

### Task 17: Final verification

Full-suite green run on both sides, in order.

- [ ] **Step 1: Frontend**

Run: `cd frontend && npm run typecheck && npm run test`
Expected: typecheck clean; all Vitest suites PASS (including `tests/i18n/locale.test.ts`
parity and the untouched patient-area suites).

- [ ] **Step 2: Backend**

Run: `./gradlew test`
Expected: PASS (full JUnit suite incl. the two new controller tests and the overview
service test; Jacoco finalizes).

- [ ] **Step 3: Smoke check (manual, optional but recommended)**

1. `./gradlew bootRun` + `cd frontend && npm run dev`.
2. Log in on :5173 as a nutrition specialist or physician with at least one assigned
   patient: lands on `/clinical`, overview rows show badges; drill into a patient, walk
   all five tabs; enter a lab result set; submit an onboarding review.
3. Log in as a patient: lands on `/`, patient flows unchanged; `/clinical` redirects to `/`.
4. Log in as a coordinator: lands on `/staff-notice` linking to `/app`.

- [ ] **Step 4: Final commit (if any fixes were needed)**

```bash
git add -A
git commit -m "Verify expert clinical SPA end to end"
```
