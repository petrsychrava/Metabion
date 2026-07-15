# Clinical Daily Check-In Review Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the expert-facing legacy diet-log review with a unified daily-check-in list and detail covering diet, measurements, symptoms, and flare state.

**Architecture:** Extend the existing clinical review by composing `DietLogService` and `SymptomTrackingService` in one read-only `ClinicalDailyCheckInService`. Keep the current filtering, access control, diet response assembly, templates, localization patterns, and test fixtures; add only the cross-record join, unified routes/DTOs, and symptom rendering.

**Tech Stack:** Java 25, Spring Boot 4.0.6 MVC, Thymeleaf, Spring Security, JUnit 5, Mockito, AssertJ, Gradle.

## Global Constraints

- Use `/app/clinical/daily-check-ins` and `/app/clinical/daily-check-ins/{patientProfileId}/{date}`; do not add legacy redirects.
- Reuse existing public service behavior and `AccessControlService` enforcement through `DietLogService` and `SymptomTrackingService`; do not create parallel repository authorization.
- Include a day when either a diet log or symptom check-in exists and render a missing side as `Not provided`.
- Keep REST contracts and database schema unchanged; add no dependencies or migration.
- Preserve patient diet-log history and the patient daily-check-in workflow.
- Follow red-green-refactor and commit each task separately.

---

### Task 1: Unified Clinical Read Model

**Files:**
- Create: `src/main/java/com/metabion/dto/ClinicalDailyCheckInSummaryResponse.java`
- Create: `src/main/java/com/metabion/dto/ClinicalDailyCheckInDetailResponse.java`
- Create: `src/main/java/com/metabion/service/ClinicalDailyCheckInService.java`
- Create: `src/test/java/com/metabion/service/ClinicalDailyCheckInServiceTest.java`

**Interfaces:**
- Consumes: `DietLogService.listClinicalPatientOptions`, `listClinicalLogs`, `getClinicalLog`; `SymptomTrackingService.listClinicalCheckIns`.
- Produces: `List<ClinicalDailyCheckInSummaryResponse> list(Authentication, Long, LocalDate, LocalDate)` and `ClinicalDailyCheckInDetailResponse get(Authentication, Long, LocalDate)`.

- [ ] **Step 1: Write failing service tests**

Create a Mockito test with these core cases:

```java
@ExtendWith(MockitoExtension.class)
class ClinicalDailyCheckInServiceTest {
    @Mock DietLogService dietLogs;
    @Mock SymptomTrackingService symptoms;
    @Mock Authentication authentication;
    @InjectMocks ClinicalDailyCheckInService service;

    @Test
    void listJoinsDietAndSymptomsByPatientAndDateAndKeepsPartialDays() {
        var from = LocalDate.of(2026, 7, 13);
        var to = LocalDate.of(2026, 7, 15);
        when(dietLogs.listClinicalPatientOptions(authentication))
                .thenReturn(List.of(new PatientOptionResponse(42L, "patient@example.com")));
        when(dietLogs.listClinicalLogs(authentication, null, from, to))
                .thenReturn(List.of(dietSummary(8L, 42L, LocalDate.of(2026, 7, 15))));
        when(symptoms.listClinicalCheckIns(authentication, 42L, from, to))
                .thenReturn(List.of(
                        symptom(80L, 42L, LocalDate.of(2026, 7, 15)),
                        symptom(81L, 42L, LocalDate.of(2026, 7, 14))));

        var result = service.list(authentication, null, from, to);

        assertThat(result).extracting(ClinicalDailyCheckInSummaryResponse::date)
                .containsExactly(LocalDate.of(2026, 7, 15), LocalDate.of(2026, 7, 14));
        assertThat(result.getFirst().dietLogId()).isEqualTo(8L);
        assertThat(result.getFirst().symptomCheckInId()).isEqualTo(80L);
        assertThat(result.get(1).dietLogId()).isNull();
        assertThat(result.get(1).patientEmail()).isEqualTo("patient@example.com");
    }

    @Test
    void listPropagatesExistingClinicalAccessFailure() {
        var denied = new ResponseStatusException(HttpStatus.FORBIDDEN, "denied");
        when(dietLogs.listClinicalPatientOptions(authentication)).thenReturn(List.of());
        when(dietLogs.listClinicalLogs(authentication, 99L, DATE, DATE)).thenThrow(denied);

        assertThatThrownBy(() -> service.list(authentication, 99L, DATE, DATE)).isSameAs(denied);
    }

    @Test
    void getReturnsFullDietAndSymptomsForOnePatientDay() {
        when(dietLogs.listClinicalLogs(authentication, 42L, DATE, DATE))
                .thenReturn(List.of(dietSummary(8L, 42L, DATE)));
        when(dietLogs.getClinicalLog(authentication, 8L)).thenReturn(dietDetail(8L, 42L, DATE));
        when(symptoms.listClinicalCheckIns(authentication, 42L, DATE, DATE))
                .thenReturn(List.of(symptom(80L, 42L, DATE)));

        var result = service.get(authentication, 42L, DATE);

        assertThat(result.dietLog().id()).isEqualTo(8L);
        assertThat(result.symptomCheckIn().id()).isEqualTo(80L);
        assertThat(result.patientEmail()).isEqualTo("patient@example.com");
    }

    @Test
    void getReturnsNotFoundWhenNeitherSideExists() {
        when(dietLogs.listClinicalLogs(authentication, 42L, DATE, DATE)).thenReturn(List.of());
        when(symptoms.listClinicalCheckIns(authentication, 42L, DATE, DATE)).thenReturn(List.of());

        assertThatThrownBy(() -> service.get(authentication, 42L, DATE))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }
}
```

Reuse the existing response constructors from `WebDietLogControllerTest` and `SymptomTrackingServiceTest` for `dietSummary`, `dietDetail`, and `symptom`; use `DATE = LocalDate.of(2026, 7, 15)` and include one option answer so the fixtures exercise real DTO shapes.

- [ ] **Step 2: Run the service test and verify RED**

Run: `./gradlew test --tests 'com.metabion.service.ClinicalDailyCheckInServiceTest'`

Expected: compilation fails because the unified DTOs and service do not exist.

- [ ] **Step 3: Add the response DTOs**

```java
public record ClinicalDailyCheckInSummaryResponse(
        Long patientProfileId,
        String patientEmail,
        LocalDate date,
        Long dietLogId,
        DietAdherenceLevel adherenceLevel,
        AppetiteLevel appetiteLevel,
        Integer mealCount,
        Integer deviationCount,
        Integer measurementCount,
        Long symptomCheckInId,
        BigDecimal symptomScore,
        FlareState flareState) {
}
```

```java
public record ClinicalDailyCheckInDetailResponse(
        Long patientProfileId,
        String patientEmail,
        LocalDate date,
        DailyDietLogResponse dietLog,
        SymptomCheckInResponse symptomCheckIn) {
}
```

- [ ] **Step 4: Implement the composing read service**

Implement a read-only Spring service. The important join must be source-service composition, not new repository access:

```java
@Service
@Transactional(readOnly = true)
public class ClinicalDailyCheckInService {
    private final DietLogService dietLogs;
    private final SymptomTrackingService symptoms;

    public ClinicalDailyCheckInService(DietLogService dietLogs, SymptomTrackingService symptoms) {
        this.dietLogs = dietLogs;
        this.symptoms = symptoms;
    }

    public List<ClinicalDailyCheckInSummaryResponse> list(Authentication authentication,
                                                           Long patientProfileId,
                                                           LocalDate from,
                                                           LocalDate to) {
        var patients = dietLogs.listClinicalPatientOptions(authentication);
        var patientEmails = patients.stream().collect(Collectors.toMap(PatientOptionResponse::id,
                PatientOptionResponse::email));
        var dietRows = dietLogs.listClinicalLogs(authentication, patientProfileId, from, to);
        var patientIds = patientProfileId == null
                ? patients.stream().map(PatientOptionResponse::id).toList()
                : List.of(patientProfileId);
        var symptomRows = patientIds.stream()
                .flatMap(id -> symptoms.listClinicalCheckIns(authentication, id, from, to).stream())
                .toList();

        record Key(Long patientProfileId, LocalDate date) {}
        var dietByKey = dietRows.stream().collect(Collectors.toMap(
                row -> new Key(row.patientProfileId(), row.logDate()), Function.identity()));
        var symptomsByKey = symptomRows.stream().collect(Collectors.toMap(
                row -> new Key(row.patientProfileId(), row.checkInDate()), Function.identity()));
        var keys = new HashSet<Key>();
        keys.addAll(dietByKey.keySet());
        keys.addAll(symptomsByKey.keySet());

        return keys.stream().map(key -> {
                    var diet = dietByKey.get(key);
                    var symptom = symptomsByKey.get(key);
                    var email = diet == null ? patientEmails.get(key.patientProfileId()) : diet.patientEmail();
                    return new ClinicalDailyCheckInSummaryResponse(
                            key.patientProfileId(), email, key.date(),
                            diet == null ? null : diet.id(),
                            diet == null ? null : diet.adherenceLevel(),
                            diet == null ? null : diet.appetiteLevel(),
                            diet == null ? null : diet.mealCount(),
                            diet == null ? null : diet.deviationCount(),
                            diet == null ? null : diet.measurementCount(),
                            symptom == null ? null : symptom.id(),
                            symptom == null ? null : symptom.totalSymptomScore(),
                            symptom == null ? null : symptom.flareState());
                })
                .sorted(Comparator.comparing(ClinicalDailyCheckInSummaryResponse::date).reversed()
                        .thenComparing(ClinicalDailyCheckInSummaryResponse::patientEmail,
                                Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER))
                        .thenComparing(ClinicalDailyCheckInSummaryResponse::patientProfileId))
                .toList();
    }

    public ClinicalDailyCheckInDetailResponse get(Authentication authentication,
                                                   Long patientProfileId,
                                                   LocalDate date) {
        var dietSummary = dietLogs.listClinicalLogs(authentication, patientProfileId, date, date).stream()
                .findFirst().orElse(null);
        var symptom = symptoms.listClinicalCheckIns(authentication, patientProfileId, date, date).stream()
                .findFirst().orElse(null);
        if (dietSummary == null && symptom == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Daily check-in not found");
        }
        var diet = dietSummary == null ? null : dietLogs.getClinicalLog(authentication, dietSummary.id());
        var email = diet != null ? diet.patientEmail() : dietLogs.listClinicalPatientOptions(authentication).stream()
                .filter(option -> Objects.equals(option.id(), patientProfileId))
                .map(PatientOptionResponse::email).findFirst().orElse(null);
        return new ClinicalDailyCheckInDetailResponse(patientProfileId, email, date, diet, symptom);
    }
}
```

Import only JDK collections/functions plus the existing DTO/service/security types. Preserve downstream `403` and range validation exceptions unchanged.

- [ ] **Step 5: Run the service test and verify GREEN**

Run: `./gradlew test --tests 'com.metabion.service.ClinicalDailyCheckInServiceTest'`

Expected: `BUILD SUCCESSFUL` with all `ClinicalDailyCheckInServiceTest` cases passing.

- [ ] **Step 6: Commit the read model**

```bash
git add src/main/java/com/metabion/dto/ClinicalDailyCheckInSummaryResponse.java \
  src/main/java/com/metabion/dto/ClinicalDailyCheckInDetailResponse.java \
  src/main/java/com/metabion/service/ClinicalDailyCheckInService.java \
  src/test/java/com/metabion/service/ClinicalDailyCheckInServiceTest.java
git commit -m "Add clinical daily check-in read model"
```

---

### Task 2: Clinical Daily Check-In Web Pages

**Files:**
- Create: `src/main/java/com/metabion/controller/web/WebClinicalDailyCheckInController.java`
- Create: `src/main/resources/templates/clinical-daily-check-ins.html`
- Create: `src/main/resources/templates/clinical-daily-check-in-detail.html`
- Create: `src/test/java/com/metabion/controller/web/WebClinicalDailyCheckInControllerTest.java`
- Modify: `src/main/java/com/metabion/controller/web/WebDietLogController.java`
- Modify: `src/test/java/com/metabion/controller/web/WebDietLogControllerTest.java`
- Delete: `src/main/resources/templates/clinical-diet-logs.html`
- Delete: `src/main/resources/templates/clinical-diet-log-detail.html`

**Interfaces:**
- Consumes: Task 1 `ClinicalDailyCheckInService.list` and `.get`; existing `DietLogService.listClinicalPatientOptions`.
- Produces: the two `/app/clinical/daily-check-ins` MVC routes and model attributes `patientProfileId`, `patientOptions`, `from`, `to`, `clinicalDefaultRangeDays`, `checkIns`, and `checkIn`.

- [ ] **Step 1: Write failing MVC tests by adapting the existing clinical tests**

Move the clinical cases out of `WebDietLogControllerTest` into the new test class and change expectations to:

```java
@Test
void clinicalListRendersUnifiedRows() throws Exception {
    when(dietLogService.listClinicalPatientOptions(any()))
            .thenReturn(List.of(new PatientOptionResponse(42L, "patient@example.com")));
    when(clinicalDailyCheckIns.list(any(), isNull(), any(), any()))
            .thenReturn(List.of(summary()));

    mvc.perform(get("/app/clinical/daily-check-ins")
                    .with(user("doctor@example.com").roles(RoleName.PHYSICIAN.name())))
            .andExpect(status().isOk())
            .andExpect(view().name("clinical-daily-check-ins"))
            .andExpect(model().attribute("activePath", "/app/clinical/daily-check-ins"))
            .andExpect(content().string(containsString("Daily check-in review")))
            .andExpect(content().string(containsString("Suspected flare")))
            .andExpect(content().string(containsString(
                    "href=\"/app/clinical/daily-check-ins/42/2026-07-15\"")));
}

@Test
void clinicalDetailRendersDietMeasurementsSymptomsAndFlare() throws Exception {
    when(clinicalDailyCheckIns.get(any(), eq(42L), eq(LocalDate.of(2026, 7, 15))))
            .thenReturn(detail());

    mvc.perform(get("/app/clinical/daily-check-ins/42/2026-07-15")
                    .with(user("doctor@example.com").roles(RoleName.PHYSICIAN.name())))
            .andExpect(status().isOk())
            .andExpect(view().name("clinical-daily-check-in-detail"))
            .andExpect(content().string(containsString("Avocado salad")))
            .andExpect(content().string(containsString("5.8")))
            .andExpect(content().string(containsString("Suspected flare")))
            .andExpect(content().string(containsString("Abdominal pain")))
            .andExpect(content().string(containsString("Mild")));
}

@Test
void legacyClinicalDietLogRouteDoesNotExist() throws Exception {
    mvc.perform(get("/app/clinical/diet-logs")
                    .with(user("doctor@example.com").roles(RoleName.PHYSICIAN.name())))
            .andExpect(status().isNotFound());
}
```

Also retain the submitted filter, Czech localization, forbidden-error rendering, default seven-day range, photo privacy, and meal/deviation ordering assertions from the old test class. Keep only patient-history tests and fixtures in `WebDietLogControllerTest`.

- [ ] **Step 2: Run MVC tests and verify RED**

Run: `./gradlew test --tests 'com.metabion.controller.web.WebClinicalDailyCheckInControllerTest' --tests 'com.metabion.controller.web.WebDietLogControllerTest'`

Expected: compilation fails because the controller/test bean/templates do not exist.

- [ ] **Step 3: Add the controller and remove only legacy clinical methods**

```java
@Controller
public class WebClinicalDailyCheckInController {
    private static final String ACTIVE_PATH = "/app/clinical/daily-check-ins";
    private static final int DEFAULT_RANGE_DAYS = 7;

    private final ClinicalDailyCheckInService dailyCheckIns;
    private final DietLogService dietLogs;
    private final AppMenuCatalog appMenuCatalog;
    private final UserPreferenceService preferences;
    private final Clock clock;

    @GetMapping("/app/clinical/daily-check-ins")
    public String list(@RequestParam(required = false) Long patientProfileId,
                       @RequestParam(required = false) LocalDate from,
                       @RequestParam(required = false) LocalDate to,
                       Model model, Authentication authentication) {
        var selectedTo = to == null ? LocalDate.now(clock) : to;
        var selectedFrom = from == null ? selectedTo.minusDays(DEFAULT_RANGE_DAYS - 1L) : from;
        model.addAttribute("patientProfileId", patientProfileId);
        model.addAttribute("patientOptions", dietLogs.listClinicalPatientOptions(authentication));
        model.addAttribute("from", selectedFrom);
        model.addAttribute("to", selectedTo);
        model.addAttribute("clinicalDefaultRangeDays", String.valueOf(DEFAULT_RANGE_DAYS));
        model.addAttribute("checkIns", dailyCheckIns.list(authentication, patientProfileId,
                selectedFrom, selectedTo));
        addAppShell(model, authentication);
        return "clinical-daily-check-ins";
    }

    @GetMapping("/app/clinical/daily-check-ins/{patientProfileId}/{date}")
    public String detail(@PathVariable Long patientProfileId, @PathVariable LocalDate date,
                         Model model, Authentication authentication) {
        model.addAttribute("checkIn", dailyCheckIns.get(authentication, patientProfileId, date));
        addAppShell(model, authentication);
        return "clinical-daily-check-in-detail";
    }

    private void addAppShell(Model model, Authentication authentication) {
        model.addAttribute("appMenuItems", appMenuCatalog.sidebarItems(authentication));
        model.addAttribute("activePath", ACTIVE_PATH);
        model.addAttribute("themePreference", preferences.currentThemePreference(authentication));
    }
}
```

Use constructor injection. In `WebDietLogController`, delete `CLINICAL_ACTIVE_PATH`, `CLINICAL_DEFAULT_RANGE_DAYS`, `clinicalList`, `clinicalDetail`, and `addOptions`; retain patient history, timezone logic, and its existing dependencies.

- [ ] **Step 4: Adapt the existing templates instead of rebuilding them**

Copy the existing clinical list structure into `clinical-daily-check-ins.html`, retain its filter script, and change the table body to the unified fields:

```html
<tr th:each="checkIn : ${checkIns}">
  <td th:text="${checkIn.date()}">2026-07-15</td>
  <td th:text="${checkIn.patientEmail()}">patient@example.com</td>
  <td th:text="${checkIn.adherenceLevel() == null ? #messages.msg('dailyCheckIns.notProvided') : #messages.msg('enum.dietAdherenceLevel.' + checkIn.adherenceLevel().name())}">Full</td>
  <td th:text="${checkIn.appetiteLevel() == null ? #messages.msg('dailyCheckIns.notProvided') : #messages.msg('enum.appetiteLevel.' + checkIn.appetiteLevel().name())}">Normal</td>
  <td th:text="${checkIn.mealCount() == null ? #messages.msg('dailyCheckIns.notProvided') : checkIn.mealCount()}">1</td>
  <td th:text="${checkIn.deviationCount() == null ? #messages.msg('dailyCheckIns.notProvided') : checkIn.deviationCount()}">0</td>
  <td th:text="${checkIn.measurementCount() == null ? #messages.msg('dailyCheckIns.notProvided') : checkIn.measurementCount()}">2</td>
  <td th:text="${checkIn.symptomScore() == null ? #messages.msg('dailyCheckIns.notProvided') : checkIn.symptomScore()}">5.00</td>
  <td th:text="${checkIn.flareState() == null ? #messages.msg('dailyCheckIns.notProvided') : #messages.msg('enum.flareState.' + checkIn.flareState().name())}">Suspected flare</td>
  <td><a th:href="@{/app/clinical/daily-check-ins/{patientProfileId}/{date}(patientProfileId=${checkIn.patientProfileId()},date=${checkIn.date()})}" th:text="#{dailyCheckIns.open}">Open</a></td>
</tr>
```

Copy the existing detail template into `clinical-daily-check-in-detail.html`. Wrap its diet, meal, and measurement panels in `th:if="${checkIn.dietLog() != null}"`, replace `log` with `checkIn.dietLog()`, and add a missing-diet hint. Add this symptom panel:

```html
<section class="panel app-panel" th:if="${checkIn.symptomCheckIn() != null}" th:with="symptoms=${checkIn.symptomCheckIn()}">
  <h2 th:text="#{dailyCheckIns.symptoms}">Symptoms</h2>
  <dl class="details">
    <dt th:text="#{dailyCheckIns.symptomScore}">Symptom score</dt><dd th:text="${symptoms.totalSymptomScore()}">5.00</dd>
    <dt th:text="#{dailyCheckIns.flareState}">Flare state</dt><dd th:text="${#messages.msg('enum.flareState.' + symptoms.flareState().name())}">Suspected flare</dd>
    <dt th:text="#{dailyCheckIns.symptomNotes}">Symptom notes</dt><dd th:text="${symptoms.notes()} ?: #{dailyCheckIns.notProvided}">Notes</dd>
  </dl>
  <dl class="details" th:each="answer : ${symptoms.answers()}">
    <dt th:text="${#messages.msgOrNull('symptomQuestion.' + answer.questionStableKey()) ?: answer.label()}">Abdominal pain</dt>
    <dd th:text="${answer.optionStableKey() != null ? (#messages.msgOrNull('symptomQuestion.' + answer.questionStableKey() + '.option.' + answer.optionStableKey()) ?: answer.optionLabel()) : (answer.answerNumeric() != null ? answer.answerNumeric() : answer.answerText())}">Mild</dd>
  </dl>
</section>
<section class="panel app-panel" th:if="${checkIn.symptomCheckIn() == null}">
  <p th:text="#{dailyCheckIns.noSymptoms}">No symptom check-in recorded.</p>
</section>
```

The header reads patient/date from `checkIn`; the back link targets `/app/clinical/daily-check-ins`. Delete the two old templates after the new tests render successfully.

- [ ] **Step 5: Run MVC tests and verify GREEN**

Run: `./gradlew test --tests 'com.metabion.controller.web.WebClinicalDailyCheckInControllerTest' --tests 'com.metabion.controller.web.WebDietLogControllerTest'`

Expected: `BUILD SUCCESSFUL`; the new routes render and the legacy route is `404`.

- [ ] **Step 6: Commit the web replacement**

```bash
git add src/main/java/com/metabion/controller/web/WebClinicalDailyCheckInController.java \
  src/main/java/com/metabion/controller/web/WebDietLogController.java \
  src/main/resources/templates/clinical-daily-check-ins.html \
  src/main/resources/templates/clinical-daily-check-in-detail.html \
  src/main/resources/templates/clinical-diet-logs.html \
  src/main/resources/templates/clinical-diet-log-detail.html \
  src/test/java/com/metabion/controller/web/WebClinicalDailyCheckInControllerTest.java \
  src/test/java/com/metabion/controller/web/WebDietLogControllerTest.java
git commit -m "Replace clinical diet logs with daily check-ins"
```

---

### Task 3: Navigation, Localization, And Trend Links

**Files:**
- Modify: `src/main/java/com/metabion/controller/web/AppMenuCatalog.java`
- Modify: `src/main/resources/messages.properties`
- Modify: `src/main/resources/messages_cs.properties`
- Modify: `src/main/resources/templates/clinical-trends.html`
- Modify: `src/test/java/com/metabion/controller/web/AppMenuCatalogTest.java`
- Modify: `src/test/java/com/metabion/controller/web/WebTrendControllerTest.java`

**Interfaces:**
- Consumes: Task 2 detail route.
- Produces: expert menu route/label and inspectable trend-day links to the unified detail.

- [ ] **Step 1: Change tests first**

In `AppMenuCatalogTest`, expect `Daily check-in review`, route `/app/clinical/daily-check-ins`, and assert no item has route `/app/clinical/diet-logs`.

In `WebTrendControllerTest`, replace the diet-log-id link assertion with:

```java
.andExpect(content().string(containsString(
        "href=\"/app/clinical/daily-check-ins/10/2026-06-26\"")));
```

Rename `clinicalTrendPageDoesNotLinkDaysWithoutDietLog` to `clinicalTrendPageLinksSymptomOnlyDaysToDailyCheckInDetail` and assert the same new URL for `trendResponseWithoutDietLog()`.

- [ ] **Step 2: Run navigation tests and verify RED**

Run: `./gradlew test --tests 'com.metabion.controller.web.AppMenuCatalogTest' --tests 'com.metabion.controller.web.WebTrendControllerTest'`

Expected: failures still show `Diet log review` and `/app/clinical/diet-logs/{id}`.

- [ ] **Step 3: Update menu, messages, and trend link**

Change the clinical menu item to:

```java
item("menu.dailyCheckInReview", "/app/clinical/daily-check-ins", false, true,
        "menu.dailyCheckInReview.description")
```

Add aligned English/Czech keys for `menu.dailyCheckInReview`, its description, and every `dailyCheckIns.*` label used by the new templates. Reuse all existing `dietLogs.*` field labels and enum keys where their meaning is unchanged; do not duplicate them under new names.

Change the clinical trend date cell to:

```html
<a th:if="${day.dietLogId() != null || day.symptomCheckInId() != null}"
   th:href="@{/app/clinical/daily-check-ins/{patientProfileId}/{date}(patientProfileId=${trend.patientProfileId()},date=${day.date()})}"
   th:text="${day.date()}">2026-06-26</a>
<span th:if="${day.dietLogId() == null && day.symptomCheckInId() == null}"
      th:text="${day.date()}">2026-06-26</span>
```

- [ ] **Step 4: Run navigation tests and verify GREEN**

Run: `./gradlew test --tests 'com.metabion.controller.web.AppMenuCatalogTest' --tests 'com.metabion.controller.web.WebTrendControllerTest'`

Expected: `BUILD SUCCESSFUL`, including the symptom-only trend link.

- [ ] **Step 5: Commit navigation integration**

```bash
git add src/main/java/com/metabion/controller/web/AppMenuCatalog.java \
  src/main/resources/messages.properties src/main/resources/messages_cs.properties \
  src/main/resources/templates/clinical-trends.html \
  src/test/java/com/metabion/controller/web/AppMenuCatalogTest.java \
  src/test/java/com/metabion/controller/web/WebTrendControllerTest.java
git commit -m "Link experts to daily check-in review"
```

---

### Task 4: Regression And Full Verification

**Files:**
- Modify only files required by failures attributable to Tasks 1-3.

**Interfaces:**
- Consumes: all prior tasks.
- Produces: verified application behavior with no stale clinical diet-log UI references.

- [ ] **Step 1: Scan for stale web references**

Run: `rg -n '/app/clinical/diet-logs|clinical-diet-log|menu\.dietLogReview' src/main src/test`

Expected: only intentionally retained REST `/api/clinical/diet-logs` references may remain; no MVC route, menu, or template reference remains.

- [ ] **Step 2: Run focused clinical verification**

Run:

```bash
./gradlew test \
  --tests 'com.metabion.service.ClinicalDailyCheckInServiceTest' \
  --tests 'com.metabion.controller.web.WebClinicalDailyCheckInControllerTest' \
  --tests 'com.metabion.controller.web.WebDietLogControllerTest' \
  --tests 'com.metabion.controller.web.AppMenuCatalogTest' \
  --tests 'com.metabion.controller.web.WebTrendControllerTest'
```

Expected: `BUILD SUCCESSFUL` with zero failed tests.

- [ ] **Step 3: Run the full suite**

Run: `./gradlew test`

Expected: `BUILD SUCCESSFUL`; Jacoco finalization completes and no tests fail.

- [ ] **Step 4: Review the final diff**

Run: `git diff --check HEAD~3..HEAD && git status --short`

Expected: no whitespace errors; only the task files plus the user's pre-existing `.idea`, `application.properties`, `.superpowers/brainstorm`, and `var` changes appear.

- [ ] **Step 5: Commit any verification-only correction**

If Step 2 or 3 required a scoped correction, stage only that correction and commit it as `Fix clinical daily check-in regression`. If no correction was needed, do not create an empty commit.
