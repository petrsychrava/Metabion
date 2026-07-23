# Patient Onboarding Submission Detail Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let patients view a read-only detail page (web + API) of any of their own onboarding submissions, and stop leaking reviewer notes/identity in patient-facing responses.

**Architecture:** Add a `toPatientView()` projection to `OnboardingSubmissionResponse` that nulls review internals, apply it to every patient-facing path in `OnboardingService`, and add an ownership-guarded `getOwnSubmissionById` service method consumed by a new web page (`GET /app/onboarding/{id}` + `onboarding-detail.html`) and REST endpoint (`GET /api/onboarding/submissions/{id}`). History rows and the "Latest baseline" panel link to the detail page.

**Tech Stack:** Java 25, Spring Boot 4 (Web MVC, Security, Data JPA), Thymeleaf, JUnit 5 + Mockito + AssertJ, Spring Security Test, Gradle wrapper.

**Spec:** `docs/superpowers/specs/2026-07-23-patient-onboarding-detail-design.md`

## Global Constraints

- Ownership/role enforcement stays in the service layer; do NOT change `SecurityConfig`.
- Patients must never see `reviewNotes`, `reviewedByEmail`, or `reviewedAt`; `reviewStatus` stays visible.
- A foreign or missing submission id must produce the same 404 `"Onboarding submission not found"` — no enumeration.
- Clinical paths (`getReviewable`, `review`, `listReviewable`) keep the full DTO.
- No entity, repository, Flyway, or `SecurityConfig` changes.
- User-facing text comes from `messages.properties` / `messages_cs.properties`; keep keys aligned across both bundles.
- Use `./gradlew` (not system Gradle) for all verification.
- Commit after each task with a concise imperative message.

---

### Task 1: Patient-view projection on `OnboardingSubmissionResponse`

**Files:**
- Modify: `src/main/java/com/metabion/dto/OnboardingSubmissionResponse.java`
- Test: `src/test/java/com/metabion/service/OnboardingServiceTest.java`

**Interfaces:**
- Consumes: existing record `OnboardingSubmissionResponse` and its static `from(OnboardingSubmission)`.
- Produces: `public OnboardingSubmissionResponse toPatientView()` — same record with `reviewedByEmail`, `reviewedAt`, `reviewNotes` nulled; all other components (including `reviewStatus`) unchanged. Used by Tasks 2–4.

- [ ] **Step 1: Write the failing test**

Add to `OnboardingServiceTest` (after `submissionResponseMapsAllSubmissionDetails`). The existing private helper `validSubmission()` builds a reviewed submission (reviewer `reviewer@example.com`, notes `"Reviewed"`).

```java
@Test
void patientViewStripsReviewInternalsButKeepsSubmittedData() {
    var submission = validSubmission();

    var response = OnboardingSubmissionResponse.from(submission).toPatientView();

    assertThat(response.reviewStatus()).isEqualTo(OnboardingReviewStatus.REVIEWED);
    assertThat(response.reviewedByEmail()).isNull();
    assertThat(response.reviewedAt()).isNull();
    assertThat(response.reviewNotes()).isNull();
    assertThat(response.diagnosisType()).isEqualTo(IbdDiagnosisType.CROHNS_DISEASE);
    assertThat(response.currentMedications()).isEqualTo("Mesalamine");
    assertThat(response.crpMgL()).isEqualByComparingTo("4.2");
    assertThat(response.version()).isEqualTo(2);
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests 'com.metabion.service.OnboardingServiceTest'`
Expected: compilation failure — `cannot find symbol: method toPatientView()`.

- [ ] **Step 3: Implement `toPatientView()`**

In `OnboardingSubmissionResponse.java`, add after the `from` method:

```java
public OnboardingSubmissionResponse toPatientView() {
    return new OnboardingSubmissionResponse(
            id,
            patientProfileId,
            patientEmail,
            onboardingContext,
            version,
            createdAt,
            submittedAt,
            dateOfBirth,
            sex,
            countryRegion,
            timezone,
            diagnosisType,
            diagnosisYear,
            diseaseLocation,
            diseaseBehavior,
            activityEstimate,
            currentMedications,
            steroidUse,
            advancedTherapyExposure,
            medicationNotes,
            labsCollectedAt,
            crpMgL,
            fecalCalprotectinUgG,
            hemoglobinGDl,
            albuminGDl,
            labNotes,
            reviewStatus,
            null,
            null,
            null);
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests 'com.metabion.service.OnboardingServiceTest'`
Expected: PASS (all tests in the class).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/metabion/dto/OnboardingSubmissionResponse.java src/test/java/com/metabion/service/OnboardingServiceTest.java
git commit -m "Add patient view projection for onboarding submissions"
```

---

### Task 2: `getOwnSubmissionById` + patient view on all patient-facing service paths

**Files:**
- Modify: `src/main/java/com/metabion/service/OnboardingService.java`
- Test: `src/test/java/com/metabion/service/OnboardingServiceTest.java`
- Test: `src/test/java/com/metabion/service/PatientAppFacadeTest.java`

**Interfaces:**
- Consumes: `toPatientView()` from Task 1; existing private helpers `currentPatientProfile(Authentication)` and `submissionOrNotFound(Long)` in `OnboardingService`.
- Produces: `public OnboardingSubmissionResponse getOwnSubmissionById(Authentication authentication, long submissionId)` — throws 404 `"Onboarding submission not found"` for missing or foreign submissions, 403 for non-patients; returns patient-view DTO. Consumed by Tasks 3 and 4. Also: `getLatestForCurrentPatient` and the private `submit` now return patient-view DTOs (callers unchanged — same type).

- [ ] **Step 1: Write the failing tests**

Add to `OnboardingServiceTest`. `validSubmission()` has `patientProfileId` 10L; `patientProfile(10L, patientUser)` matches it for the owner test.

```java
@Test
void ownSubmissionLookupReturnsPatientViewForOwner() {
    var patientUser = user(21L, "owner@example.com", RoleName.PATIENT);
    var patientProfile = patientProfile(10L, patientUser);
    when(users.findByEmail("owner@example.com")).thenReturn(Optional.of(patientUser));
    when(patientProfiles.findByUserId(21L)).thenReturn(Optional.of(patientProfile));
    when(submissions.findById(55L)).thenReturn(Optional.of(validSubmission()));

    var response = service.getOwnSubmissionById(auth("owner@example.com"), 55L);

    assertThat(response.diagnosisType()).isEqualTo(IbdDiagnosisType.CROHNS_DISEASE);
    assertThat(response.reviewStatus()).isEqualTo(OnboardingReviewStatus.REVIEWED);
    assertThat(response.reviewedByEmail()).isNull();
    assertThat(response.reviewedAt()).isNull();
    assertThat(response.reviewNotes()).isNull();
}

@Test
void ownSubmissionLookupRejectsForeignSubmission() {
    var patientUser = user(22L, "intruder@example.com", RoleName.PATIENT);
    var patientProfile = patientProfile(220L, patientUser);
    when(users.findByEmail("intruder@example.com")).thenReturn(Optional.of(patientUser));
    when(patientProfiles.findByUserId(22L)).thenReturn(Optional.of(patientProfile));
    when(submissions.findById(55L)).thenReturn(Optional.of(validSubmission()));

    assertThatThrownBy(() -> service.getOwnSubmissionById(auth("intruder@example.com"), 55L))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("404 NOT_FOUND")
            .hasMessageContaining("Onboarding submission not found");
}

@Test
void ownSubmissionLookupRejectsMissingSubmission() {
    var patientUser = user(23L, "missing@example.com", RoleName.PATIENT);
    var patientProfile = patientProfile(230L, patientUser);
    when(users.findByEmail("missing@example.com")).thenReturn(Optional.of(patientUser));
    when(patientProfiles.findByUserId(23L)).thenReturn(Optional.of(patientProfile));
    when(submissions.findById(55L)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.getOwnSubmissionById(auth("missing@example.com"), 55L))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("404 NOT_FOUND")
            .hasMessageContaining("Onboarding submission not found");
}

@Test
void ownSubmissionLookupRejectsNonPatient() {
    var doctor = user(24L, "doctor-read@example.com", RoleName.PHYSICIAN);
    when(users.findByEmail("doctor-read@example.com")).thenReturn(Optional.of(doctor));

    assertThatThrownBy(() -> service.getOwnSubmissionById(auth("doctor-read@example.com"), 55L))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("403 FORBIDDEN");
    verify(submissions, never()).findById(any());
}

@Test
void latestForCurrentPatientStripsReviewInternals() {
    var patientUser = user(25L, "latest@example.com", RoleName.PATIENT);
    var patientProfile = patientProfile(10L, patientUser);
    when(users.findByEmail("latest@example.com")).thenReturn(Optional.of(patientUser));
    when(patientProfiles.findByUserId(25L)).thenReturn(Optional.of(patientProfile));
    when(submissions.findFirstByPatientProfileIdAndOnboardingContextOrderByVersionDesc(10L, "default"))
            .thenReturn(Optional.of(validSubmission()));

    var response = service.getLatestForCurrentPatient(auth("latest@example.com"), "default");

    assertThat(response.reviewStatus()).isEqualTo(OnboardingReviewStatus.REVIEWED);
    assertThat(response.reviewedByEmail()).isNull();
    assertThat(response.reviewedAt()).isNull();
    assertThat(response.reviewNotes()).isNull();
}
```

Also add the MCP delegation characterization test to `PatientAppFacadeTest` (its `authentication` field and `onboarding` mock already exist; `verify` is statically imported). This test passes without production changes — it pins that the MCP path goes through the service and therefore inherits the patient view:

```java
@Test
void latestOnboardingDelegatesToOnboardingService() {
    facade.latestOnboarding(authentication, "default");

    verify(onboarding).getLatestForCurrentPatient(authentication, "default");
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests 'com.metabion.service.OnboardingServiceTest'`
Expected: compilation failure — `cannot find symbol: method getOwnSubmissionById`. Also `latestForCurrentPatientStripsReviewInternals` would fail on the not-yet-stripped fields once compilation is fixed in Step 3.

- [ ] **Step 3: Implement the service changes**

In `OnboardingService.java`:

a) Add after `getLatestForCurrentPatient`:

```java
public OnboardingSubmissionResponse getOwnSubmissionById(Authentication authentication, long submissionId) {
    var patient = currentPatientProfile(authentication);
    var submission = submissionOrNotFound(submissionId);
    if (!submission.getPatientProfile().getId().equals(patient.getId())) {
        throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                "Onboarding submission not found");
    }
    return OnboardingSubmissionResponse.from(submission).toPatientView();
}
```

b) In `getLatestForCurrentPatient`, replace the `.map(OnboardingSubmissionResponse::from)` line so the method reads:

```java
public OnboardingSubmissionResponse getLatestForCurrentPatient(Authentication authentication, String context) {
    var patient = currentPatientProfile(authentication);
    return submissions.findFirstByPatientProfileIdAndOnboardingContextOrderByVersionDesc(
                    patient.getId(),
                    normalizeContext(context))
            .map(submission -> OnboardingSubmissionResponse.from(submission).toPatientView())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Onboarding submission not found"));
}
```

c) In the private `submit`, change the return line to:

```java
return OnboardingSubmissionResponse.from(submissions.save(submission)).toPatientView();
```

(On a fresh submit the review fields are always null; this keeps every patient-facing response on the same projection.)

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests 'com.metabion.service.OnboardingServiceTest' --tests 'com.metabion.service.PatientAppFacadeTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/metabion/service/OnboardingService.java src/test/java/com/metabion/service/OnboardingServiceTest.java src/test/java/com/metabion/service/PatientAppFacadeTest.java
git commit -m "Guard patient onboarding detail lookup and strip review internals"
```

---

### Task 3: REST endpoint `GET /api/onboarding/submissions/{id}`

**Files:**
- Modify: `src/main/java/com/metabion/controller/api/OnboardingController.java`
- Test: `src/test/java/com/metabion/controller/api/OnboardingControllerTest.java`

**Interfaces:**
- Consumes: `OnboardingService.getOwnSubmissionById(Authentication, long)` from Task 2.
- Produces: `GET /api/onboarding/submissions/{id}` returning `OnboardingSubmissionResponse` (patient view). No conflict with `/api/onboarding/submissions/latest` — Spring prefers the exact-match route over the path variable.

- [ ] **Step 1: Write the failing tests**

Add to `OnboardingControllerTest`:

```java
@Test
void patientCanReadOwnSubmissionById() throws Exception {
    mvc.perform(get("/api/onboarding/submissions/55")
                    .with(user("patient@example.com").roles(RoleName.PATIENT.name())))
            .andExpect(status().isOk());

    verify(onboardingService).getOwnSubmissionById(any(), eq(55L));
}

@Test
void unauthenticatedSubmissionDetailIsUnauthorized() throws Exception {
    mvc.perform(get("/api/onboarding/submissions/55"))
            .andExpect(status().isUnauthorized());
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests 'com.metabion.controller.api.OnboardingControllerTest'`
Expected: compilation failure — `getOwnSubmissionById` does not exist on the mocked `OnboardingService`... it does exist after Task 2, so instead expect: `patientCanReadOwnSubmissionById` FAILS with `status expected:<200> but was:<404>` (no route). `unauthenticatedSubmissionDetailIsUnauthorized` FAILS expecting 401 but getting 404.

- [ ] **Step 3: Add the endpoint**

In `OnboardingController.java`, add after the `history` method:

```java
@GetMapping("/api/onboarding/submissions/{id}")
public OnboardingSubmissionResponse ownSubmission(@PathVariable Long id,
                                                  Authentication authentication) {
    return onboardingService.getOwnSubmissionById(authentication, id);
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests 'com.metabion.controller.api.OnboardingControllerTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/metabion/controller/api/OnboardingController.java src/test/java/com/metabion/controller/api/OnboardingControllerTest.java
git commit -m "Add patient onboarding submission detail endpoint"
```

---

### Task 4: Web page `GET /app/onboarding/{id}` + `onboarding-detail.html`

**Files:**
- Modify: `src/main/java/com/metabion/controller/web/WebOnboardingController.java`
- Create: `src/main/resources/templates/onboarding-detail.html`
- Test: `src/test/java/com/metabion/controller/web/WebOnboardingControllerTest.java`

**Interfaces:**
- Consumes: `OnboardingService.getOwnSubmissionById(Authentication, long)` from Task 2; private `addAppShell(Model, Authentication, String)` in the controller.
- Produces: web route `GET /app/onboarding/{id}` rendering view `onboarding-detail` with model attribute `submission` (`OnboardingSubmissionResponse`, patient view). The template reuses existing message keys only — no bundle changes in this task.

- [ ] **Step 1: Write the failing tests**

Add to `WebOnboardingControllerTest`, plus one new private helper `reviewedSubmissionResponse()` (a reviewed variant of the existing `fullSubmissionResponse()` so the "hides review internals" assertions have something to hide):

```java
@Test
void patientSubmissionDetailRendersOwnDataWithoutReviewInternals() throws Exception {
    when(onboardingService.getOwnSubmissionById(any(), eq(99L))).thenReturn(reviewedSubmissionResponse());

    mvc.perform(get("/app/onboarding/99")
                    .with(user("patient@example.com").roles(RoleName.PATIENT.name())))
            .andExpect(status().isOk())
            .andExpect(view().name("onboarding-detail"))
            .andExpect(content().string(containsString("1990-01-01")))
            .andExpect(content().string(containsString("Europe/Prague")))
            .andExpect(content().string(containsString("Ileocolonic")))
            .andExpect(content().string(containsString("Mesalamine")))
            .andExpect(content().string(containsString("4.2")))
            .andExpect(content().string(containsString("Recent outpatient labs")))
            .andExpect(content().string(not(containsString("Confidential reviewer note"))))
            .andExpect(content().string(not(containsString("reviewer@example.com"))))
            .andExpect(content().string(not(containsString("Review notes"))));
}

@Test
void patientSubmissionDetailRequiresAuthentication() throws Exception {
    mvc.perform(get("/app/onboarding/99"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/login"));
}

@Test
void patientSubmissionDetailNotFoundRendersWebError() throws Exception {
    doThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Onboarding submission not found"))
            .when(onboardingService).getOwnSubmissionById(any(), eq(99L));

    mvc.perform(get("/app/onboarding/99")
                    .with(user("patient@example.com").roles(RoleName.PATIENT.name())))
            .andExpect(status().isNotFound())
            .andExpect(view().name("result"))
            .andExpect(content().string(containsString("Page not found")));
}

@Test
void patientSubmissionDetailRendersInCzech() throws Exception {
    when(userPreferenceService.currentLanguagePreference(any())).thenReturn(LanguagePreference.CS);
    when(onboardingService.getOwnSubmissionById(any(), eq(99L))).thenReturn(fullSubmissionResponse());

    mvc.perform(get("/app/onboarding/99")
                    .with(user("patient@example.com").roles(RoleName.PATIENT.name())))
            .andExpect(status().isOk())
            .andExpect(view().name("onboarding-detail"))
            .andExpect(content().string(containsString("lang=\"cs\"")))
            .andExpect(content().string(containsString("Detail vstupního dotazníku")))
            .andExpect(content().string(containsString("Zpět")))
            .andExpect(content().string(containsString("Datum narození")))
            .andExpect(content().string(containsString("Crohnova choroba")))
            .andExpect(content().string(containsString("Mírná")));
}

private OnboardingSubmissionResponse reviewedSubmissionResponse() {
    return new OnboardingSubmissionResponse(
            99L,
            10L,
            "patient@example.com",
            "study-a",
            2,
            Instant.parse("2026-05-31T11:00:00Z"),
            Instant.parse("2026-05-31T12:00:00Z"),
            LocalDate.of(1990, 1, 1),
            Sex.FEMALE,
            "CZ",
            "Europe/Prague",
            IbdDiagnosisType.CROHNS_DISEASE,
            2018,
            "Ileocolonic",
            "Inflammatory",
            DiseaseActivityEstimate.MILD,
            "Mesalamine",
            SteroidUse.NONE,
            AdvancedTherapyExposure.NEVER_USED,
            "Stable regimen",
            LocalDate.of(2026, 5, 20),
            new BigDecimal("4.2"),
            new BigDecimal("120"),
            new BigDecimal("13.8"),
            new BigDecimal("4.3"),
            "Recent outpatient labs",
            OnboardingReviewStatus.REVIEWED,
            "reviewer@example.com",
            Instant.parse("2026-06-01T08:00:00Z"),
            "Confidential reviewer note");
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests 'com.metabion.controller.web.WebOnboardingControllerTest'`
Expected: `patientSubmissionDetail*` tests FAIL — 404/no route (and no `onboarding-detail` template).

- [ ] **Step 3: Add the controller method**

In `WebOnboardingController.java`, add after the `history` method:

```java
@GetMapping("/app/onboarding/{id}")
public String submissionDetail(@PathVariable Long id, Authentication authentication, Model model) {
    model.addAttribute("submission", onboardingService.getOwnSubmissionById(authentication, id));
    addAppShell(model, authentication, "/app/onboarding/history");
    return "onboarding-detail";
}
```

- [ ] **Step 4: Create the template**

Create `src/main/resources/templates/onboarding-detail.html`. Same field layout as `clinical-onboarding-detail.html` minus patient email, review rows, and review form; back link targets the patient history page. All message keys below already exist in both bundles.

```html
<!DOCTYPE html>
<html lang="en" xmlns:th="http://www.thymeleaf.org"
      th:replace="~{layout :: appShell(#{onboarding.detail.pageTitle}, ${activePath}, ~{::content})}">
<th:block th:fragment="content">
    <header class="app-header">
        <div>
            <p class="eyebrow">Metabion</p>
            <h1 th:text="#{onboarding.detail.title}">Onboarding detail</h1>
        </div>
        <a class="button-link secondary" th:href="@{/app/onboarding/history(context=${submission.onboardingContext()})}"
           th:text="#{onboarding.back}">Back</a>
    </header>
    <section class="panel app-panel">
        <p class="muted">
            <span th:text="#{onboarding.version}">Version</span>
            <strong th:text="${submission.version()}">1</strong>,
            <span th:text="#{onboarding.contextLower}">context</span>
            <strong th:text="${submission.onboardingContext()}">default</strong>,
            <span th:text="#{onboarding.submittedLower}">submitted</span>
            <strong th:text="${submission.submittedAt()}">date</strong>,
            <span th:text="#{onboarding.status}">status</span>
            <strong th:text="${#messages.msg('enum.onboardingReviewStatus.' + submission.reviewStatus().name())}">PENDING_REVIEW</strong>
        </p>
        <dl class="details">
            <dt th:text="#{onboarding.dateOfBirth}">Date of birth</dt><dd th:text="${submission.dateOfBirth()}">1990-01-01</dd>
            <dt th:text="#{onboarding.sex}">Sex</dt><dd th:text="${#messages.msg('enum.sex.' + submission.sex().name())}">FEMALE</dd>
            <dt th:text="#{onboarding.countryRegion}">Country/region</dt><dd th:text="${submission.countryRegion()}">CZ</dd>
            <dt th:text="#{onboarding.timezone}">Timezone</dt><dd th:text="${submission.timezone()}">Europe/Prague</dd>
            <dt th:text="#{onboarding.diagnosis}">Diagnosis</dt><dd th:text="${#messages.msg('enum.ibdDiagnosisType.' + submission.diagnosisType().name())}">CROHNS_DISEASE</dd>
            <dt th:text="#{onboarding.diagnosisYear}">Diagnosis year</dt><dd th:text="${submission.diagnosisYear()} ?: #{onboarding.notProvided}">2018</dd>
            <dt th:text="#{onboarding.diseaseLocation}">Disease location</dt><dd th:text="${submission.diseaseLocation()} ?: #{onboarding.notProvided}">Ileocolonic</dd>
            <dt th:text="#{onboarding.diseaseBehavior}">Disease behavior</dt><dd th:text="${submission.diseaseBehavior()} ?: #{onboarding.notProvided}">Inflammatory</dd>
            <dt th:text="#{onboarding.activity}">Activity</dt><dd th:text="${#messages.msg('enum.diseaseActivityEstimate.' + submission.activityEstimate().name())}">MILD</dd>
            <dt th:text="#{onboarding.medications}">Medications</dt><dd th:text="${submission.currentMedications()} ?: #{onboarding.notProvided}">Mesalamine</dd>
            <dt th:text="#{onboarding.steroidUse}">Steroid use</dt><dd th:text="${#messages.msg('enum.steroidUse.' + submission.steroidUse().name())}">NONE</dd>
            <dt th:text="#{onboarding.advancedTherapyExposure}">Advanced therapy exposure</dt><dd th:text="${#messages.msg('enum.advancedTherapyExposure.' + submission.advancedTherapyExposure().name())}">NEVER_USED</dd>
            <dt th:text="#{onboarding.medicationNotes}">Medication notes</dt><dd th:text="${submission.medicationNotes()} ?: #{onboarding.notProvided}">Stable regimen</dd>
            <dt th:text="#{onboarding.labsCollected}">Labs collected</dt><dd th:text="${submission.labsCollectedAt()} ?: #{onboarding.notProvided}">2026-05-20</dd>
            <dt th:text="#{onboarding.crpMgL}">CRP mg/L</dt><dd th:text="${submission.crpMgL()} ?: #{onboarding.notProvided}">4.2</dd>
            <dt th:text="#{onboarding.fecalCalprotectinUgG}">Fecal calprotectin ug/g</dt><dd th:text="${submission.fecalCalprotectinUgG()} ?: #{onboarding.notProvided}">120</dd>
            <dt th:text="#{onboarding.hemoglobinGDl}">Hemoglobin g/dL</dt><dd th:text="${submission.hemoglobinGDl()} ?: #{onboarding.notProvided}">13.8</dd>
            <dt th:text="#{onboarding.albuminGDl}">Albumin g/dL</dt><dd th:text="${submission.albuminGDl()} ?: #{onboarding.notProvided}">4.3</dd>
            <dt th:text="#{onboarding.labNotes}">Lab notes</dt><dd th:text="${submission.labNotes()} ?: #{onboarding.notProvided}">Recent outpatient labs</dd>
        </dl>
    </section>
</th:block>
</html>
```

Note: `currentMedications` gets `?: #{onboarding.notProvided}` here (it is optional in the form; the clinical template renders it raw, which would show blank — using the fallback is intentional and consistent with the other optional fields).

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew test --tests 'com.metabion.controller.web.WebOnboardingControllerTest'`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/metabion/controller/web/WebOnboardingController.java src/main/resources/templates/onboarding-detail.html src/test/java/com/metabion/controller/web/WebOnboardingControllerTest.java
git commit -m "Add patient onboarding submission detail page"
```

---

### Task 5: Navigation links to the detail page

**Files:**
- Modify: `src/main/resources/templates/onboarding-history.html`
- Modify: `src/main/resources/templates/onboarding.html`
- Modify: `src/main/resources/messages.properties`
- Modify: `src/main/resources/messages_cs.properties`
- Test: `src/test/java/com/metabion/controller/web/WebOnboardingControllerTest.java`

**Interfaces:**
- Consumes: route `GET /app/onboarding/{id}` from Task 4; `OnboardingSubmissionSummaryResponse.id()` and `OnboardingSubmissionResponse.id()` already exposed to the templates.
- Produces: new message key `onboarding.patient.viewDetails` in both bundles (used in `onboarding.html`).

- [ ] **Step 1: Write the failing tests**

Add to `WebOnboardingControllerTest` (existing `summaryResponse()` has id 99 and `fullSubmissionResponse()` has id 99):

```java
@Test
void patientHistoryRowsLinkToSubmissionDetail() throws Exception {
    when(onboardingService.listHistoryForCurrentPatient(any(), eq("default")))
            .thenReturn(java.util.List.of(summaryResponse()));

    mvc.perform(get("/app/onboarding/history")
                    .with(user("patient@example.com").roles(RoleName.PATIENT.name())))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("href=\"/app/onboarding/99\"")));
}

@Test
void latestBaselinePanelLinksToSubmissionDetail() throws Exception {
    when(onboardingService.getLatestForCurrentPatient(any(), eq("default"))).thenReturn(fullSubmissionResponse());

    mvc.perform(get("/app/onboarding")
                    .with(user("patient@example.com").roles(RoleName.PATIENT.name())))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("href=\"/app/onboarding/99\"")));
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests 'com.metabion.controller.web.WebOnboardingControllerTest'`
Expected: both new tests FAIL — no `href="/app/onboarding/99"` in the responses.

- [ ] **Step 3: Link history rows**

In `onboarding-history.html`, replace the version cell:

```html
<td th:text="${submission.version()}">1</td>
```

with:

```html
<td><a th:href="@{/app/onboarding/{id}(id=${submission.id()})}" th:text="${submission.version()}">1</a></td>
```

- [ ] **Step 4: Link the Latest baseline panel + add message key**

In `onboarding.html`, inside the `th:if="${latest != null}"` section, after the `onboarding.patient.latestSubmitted` paragraph add:

```html
<p><a th:href="@{/app/onboarding/{id}(id=${latest.id()})}" th:text="#{onboarding.patient.viewDetails}">View details</a></p>
```

In `messages.properties`, after line `onboarding.patient.latestSubmitted=Submitted {0}` add:

```properties
onboarding.patient.viewDetails=View details
```

In `messages_cs.properties`, after line `onboarding.patient.latestSubmitted=Odesláno {0}` add:

```properties
onboarding.patient.viewDetails=Zobrazit detail
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew test --tests 'com.metabion.controller.web.WebOnboardingControllerTest'`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/resources/templates/onboarding-history.html src/main/resources/templates/onboarding.html src/main/resources/messages.properties src/main/resources/messages_cs.properties src/test/java/com/metabion/controller/web/WebOnboardingControllerTest.java
git commit -m "Link patient onboarding history and latest baseline to detail page"
```

---

### Task 6: Full verification

**Files:** none (verification only).

- [ ] **Step 1: Run the full suite**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL, all tests pass (including `OnboardingReviewIT` and repository/integration tests that exercise onboarding).

- [ ] **Step 2: Fix any fallout**

If existing tests asserted review fields on patient-facing responses (none known at plan time — `getLatestForCurrentPatient` is only mocked in controller tests, and `OnboardingReviewIT` exercises the clinical path), update those assertions to the patient-view contract and re-run `./gradlew test`.
