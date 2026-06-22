# Patient Diet Log History Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a separate patient diet-log history page with a 30-day default range, glucose and ketone values, and links back to the existing date-based edit form.

**Architecture:** Keep the existing `/app/diet-logs` create/update page and add `/app/diet-logs/history` for patient history. Preserve the current API summary DTO by introducing a web-focused history-row DTO and a service method that reuses current-patient ownership checks and diet-log range queries. Fetch linked and standalone measurements for the listed logs, then render the latest glucose and ketone measurement per log.

**Tech Stack:** Java 25, Spring Boot 4.0.6, Spring MVC, Thymeleaf, Spring Security, Spring Data JPA, JUnit 5, Mockito, MockMvc.

---

## File Structure

- Create `src/main/java/com/metabion/dto/DailyDietLogHistoryRowResponse.java`
  - Web-focused patient history row with log date, adherence, appetite, latest glucose, and latest ketone.
- Modify `src/main/java/com/metabion/repository/DailyMeasurementEntryRepository.java`
  - Add a bulk linked-measurement lookup for diet-log IDs.
- Modify `src/main/java/com/metabion/service/DietLogResponseAssembler.java`
  - Add a `historyRow(...)` assembler method.
- Modify `src/main/java/com/metabion/service/DietLogService.java`
  - Add `listCurrentPatientHistoryRows(...)` and helper methods for linked and standalone measurements.
- Modify `src/test/java/com/metabion/service/DietLogServiceTest.java`
  - Cover latest glucose/ketone selection, standalone measurements, ownership, and range validation.
- Modify `src/main/java/com/metabion/controller/web/WebDietLogController.java`
  - Add `GET /app/diet-logs/history` and a 30-day default range.
- Create `src/main/resources/templates/diet-log-history.html`
  - Patient history table with filter and navigation.
- Modify `src/main/resources/templates/diet-logs.html`
  - Add `View history` link in the page header.
- Modify `src/main/resources/messages.properties`
  - Add patient history labels.
- Modify `src/main/resources/messages_cs.properties`
  - Add Czech patient history labels.
- Modify `src/test/java/com/metabion/controller/web/WebDietLogControllerTest.java`
  - Cover history route defaults, explicit filters, row rendering, and navigation links.

## Task 1: Add Patient History Row Service Data

**Files:**
- Create: `src/main/java/com/metabion/dto/DailyDietLogHistoryRowResponse.java`
- Modify: `src/main/java/com/metabion/repository/DailyMeasurementEntryRepository.java`
- Modify: `src/main/java/com/metabion/service/DietLogResponseAssembler.java`
- Modify: `src/main/java/com/metabion/service/DietLogService.java`
- Test: `src/test/java/com/metabion/service/DietLogServiceTest.java`

- [ ] **Step 1: Write the failing service test**

Add imports to `src/test/java/com/metabion/service/DietLogServiceTest.java`:

```java
import com.metabion.dto.DailyDietLogHistoryRowResponse;
```

Add this test method after `listCurrentPatientLogsReturnsDescendingSummariesAndValidatesRange()`:

```java
@Test
void listCurrentPatientHistoryRowsReturnsLatestGlucoseAndKetoneValues() {
    var patient = givenAuthenticatedPatient();
    var log = savedLog(99L, patient, LocalDate.of(2026, 6, 10));
    when(dailyDietLogs.findByPatientProfileIdAndLogDateBetweenOrderByLogDateDesc(
            10L,
            LocalDate.of(2026, 6, 1),
            LocalDate.of(2026, 6, 30))).thenReturn(List.of(log));
    when(measurements.findByDailyDietLogIdInOrderByMeasuredAtDesc(List.of(99L)))
            .thenReturn(List.of(
                    measurement(patient, log, MeasurementType.GLUCOSE, "5.9", MeasurementUnit.MMOL_L,
                            Instant.parse("2026-06-10T09:30:00Z")),
                    measurement(patient, log, MeasurementType.KETONE, "1.2", MeasurementUnit.MMOL_L,
                            Instant.parse("2026-06-10T20:00:00Z")),
                    measurement(patient, log, MeasurementType.GLUCOSE, "5.4", MeasurementUnit.MMOL_L,
                            Instant.parse("2026-06-10T07:30:00Z"))));
    when(measurements.findByPatientProfileIdAndDailyDietLogIsNullAndMeasuredAtGreaterThanEqualAndMeasuredAtLessThanOrderByMeasuredAtDesc(
            eq(10L),
            eq(Instant.parse("2026-06-10T00:00:00Z")),
            eq(Instant.parse("2026-06-11T00:00:00Z"))))
            .thenReturn(List.of());

    var rows = service.listCurrentPatientHistoryRows(
            auth("patient@example.com"),
            LocalDate.of(2026, 6, 1),
            LocalDate.of(2026, 6, 30));

    assertThat(rows).extracting(DailyDietLogHistoryRowResponse::id).containsExactly(99L);
    assertThat(rows.getFirst().glucose().value()).isEqualByComparingTo("5.9");
    assertThat(rows.getFirst().glucose().unit()).isEqualTo(MeasurementUnit.MMOL_L);
    assertThat(rows.getFirst().ketones().value()).isEqualByComparingTo("1.2");
    assertThat(rows.getFirst().ketones().unit()).isEqualTo(MeasurementUnit.MMOL_L);
}
```

Add this test method next:

```java
@Test
void listCurrentPatientHistoryRowsIncludesStandaloneMeasurementsForLogDate() {
    var patient = givenAuthenticatedPatient();
    var log = savedLog(99L, patient, LocalDate.of(2026, 6, 10));
    when(dailyDietLogs.findByPatientProfileIdAndLogDateBetweenOrderByLogDateDesc(
            10L,
            LocalDate.of(2026, 6, 1),
            LocalDate.of(2026, 6, 30))).thenReturn(List.of(log));
    when(measurements.findByDailyDietLogIdInOrderByMeasuredAtDesc(List.of(99L))).thenReturn(List.of());
    when(measurements.findByPatientProfileIdAndDailyDietLogIsNullAndMeasuredAtGreaterThanEqualAndMeasuredAtLessThanOrderByMeasuredAtDesc(
            eq(10L),
            eq(Instant.parse("2026-06-10T00:00:00Z")),
            eq(Instant.parse("2026-06-11T00:00:00Z"))))
            .thenReturn(List.of(measurement(patient, null, MeasurementType.KETONE, "0.8", MeasurementUnit.MMOL_L,
                    Instant.parse("2026-06-10T19:00:00Z"))));

    var rows = service.listCurrentPatientHistoryRows(
            auth("patient@example.com"),
            LocalDate.of(2026, 6, 1),
            LocalDate.of(2026, 6, 30));

    assertThat(rows.getFirst().glucose()).isNull();
    assertThat(rows.getFirst().ketones().value()).isEqualByComparingTo("0.8");
}
```

Add this helper near the existing `glucoseMeasurement(...)` helper:

```java
private DailyMeasurementEntry measurement(PatientProfile patient,
                                          DailyDietLog log,
                                          MeasurementType type,
                                          String value,
                                          MeasurementUnit unit,
                                          Instant measuredAt) {
    return new DailyMeasurementEntry(
            patient,
            log,
            type,
            new BigDecimal(value),
            unit,
            measuredAt,
            MeasurementContext.FASTING,
            null);
}
```

- [ ] **Step 2: Run the focused service test and verify failure**

Run:

```bash
./gradlew test --tests com.metabion.service.DietLogServiceTest
```

Expected: FAIL because `DailyDietLogHistoryRowResponse`, `DietLogService.listCurrentPatientHistoryRows(...)`, and `DailyMeasurementEntryRepository.findByDailyDietLogIdInOrderByMeasuredAtDesc(...)` do not exist.

- [ ] **Step 3: Add the history row DTO**

Create `src/main/java/com/metabion/dto/DailyDietLogHistoryRowResponse.java`:

```java
package com.metabion.dto;

import com.metabion.domain.AppetiteLevel;
import com.metabion.domain.DailyDietLog;
import com.metabion.domain.DailyMeasurementEntry;
import com.metabion.domain.DietAdherenceLevel;
import com.metabion.domain.MeasurementType;
import com.metabion.domain.MeasurementUnit;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;

public record DailyDietLogHistoryRowResponse(
        Long id,
        LocalDate logDate,
        DietAdherenceLevel adherenceLevel,
        AppetiteLevel appetiteLevel,
        MeasurementValue glucose,
        MeasurementValue ketones
) {

    public static DailyDietLogHistoryRowResponse from(DailyDietLog log, List<DailyMeasurementEntry> measurements) {
        var entries = measurements == null ? List.<DailyMeasurementEntry>of() : measurements;
        return new DailyDietLogHistoryRowResponse(
                log.getId(),
                log.getLogDate(),
                log.getAdherenceLevel(),
                log.getAppetiteLevel(),
                latest(entries, MeasurementType.GLUCOSE),
                latest(entries, MeasurementType.KETONE));
    }

    private static MeasurementValue latest(List<DailyMeasurementEntry> measurements, MeasurementType type) {
        return measurements.stream()
                .filter(measurement -> measurement.getMeasurementType() == type)
                .max(Comparator.comparing(DailyMeasurementEntry::getMeasuredAt, Comparator.nullsLast(Comparator.naturalOrder())))
                .map(MeasurementValue::from)
                .orElse(null);
    }

    public record MeasurementValue(BigDecimal value, MeasurementUnit unit) {
        static MeasurementValue from(DailyMeasurementEntry measurement) {
            return new MeasurementValue(measurement.getValue(), measurement.getUnit());
        }
    }
}
```

- [ ] **Step 4: Add repository bulk lookup**

Modify `src/main/java/com/metabion/repository/DailyMeasurementEntryRepository.java` and add this method after `findByDailyDietLogIdOrderByMeasuredAtDesc(...)`:

```java
List<DailyMeasurementEntry> findByDailyDietLogIdInOrderByMeasuredAtDesc(List<Long> dailyDietLogIds);
```

- [ ] **Step 5: Add assembler method**

Modify `src/main/java/com/metabion/service/DietLogResponseAssembler.java`.

Add import:

```java
import com.metabion.dto.DailyDietLogHistoryRowResponse;
```

Add this method after `summary(...)`:

```java
public DailyDietLogHistoryRowResponse historyRow(DailyDietLog log, List<DailyMeasurementEntry> measurements) {
    return DailyDietLogHistoryRowResponse.from(log, measurements);
}
```

- [ ] **Step 6: Add service history method and measurement grouping**

Modify `src/main/java/com/metabion/service/DietLogService.java`.

Add import:

```java
import com.metabion.dto.DailyDietLogHistoryRowResponse;
```

Add this public method after `listCurrentPatientLogs(...)`:

```java
public List<DailyDietLogHistoryRowResponse> listCurrentPatientHistoryRows(Authentication authentication,
                                                                          LocalDate from,
                                                                          LocalDate to) {
    var patient = currentPatientProfile(authentication);
    validateRange(from, to);
    var logs = dailyDietLogs.findByPatientProfileIdAndLogDateBetweenOrderByLogDateDesc(patient.getId(), from, to);
    var measurementsByLogId = historyMeasurementsFor(patient, logs);
    return logs.stream()
            .map(log -> responseAssembler.historyRow(log, measurementsByLogId.getOrDefault(log.getId(), List.of())))
            .toList();
}
```

Add this private helper after `measurementCountsFor(...)`:

```java
private Map<Long, List<DailyMeasurementEntry>> historyMeasurementsFor(PatientProfile patient, List<DailyDietLog> logs) {
    if (logs == null || logs.isEmpty()) {
        return Map.of();
    }
    var logIds = logs.stream()
            .map(DailyDietLog::getId)
            .filter(id -> id != null)
            .toList();
    var entriesByLogId = new HashMap<Long, List<DailyMeasurementEntry>>();
    if (!logIds.isEmpty()) {
        measurements.findByDailyDietLogIdInOrderByMeasuredAtDesc(logIds)
                .forEach(entry -> {
                    var log = entry.getDailyDietLog();
                    if (log != null && log.getId() != null) {
                        entriesByLogId.merge(log.getId(), List.of(entry), DietLogService::appendMeasurements);
                    }
                });
    }

    var patientProfileId = patient == null ? null : patient.getId();
    var logsByDate = logs.stream()
            .filter(log -> log.getId() != null && log.getLogDate() != null)
            .collect(Collectors.groupingBy(DailyDietLog::getLogDate, Collectors.mapping(DailyDietLog::getId, Collectors.toList())));
    if (patientProfileId == null || logsByDate.isEmpty()) {
        return entriesByLogId;
    }
    var zone = measurementWindows.zoneFor(patient);
    var window = measurementWindows.dateRangeWindow(patient, logsByDate.keySet());
    measurements
            .findByPatientProfileIdAndDailyDietLogIsNullAndMeasuredAtGreaterThanEqualAndMeasuredAtLessThanOrderByMeasuredAtDesc(
                    patientProfileId,
                    window.fromInclusive(),
                    window.toExclusive())
            .stream()
            .filter(entry -> entry.getMeasuredAt() != null)
            .forEach(entry -> {
                var ids = logsByDate.get(entry.getMeasuredAt().atZone(zone).toLocalDate());
                if (ids != null) {
                    ids.forEach(id -> entriesByLogId.merge(id, List.of(entry), DietLogService::appendMeasurements));
                }
            });
    return entriesByLogId;
}
```

Add this private static helper after `historyMeasurementsFor(...)`:

```java
private static List<DailyMeasurementEntry> appendMeasurements(List<DailyMeasurementEntry> existing,
                                                              List<DailyMeasurementEntry> added) {
    return Stream.concat(existing.stream(), added.stream()).toList();
}
```

- [ ] **Step 7: Run the focused service test and verify pass**

Run:

```bash
./gradlew test --tests com.metabion.service.DietLogServiceTest
```

Expected: PASS.

- [ ] **Step 8: Commit service history data changes**

Run:

```bash
git add src/main/java/com/metabion/dto/DailyDietLogHistoryRowResponse.java \
  src/main/java/com/metabion/repository/DailyMeasurementEntryRepository.java \
  src/main/java/com/metabion/service/DietLogResponseAssembler.java \
  src/main/java/com/metabion/service/DietLogService.java \
  src/test/java/com/metabion/service/DietLogServiceTest.java
git commit -m "Add patient diet log history rows"
```

## Task 2: Add Patient History Web Page And Navigation

**Files:**
- Modify: `src/main/java/com/metabion/controller/web/WebDietLogController.java`
- Create: `src/main/resources/templates/diet-log-history.html`
- Modify: `src/main/resources/templates/diet-logs.html`
- Test: `src/test/java/com/metabion/controller/web/WebDietLogControllerTest.java`

- [ ] **Step 1: Write failing web controller tests**

Add import to `src/test/java/com/metabion/controller/web/WebDietLogControllerTest.java`:

```java
import com.metabion.dto.DailyDietLogHistoryRowResponse;
```

Add this test after `patientDietLogPageReloadsSelectedDateThroughQueryParameter()`:

```java
@Test
void patientDietLogPageLinksToHistory() throws Exception {
    doThrow(new ResponseStatusException(HttpStatus.NOT_FOUND))
            .when(dietLogService).getCurrentPatientLog(any(), any());

    mvc.perform(get("/app/diet-logs")
                    .with(user("patient@example.com").roles(RoleName.PATIENT.name())))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("/app/diet-logs/history")))
            .andExpect(content().string(containsString("View history")));
}
```

Add these tests after `patientSaveRedirectsToSelectedDateAndDelegates()`:

```java
@Test
void patientHistoryUsesDefaultThirtyDayRangeInPatientTimezone() throws Exception {
    when(clock.instant()).thenReturn(Instant.parse("2026-06-21T08:00:00Z"));
    when(dietLogService.currentPatientTimezone(any())).thenReturn("Europe/Prague");
    when(dietLogService.listCurrentPatientHistoryRows(
            any(),
            eq(LocalDate.of(2026, 5, 23)),
            eq(LocalDate.of(2026, 6, 21))))
            .thenReturn(List.of(historyRow()));

    mvc.perform(get("/app/diet-logs/history")
                    .with(user("patient@example.com").roles(RoleName.PATIENT.name())))
            .andExpect(status().isOk())
            .andExpect(view().name("diet-log-history"))
            .andExpect(model().attribute("from", LocalDate.of(2026, 5, 23)))
            .andExpect(model().attribute("to", LocalDate.of(2026, 6, 21)))
            .andExpect(content().string(containsString("Diet log history")))
            .andExpect(content().string(containsString("New log")))
            .andExpect(content().string(containsString("/app/diet-logs?date=2026-06-10")))
            .andExpect(content().string(containsString("5.80")))
            .andExpect(content().string(containsString("1.20")));
}
```

Add this test next:

```java
@Test
void patientHistoryUsesSubmittedRangeAndRendersMissingMeasurements() throws Exception {
    when(dietLogService.listCurrentPatientHistoryRows(
            any(),
            eq(LocalDate.of(2026, 6, 1)),
            eq(LocalDate.of(2026, 6, 10))))
            .thenReturn(List.of(new DailyDietLogHistoryRowResponse(
                    100L,
                    LocalDate.of(2026, 6, 9),
                    DietAdherenceLevel.FULL,
                    AppetiteLevel.LOW,
                    null,
                    null)));

    mvc.perform(get("/app/diet-logs/history")
                    .param("from", "2026-06-01")
                    .param("to", "2026-06-10")
                    .with(user("patient@example.com").roles(RoleName.PATIENT.name())))
            .andExpect(status().isOk())
            .andExpect(view().name("diet-log-history"))
            .andExpect(model().attribute("from", LocalDate.of(2026, 6, 1)))
            .andExpect(model().attribute("to", LocalDate.of(2026, 6, 10)))
            .andExpect(content().string(containsString("2026-06-09")))
            .andExpect(content().string(containsString("Not provided")));
}
```

Add this helper near `summaryResponse()`:

```java
private DailyDietLogHistoryRowResponse historyRow() {
    return new DailyDietLogHistoryRowResponse(
            99L,
            LocalDate.of(2026, 6, 10),
            DietAdherenceLevel.MOSTLY,
            AppetiteLevel.NORMAL,
            new DailyDietLogHistoryRowResponse.MeasurementValue(new BigDecimal("5.80"), MeasurementUnit.MMOL_L),
            new DailyDietLogHistoryRowResponse.MeasurementValue(new BigDecimal("1.20"), MeasurementUnit.MMOL_L));
}
```

- [ ] **Step 2: Run the focused web test and verify failure**

Run:

```bash
./gradlew test --tests com.metabion.controller.web.WebDietLogControllerTest
```

Expected: FAIL because `/app/diet-logs/history`, the `View history` link, and the `diet-log-history` template do not exist.

- [ ] **Step 3: Add controller route**

Modify `src/main/java/com/metabion/controller/web/WebDietLogController.java`.

Add constant near `CLINICAL_DEFAULT_RANGE_DAYS`:

```java
private static final int PATIENT_HISTORY_DEFAULT_RANGE_DAYS = 30;
```

Add this method after `savePatientForm(...)`:

```java
@GetMapping("/app/diet-logs/history")
public String patientHistory(@RequestParam(required = false) LocalDate from,
                             @RequestParam(required = false) LocalDate to,
                             Model model,
                             Authentication authentication) {
    var patientTimezone = currentPatientTimezone(authentication);
    var selectedTo = to == null ? currentDate(patientTimezone) : to;
    var selectedFrom = from == null ? selectedTo.minusDays(PATIENT_HISTORY_DEFAULT_RANGE_DAYS - 1L) : from;
    model.addAttribute("from", selectedFrom);
    model.addAttribute("to", selectedTo);
    model.addAttribute("logs", dietLogService.listCurrentPatientHistoryRows(authentication, selectedFrom, selectedTo));
    addAppShell(model, authentication, PATIENT_ACTIVE_PATH);
    return "diet-log-history";
}
```

- [ ] **Step 4: Add history template**

Create `src/main/resources/templates/diet-log-history.html`:

```html
<!DOCTYPE html>
<html lang="en" xmlns:th="http://www.thymeleaf.org"
      th:replace="~{layout :: appShell(#{dietLogs.history.pageTitle}, ${activePath}, ~{::content})}">
<th:block th:fragment="content">
    <header class="app-header">
        <div>
            <p class="eyebrow">Metabion</p>
            <h1 th:text="#{dietLogs.history.title}">Diet log history</h1>
        </div>
        <a class="button-link secondary" th:href="@{/app/diet-logs}" th:text="#{dietLogs.newLog}">New log</a>
    </header>

    <section class="panel app-panel">
        <form class="form" th:action="@{/app/diet-logs/history}" method="get">
            <label class="field"><span th:text="#{dietLogs.from}">From</span>
                <input type="date" name="from" th:value="${from}">
            </label>
            <label class="field"><span th:text="#{dietLogs.to}">To</span>
                <input type="date" name="to" th:value="${to}">
            </label>
            <button type="submit" th:text="#{dietLogs.filter}">Filter</button>
        </form>
    </section>

    <section class="panel app-panel">
        <table class="table">
            <thead>
            <tr>
                <th th:text="#{dietLogs.logDate}">Date</th>
                <th th:text="#{dietLogs.adherenceLevel}">Adherence</th>
                <th th:text="#{dietLogs.appetiteLevel}">Appetite</th>
                <th th:text="#{dietLogs.glucose}">Glucose</th>
                <th th:text="#{dietLogs.ketones}">Ketones</th>
                <th th:text="#{dietLogs.openEdit}">Open/Edit</th>
            </tr>
            </thead>
            <tbody>
            <tr th:each="log : ${logs}">
                <td th:text="${log.logDate()}">2026-06-10</td>
                <td th:text="${#messages.msg('enum.dietAdherenceLevel.' + log.adherenceLevel().name())}">Mostly</td>
                <td th:text="${#messages.msg('enum.appetiteLevel.' + log.appetiteLevel().name())}">Normal</td>
                <td>
                    <span th:if="${log.glucose() != null}"
                          th:text="${log.glucose().value() + ' ' + #messages.msg('enum.measurementUnit.' + log.glucose().unit().name())}">5.80 mmol/l</span>
                    <span th:if="${log.glucose() == null}" th:text="#{dietLogs.notProvided}">Not provided</span>
                </td>
                <td>
                    <span th:if="${log.ketones() != null}"
                          th:text="${log.ketones().value() + ' ' + #messages.msg('enum.measurementUnit.' + log.ketones().unit().name())}">1.20 mmol/l</span>
                    <span th:if="${log.ketones() == null}" th:text="#{dietLogs.notProvided}">Not provided</span>
                </td>
                <td><a th:href="@{/app/diet-logs(date=${log.logDate()})}" th:text="#{dietLogs.openEdit}">Open/Edit</a></td>
            </tr>
            <tr th:if="${#lists.isEmpty(logs)}">
                <td colspan="6" th:text="#{dietLogs.noLogs}">No diet logs found</td>
            </tr>
            </tbody>
        </table>
    </section>
</th:block>
</html>
```

- [ ] **Step 5: Add View history link to edit page**

Modify the `<header class="app-header">` in `src/main/resources/templates/diet-logs.html` so it becomes:

```html
<header class="app-header">
    <div>
        <p class="eyebrow">Metabion</p>
        <h1 th:text="#{dietLogs.patient.title}">Diet logs</h1>
    </div>
    <a class="button-link secondary" th:href="@{/app/diet-logs/history}" th:text="#{dietLogs.viewHistory}">View history</a>
</header>
```

- [ ] **Step 6: Run focused web test and verify remaining failures**

Run:

```bash
./gradlew test --tests com.metabion.controller.web.WebDietLogControllerTest
```

Expected: FAIL only for missing message bundle keys if Thymeleaf renders unresolved message markers or for assertion text tied to messages.

- [ ] **Step 7: Commit web route and template changes after Task 3 messages are added**

Do not commit yet. Task 3 adds localization keys required for the web tests to pass.

## Task 3: Add Localization And Final Verification

**Files:**
- Modify: `src/main/resources/messages.properties`
- Modify: `src/main/resources/messages_cs.properties`
- Test: `src/test/java/com/metabion/controller/web/WebDietLogControllerTest.java`

- [ ] **Step 1: Add English message keys**

Modify `src/main/resources/messages.properties` near the existing `dietLogs.*` labels:

```properties
dietLogs.history.pageTitle=Diet log history
dietLogs.history.title=Diet log history
dietLogs.viewHistory=View history
dietLogs.newLog=New log
dietLogs.openEdit=Open/Edit
```

- [ ] **Step 2: Add Czech message keys**

Modify `src/main/resources/messages_cs.properties` near the existing `dietLogs.*` labels:

```properties
dietLogs.history.pageTitle=Historie záznamů stravy
dietLogs.history.title=Historie záznamů stravy
dietLogs.viewHistory=Zobrazit historii
dietLogs.newLog=Nový záznam
dietLogs.openEdit=Otevřít/Upravit
```

- [ ] **Step 3: Run focused web test and verify pass**

Run:

```bash
./gradlew test --tests com.metabion.controller.web.WebDietLogControllerTest
```

Expected: PASS.

- [ ] **Step 4: Run focused service test and verify pass**

Run:

```bash
./gradlew test --tests com.metabion.service.DietLogServiceTest
```

Expected: PASS.

- [ ] **Step 5: Run all tests**

Run:

```bash
./gradlew test
```

Expected: PASS.

- [ ] **Step 6: Inspect git diff**

Run:

```bash
git diff -- src/main/java/com/metabion/dto/DailyDietLogHistoryRowResponse.java \
  src/main/java/com/metabion/repository/DailyMeasurementEntryRepository.java \
  src/main/java/com/metabion/service/DietLogResponseAssembler.java \
  src/main/java/com/metabion/service/DietLogService.java \
  src/main/java/com/metabion/controller/web/WebDietLogController.java \
  src/main/resources/templates/diet-log-history.html \
  src/main/resources/templates/diet-logs.html \
  src/main/resources/messages.properties \
  src/main/resources/messages_cs.properties \
  src/test/java/com/metabion/service/DietLogServiceTest.java \
  src/test/java/com/metabion/controller/web/WebDietLogControllerTest.java
```

Expected: Diff contains only patient diet-log history changes, no API response-shape changes to `DailyDietLogSummaryResponse`, and no schema migration.

- [ ] **Step 7: Commit web and localization changes**

Run:

```bash
git add src/main/java/com/metabion/controller/web/WebDietLogController.java \
  src/main/resources/templates/diet-log-history.html \
  src/main/resources/templates/diet-logs.html \
  src/main/resources/messages.properties \
  src/main/resources/messages_cs.properties \
  src/test/java/com/metabion/controller/web/WebDietLogControllerTest.java
git commit -m "Add patient diet log history page"
```

## Implementation Notes

- Keep `GET /api/diet-logs?from=&to=` returning `DailyDietLogSummaryResponse`. The web page uses `DailyDietLogHistoryRowResponse`.
- Keep `/app/diet-logs/history` under the patient service path. Do not accept `patientProfileId` on this page.
- Use patient timezone for the default date range. With a 30-day inclusive range, `from = to.minusDays(29)`.
- Show the latest glucose or ketone measurement by `measuredAt`. Missing values render as `dietLogs.notProvided`.
- Leave `.superpowers/` and `var/` untouched if they are still untracked.
