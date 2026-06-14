# Diet Log Service Refactor Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Refactor `DietLogService` into focused collaborators for validation, mapping, time-window calculation, and response assembly while preserving current API behavior.

**Architecture:** `DietLogService` remains the transactional workflow coordinator and delegates specialized responsibilities to small Spring services in `com.metabion.service`. `MeasurementWindowService` is the only owner of patient timezone parsing and invalid-timezone fallback; `MeasurementValidator` depends on it for measured-at date membership. Behavior stays compatible except for stricter photo storage-key allowlisting and strict rejection of any future unsupported glucose unit.

**Tech Stack:** Java 25, Spring Boot 4.0.6, JUnit 5, Mockito, AssertJ, Gradle.

---

## File Structure

- Create `src/main/java/com/metabion/service/StorageKeyValidator.java`
  - Validates nullable photo storage keys using an allowlist.
- Create `src/test/java/com/metabion/service/StorageKeyValidatorTest.java`
  - Direct unit tests for allowed and rejected storage keys.
- Create `src/main/java/com/metabion/service/MeasurementWindowService.java`
  - Owns `ZoneId` resolution and local-date to instant-window conversion.
- Create `src/test/java/com/metabion/service/MeasurementWindowServiceTest.java`
  - Tests UTC, patient timezone, and fallback behavior.
- Create `src/main/java/com/metabion/service/MeasurementValidator.java`
  - Validates measurement required fields, type/unit/range rules, and measured-at date membership.
- Create `src/test/java/com/metabion/service/MeasurementValidatorTest.java`
  - Direct unit tests for measurement validation.
- Create `src/main/java/com/metabion/service/DietLogRequestMapper.java`
  - Applies `DailyDietLogRequest` scalar fields and child collections; creates measurement entities.
- Create `src/main/java/com/metabion/service/DietLogResponseAssembler.java`
  - Builds summary and full response DTOs; owns notes preview.
- Modify `src/main/java/com/metabion/service/DietLogService.java`
  - Delegate validation, mapping, windows, and response assembly.
- Modify `src/test/java/com/metabion/service/DietLogServiceTest.java`
  - Update constructor setup, rename one test, and add helper stubs where useful.

---

### Task 1: StorageKeyValidator

**Files:**
- Create: `src/main/java/com/metabion/service/StorageKeyValidator.java`
- Create: `src/test/java/com/metabion/service/StorageKeyValidatorTest.java`

- [ ] **Step 1: Write failing storage-key validator tests**

Create `src/test/java/com/metabion/service/StorageKeyValidatorTest.java`:

```java
package com.metabion.service;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StorageKeyValidatorTest {

    private final StorageKeyValidator validator = new StorageKeyValidator();

    @Test
    void allowsNullAndOpaqueRelativeKeys() {
        validator.validate(null);
        validator.validate("pending/meal.jpg");
        validator.validate("patients/10/logs/2026-06-10/photo_1-2.HEIC");
    }

    @Test
    void rejectsUnsafeOrNonOpaqueKeys() {
        assertRejected("https://example.com/meal.jpg");
        assertRejected("../meal.jpg");
        assertRejected("/tmp/meal.jpg");
        assertRejected("C:\\Users\\x\\meal.jpg");
        assertRejected("\\\\server\\share\\meal.jpg");
        assertRejected("~/meal.jpg");
        assertRejected("file:/tmp/meal.jpg");
        assertRejected("meal.jpg?token=abc");
        assertRejected("meal.jpg#sig");
        assertRejected("pending/meal.jpg?signature=abc");
        assertRejected("pending//meal.jpg");
        assertRejected("pending/./meal.jpg");
        assertRejected("pending/../meal.jpg");
        assertRejected("pending/meal 1.jpg");
        assertRejected("pending/meal@1.jpg");
    }

    private void assertRejected(String storageKey) {
        assertThatThrownBy(() -> validator.validate(storageKey))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("400 BAD_REQUEST")
                .hasMessageContaining("photo storageKey is not allowed");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```bash
./gradlew test --tests com.metabion.service.StorageKeyValidatorTest
```

Expected: compilation fails because `StorageKeyValidator` does not exist.

- [ ] **Step 3: Implement StorageKeyValidator**

Create `src/main/java/com/metabion/service/StorageKeyValidator.java`:

```java
package com.metabion.service;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.regex.Pattern;

@Service
public class StorageKeyValidator {

    private static final Pattern ALLOWED_STORAGE_KEY = Pattern.compile("[A-Za-z0-9_./-]+");

    public void validate(String storageKey) {
        if (storageKey == null) {
            return;
        }
        if (!ALLOWED_STORAGE_KEY.matcher(storageKey).matches()
                || storageKey.startsWith("/")
                || storageKey.contains("\\")
                || storageKey.contains(":")
                || storageKey.contains("?")
                || storageKey.contains("#")
                || hasUnsafeSegment(storageKey)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "photo storageKey is not allowed");
        }
    }

    private boolean hasUnsafeSegment(String storageKey) {
        var segments = storageKey.split("/", -1);
        for (var segment : segments) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                return true;
            }
        }
        return false;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run:

```bash
./gradlew test --tests com.metabion.service.StorageKeyValidatorTest
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/metabion/service/StorageKeyValidator.java src/test/java/com/metabion/service/StorageKeyValidatorTest.java
git commit -m "Add storage key validator"
```

---

### Task 2: MeasurementWindowService

**Files:**
- Create: `src/main/java/com/metabion/service/MeasurementWindowService.java`
- Create: `src/test/java/com/metabion/service/MeasurementWindowServiceTest.java`

- [ ] **Step 1: Write failing measurement-window tests**

Create `src/test/java/com/metabion/service/MeasurementWindowServiceTest.java`:

```java
package com.metabion.service;

import com.metabion.domain.PatientProfile;
import com.metabion.domain.User;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MeasurementWindowServiceTest {

    private final MeasurementWindowService service = new MeasurementWindowService();

    @Test
    void buildsUtcDayWindow() {
        var patient = patient("UTC");

        var window = service.dayWindow(patient, LocalDate.of(2026, 6, 10));

        assertThat(window.fromInclusive()).isEqualTo(Instant.parse("2026-06-10T00:00:00Z"));
        assertThat(window.toExclusive()).isEqualTo(Instant.parse("2026-06-11T00:00:00Z"));
    }

    @Test
    void appliesPatientTimezone() {
        var patient = patient("Europe/Prague");

        var window = service.dayWindow(patient, LocalDate.of(2026, 6, 10));

        assertThat(window.fromInclusive()).isEqualTo(Instant.parse("2026-06-09T22:00:00Z"));
        assertThat(window.toExclusive()).isEqualTo(Instant.parse("2026-06-10T22:00:00Z"));
    }

    @Test
    void fallsBackForMissingOrInvalidTimezone() {
        var expected = LocalDate.of(2026, 6, 10).atStartOfDay(ZoneId.systemDefault()).toInstant();

        assertThat(service.dayWindow(patient(null), LocalDate.of(2026, 6, 10)).fromInclusive()).isEqualTo(expected);
        assertThat(service.dayWindow(patient("not-a-zone"), LocalDate.of(2026, 6, 10)).fromInclusive()).isEqualTo(expected);
    }

    @Test
    void buildsDateRangeWindowFromMinAndMaxDates() {
        var patient = patient("UTC");

        var window = service.dateRangeWindow(patient, List.of(
                LocalDate.of(2026, 6, 12),
                LocalDate.of(2026, 6, 10),
                LocalDate.of(2026, 6, 11)));

        assertThat(window.fromInclusive()).isEqualTo(Instant.parse("2026-06-10T00:00:00Z"));
        assertThat(window.toExclusive()).isEqualTo(Instant.parse("2026-06-13T00:00:00Z"));
    }

    @Test
    void measuredAtBelongsToLocalDateUsesHalfOpenWindow() {
        var patient = patient("UTC");

        assertThat(service.belongsToDate(patient, LocalDate.of(2026, 6, 10),
                Instant.parse("2026-06-10T00:00:00Z"))).isTrue();
        assertThat(service.belongsToDate(patient, LocalDate.of(2026, 6, 10),
                Instant.parse("2026-06-10T23:59:59Z"))).isTrue();
        assertThat(service.belongsToDate(patient, LocalDate.of(2026, 6, 10),
                Instant.parse("2026-06-11T00:00:00Z"))).isFalse();
    }

    private PatientProfile patient(String timezone) {
        var user = new User("patient@example.com", "hash");
        var patient = new PatientProfile(user);
        patient.setTimezone(timezone);
        return patient;
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```bash
./gradlew test --tests com.metabion.service.MeasurementWindowServiceTest
```

Expected: compilation fails because `MeasurementWindowService` does not exist.

- [ ] **Step 3: Implement MeasurementWindowService**

Create `src/main/java/com/metabion/service/MeasurementWindowService.java`:

```java
package com.metabion.service;

import com.metabion.domain.PatientProfile;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collection;
import java.util.Comparator;

@Service
public class MeasurementWindowService {

    public MeasurementWindow dayWindow(PatientProfile patient, LocalDate date) {
        var zone = zoneFor(patient);
        return new MeasurementWindow(
                date.atStartOfDay(zone).toInstant(),
                date.plusDays(1).atStartOfDay(zone).toInstant());
    }

    public MeasurementWindow dateRangeWindow(PatientProfile patient, Collection<LocalDate> dates) {
        var minDate = dates.stream().min(Comparator.naturalOrder()).orElseThrow();
        var maxDate = dates.stream().max(Comparator.naturalOrder()).orElseThrow();
        var zone = zoneFor(patient);
        return new MeasurementWindow(
                minDate.atStartOfDay(zone).toInstant(),
                maxDate.plusDays(1).atStartOfDay(zone).toInstant());
    }

    public boolean belongsToDate(PatientProfile patient, LocalDate date, Instant measuredAt) {
        var window = dayWindow(patient, date);
        return !measuredAt.isBefore(window.fromInclusive()) && measuredAt.isBefore(window.toExclusive());
    }

    ZoneId zoneFor(PatientProfile patient) {
        try {
            var timezone = trimToNull(patient == null ? null : patient.getTimezone());
            return timezone == null ? ZoneId.systemDefault() : ZoneId.of(timezone);
        } catch (RuntimeException ignored) {
            return ZoneId.systemDefault();
        }
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        var trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    public record MeasurementWindow(Instant fromInclusive, Instant toExclusive) {
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run:

```bash
./gradlew test --tests com.metabion.service.MeasurementWindowServiceTest
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/metabion/service/MeasurementWindowService.java src/test/java/com/metabion/service/MeasurementWindowServiceTest.java
git commit -m "Add measurement window service"
```

---

### Task 3: MeasurementValidator

**Files:**
- Create: `src/main/java/com/metabion/service/MeasurementValidator.java`
- Create: `src/test/java/com/metabion/service/MeasurementValidatorTest.java`

- [ ] **Step 1: Write failing measurement-validator tests**

Create `src/test/java/com/metabion/service/MeasurementValidatorTest.java`:

```java
package com.metabion.service;

import com.metabion.domain.MeasurementContext;
import com.metabion.domain.MeasurementType;
import com.metabion.domain.MeasurementUnit;
import com.metabion.domain.PatientProfile;
import com.metabion.domain.User;
import com.metabion.dto.DailyMeasurementEntryRequest;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MeasurementValidatorTest {

    private final MeasurementValidator validator = new MeasurementValidator(new MeasurementWindowService());
    private final PatientProfile patient = patient("UTC");

    @Test
    void acceptsCurrentValidMeasurementRules() {
        validator.validateForLogDate(patient, LocalDate.of(2026, 6, 10),
                request(MeasurementType.GLUCOSE, "5.8", MeasurementUnit.MMOL_L));
        validator.validateForLogDate(patient, LocalDate.of(2026, 6, 10),
                request(MeasurementType.GLUCOSE, "104", MeasurementUnit.MG_DL));
        validator.validateForLogDate(patient, LocalDate.of(2026, 6, 10),
                request(MeasurementType.KETONE, "1.2", MeasurementUnit.MMOL_L));
    }

    @Test
    void rejectsMissingFields() {
        assertBadRequest(null, "measurement is required");
        assertBadRequest(new DailyMeasurementEntryRequest(null, BigDecimal.ONE, MeasurementUnit.MMOL_L,
                measuredAt(), MeasurementContext.FASTING, null), "measurementType is required");
        assertBadRequest(new DailyMeasurementEntryRequest(MeasurementType.GLUCOSE, null, MeasurementUnit.MMOL_L,
                measuredAt(), MeasurementContext.FASTING, null), "value is required");
        assertBadRequest(new DailyMeasurementEntryRequest(MeasurementType.GLUCOSE, BigDecimal.ONE, null,
                measuredAt(), MeasurementContext.FASTING, null), "unit is required");
        assertBadRequest(new DailyMeasurementEntryRequest(MeasurementType.GLUCOSE, BigDecimal.ONE, MeasurementUnit.MMOL_L,
                null, MeasurementContext.FASTING, null), "measuredAt is required");
        assertBadRequest(new DailyMeasurementEntryRequest(MeasurementType.GLUCOSE, BigDecimal.ONE, MeasurementUnit.MMOL_L,
                measuredAt(), null, null), "context is required");
    }

    @Test
    void rejectsOutOfRangeValues() {
        assertBadRequest(request(MeasurementType.GLUCOSE, "40.1", MeasurementUnit.MMOL_L),
                "glucose mmol/L value is outside the allowed range");
        assertBadRequest(request(MeasurementType.GLUCOSE, "721", MeasurementUnit.MG_DL),
                "glucose mg/dL value is outside the allowed range");
        assertBadRequest(request(MeasurementType.KETONE, "15.1", MeasurementUnit.MMOL_L),
                "ketone mmol/L value is outside the allowed range");
    }

    @Test
    void rejectsUnsupportedKetoneUnit() {
        assertBadRequest(request(MeasurementType.KETONE, "1.0", MeasurementUnit.MG_DL),
                "ketone unit must be MMOL_L");
    }

    @Test
    void rejectsMeasuredAtOutsideLocalLogDate() {
        var request = new DailyMeasurementEntryRequest(
                MeasurementType.GLUCOSE,
                new BigDecimal("5.8"),
                MeasurementUnit.MMOL_L,
                Instant.parse("2026-06-11T00:00:00Z"),
                MeasurementContext.FASTING,
                null);

        assertBadRequest(request, "measuredAt must be within logDate");
    }

    private void assertBadRequest(DailyMeasurementEntryRequest request, String reason) {
        assertThatThrownBy(() -> validator.validateForLogDate(patient, LocalDate.of(2026, 6, 10), request))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("400 BAD_REQUEST")
                .hasMessageContaining(reason);
    }

    private DailyMeasurementEntryRequest request(MeasurementType type, String value, MeasurementUnit unit) {
        return new DailyMeasurementEntryRequest(
                type,
                new BigDecimal(value),
                unit,
                measuredAt(),
                MeasurementContext.FASTING,
                null);
    }

    private Instant measuredAt() {
        return Instant.parse("2026-06-10T07:30:00Z");
    }

    private PatientProfile patient(String timezone) {
        var user = new User("patient@example.com", "hash");
        var patient = new PatientProfile(user);
        patient.setTimezone(timezone);
        return patient;
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```bash
./gradlew test --tests com.metabion.service.MeasurementValidatorTest
```

Expected: compilation fails because `MeasurementValidator` does not exist.

- [ ] **Step 3: Implement MeasurementValidator**

Create `src/main/java/com/metabion/service/MeasurementValidator.java`:

```java
package com.metabion.service;

import com.metabion.domain.MeasurementType;
import com.metabion.domain.MeasurementUnit;
import com.metabion.domain.PatientProfile;
import com.metabion.dto.DailyMeasurementEntryRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

@Service
public class MeasurementValidator {

    private static final Map<MeasurementKey, RangeRule> RULES = Map.of(
            new MeasurementKey(MeasurementType.GLUCOSE, MeasurementUnit.MMOL_L),
            new RangeRule(new BigDecimal("1.0"), new BigDecimal("40.0"),
                    "glucose mmol/L value is outside the allowed range"),
            new MeasurementKey(MeasurementType.GLUCOSE, MeasurementUnit.MG_DL),
            new RangeRule(new BigDecimal("18"), new BigDecimal("720"),
                    "glucose mg/dL value is outside the allowed range"),
            new MeasurementKey(MeasurementType.KETONE, MeasurementUnit.MMOL_L),
            new RangeRule(new BigDecimal("0.0"), new BigDecimal("15.0"),
                    "ketone mmol/L value is outside the allowed range")
    );

    private final MeasurementWindowService measurementWindows;

    public MeasurementValidator(MeasurementWindowService measurementWindows) {
        this.measurementWindows = measurementWindows;
    }

    public void validateForLogDate(PatientProfile patient, LocalDate logDate, DailyMeasurementEntryRequest request) {
        validate(request);
        if (!measurementWindows.belongsToDate(patient, logDate, request.measuredAt())) {
            throw badRequest("measuredAt must be within logDate");
        }
    }

    public void validate(DailyMeasurementEntryRequest request) {
        if (request == null) {
            throw badRequest("measurement is required");
        }
        if (request.measurementType() == null) {
            throw badRequest("measurementType is required");
        }
        if (request.value() == null) {
            throw badRequest("value is required");
        }
        if (request.unit() == null) {
            throw badRequest("unit is required");
        }
        if (request.measuredAt() == null) {
            throw badRequest("measuredAt is required");
        }
        if (request.context() == null) {
            throw badRequest("context is required");
        }

        var rule = RULES.get(new MeasurementKey(request.measurementType(), request.unit()));
        if (rule == null) {
            rejectUnsupportedRule(request);
            return;
        }
        rule.validate(request.value());
    }

    private void rejectUnsupportedRule(DailyMeasurementEntryRequest request) {
        if (request.measurementType() == MeasurementType.KETONE) {
            throw badRequest("ketone unit must be MMOL_L");
        }
        if (request.measurementType() == MeasurementType.GLUCOSE) {
            throw badRequest("glucose unit must be MMOL_L or MG_DL");
        }
    }

    private static ResponseStatusException badRequest(String reason) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
    }

    private record MeasurementKey(MeasurementType type, MeasurementUnit unit) {
    }

    private record RangeRule(BigDecimal min, BigDecimal max, String errorReason) {
        void validate(BigDecimal value) {
            if (value.compareTo(min) < 0 || value.compareTo(max) > 0) {
                throw badRequest(errorReason);
            }
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run:

```bash
./gradlew test --tests com.metabion.service.MeasurementValidatorTest
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/metabion/service/MeasurementValidator.java src/test/java/com/metabion/service/MeasurementValidatorTest.java
git commit -m "Add measurement validator"
```

---

### Task 4: DietLogRequestMapper

**Files:**
- Create: `src/main/java/com/metabion/service/DietLogRequestMapper.java`
- Modify: `src/main/java/com/metabion/service/DietLogService.java`
- Modify: `src/test/java/com/metabion/service/DietLogServiceTest.java`

- [ ] **Step 1: Wire mapper into service constructor without changing behavior**

Modify `DietLogService` constructor fields so it receives:

```java
private final MeasurementValidator measurementValidator;
private final DietLogRequestMapper requestMapper;
```

The constructor signature becomes:

```java
public DietLogService(UserRepository users,
                      PatientProfileRepository patientProfiles,
                      DailyDietLogRepository dailyDietLogs,
                      DailyMeasurementEntryRepository measurements,
                      AccessControlService accessControl,
                      MeasurementValidator measurementValidator,
                      DietLogRequestMapper requestMapper) {
    this.users = users;
    this.patientProfiles = patientProfiles;
    this.dailyDietLogs = dailyDietLogs;
    this.measurements = measurements;
    this.accessControl = accessControl;
    this.measurementValidator = measurementValidator;
    this.requestMapper = requestMapper;
}
```

In `DietLogServiceTest.setUp`, construct the real collaborators:

```java
var measurementWindows = new MeasurementWindowService();
var measurementValidator = new MeasurementValidator(measurementWindows);
var storageKeyValidator = new StorageKeyValidator();
var requestMapper = new DietLogRequestMapper(storageKeyValidator);
service = new DietLogService(
        users,
        patientProfiles,
        dailyDietLogs,
        measurements,
        accessControl,
        measurementValidator,
        requestMapper);
```

- [ ] **Step 2: Create DietLogRequestMapper**

Create `src/main/java/com/metabion/service/DietLogRequestMapper.java`:

```java
package com.metabion.service;

import com.metabion.domain.DailyDietLog;
import com.metabion.domain.DailyDietLogDeviation;
import com.metabion.domain.DailyDietLogMeal;
import com.metabion.domain.DailyDietLogPhotoReference;
import com.metabion.domain.DailyMeasurementEntry;
import com.metabion.domain.PatientProfile;
import com.metabion.dto.DailyDietLogRequest;
import com.metabion.dto.DailyMeasurementEntryRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;

@Service
public class DietLogRequestMapper {

    private final StorageKeyValidator storageKeyValidator;

    public DietLogRequestMapper(StorageKeyValidator storageKeyValidator) {
        this.storageKeyValidator = storageKeyValidator;
    }

    public void applyTo(DailyDietLog log, DailyDietLogRequest request) {
        log.setLogDate(request.logDate());
        log.setAdherenceLevel(request.adherenceLevel());
        log.setAppetiteLevel(request.appetiteLevel());
        log.setNotes(trimToNull(request.notes()));
        log.setMetadata(trimToNull(request.metadata()));
        log.replaceChildren(mealsFrom(request), deviationsFrom(request), photoReferencesFrom(request));
    }

    public DailyMeasurementEntry measurementFrom(PatientProfile patient,
                                                 DailyDietLog log,
                                                 DailyMeasurementEntryRequest request) {
        return new DailyMeasurementEntry(
                patient,
                log,
                request.measurementType(),
                request.value(),
                request.unit(),
                request.measuredAt(),
                request.context(),
                trimToNull(request.notes()),
                trimToNull(request.metadata()));
    }

    private java.util.List<DailyDietLogMeal> mealsFrom(DailyDietLogRequest request) {
        var requests = request.mealsOrEmpty();
        var meals = new ArrayList<DailyDietLogMeal>(requests.size());
        for (var i = 0; i < requests.size(); i++) {
            var meal = requests.get(i);
            if (meal == null) {
                throw badRequest("meal is required");
            }
            if (meal.mealType() == null) {
                throw badRequest("mealType is required");
            }
            if (meal.foodCategory() == null) {
                throw badRequest("foodCategory is required");
            }
            meals.add(new DailyDietLogMeal(
                    meal.mealType(),
                    meal.foodCategory(),
                    trimToNull(meal.foodDescription()),
                    trimToNull(meal.notes()),
                    i));
        }
        return meals;
    }

    private java.util.List<DailyDietLogDeviation> deviationsFrom(DailyDietLogRequest request) {
        var requests = request.deviationsOrEmpty();
        var deviations = new ArrayList<DailyDietLogDeviation>(requests.size());
        for (var i = 0; i < requests.size(); i++) {
            var deviation = requests.get(i);
            if (deviation == null) {
                throw badRequest("deviation is required");
            }
            if (deviation.deviationCategory() == null) {
                throw badRequest("deviationCategory is required");
            }
            if (deviation.severity() == null) {
                throw badRequest("severity is required");
            }
            deviations.add(new DailyDietLogDeviation(
                    deviation.deviationCategory(),
                    deviation.severity(),
                    trimToNull(deviation.notes()),
                    i));
        }
        return deviations;
    }

    private java.util.List<DailyDietLogPhotoReference> photoReferencesFrom(DailyDietLogRequest request) {
        var requests = request.photoReferencesOrEmpty();
        var photoReferences = new ArrayList<DailyDietLogPhotoReference>(requests.size());
        for (var i = 0; i < requests.size(); i++) {
            var photo = requests.get(i);
            if (photo == null) {
                throw badRequest("photoReference is required");
            }
            var storageKey = trimToNull(photo.storageKey());
            storageKeyValidator.validate(storageKey);
            photoReferences.add(new DailyDietLogPhotoReference(
                    trimToNull(photo.originalFilename()),
                    trimToNull(photo.contentType()),
                    photo.sizeBytes(),
                    storageKey,
                    trimToNull(photo.caption()),
                    i));
        }
        return photoReferences;
    }

    static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        var trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static ResponseStatusException badRequest(String reason) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
    }
}
```

- [ ] **Step 3: Delegate save flow to mapper and validator**

In `saveForCurrentPatient`, replace:

```java
request.measurementsOrEmpty().forEach(this::validateMeasurement);
request.measurementsOrEmpty().forEach(measurement -> validateMeasurementDate(patient, request.logDate(), measurement));
```

with:

```java
request.measurementsOrEmpty()
        .forEach(measurement -> measurementValidator.validateForLogDate(patient, request.logDate(), measurement));
```

Replace the scalar setters and `replaceChildren(...)` block with:

```java
log.setPatientProfile(patient);
requestMapper.applyTo(log, request);
```

Replace `measurementFrom(patient, saved, request)` calls with:

```java
requestMapper.measurementFrom(patient, saved, request)
```

In `addMeasurementForCurrentPatient`, replace validation calls with:

```java
measurementValidator.validateForLogDate(patient, date, request);
```

and replace entity creation with:

```java
requestMapper.measurementFrom(patient, log, request)
```

- [ ] **Step 4: Remove moved private methods from DietLogService**

Delete these private methods and now-unused imports from `DietLogService`:

```java
validateMeasurement(...)
validateMeasurementDate(...)
mealsFrom(...)
deviationsFrom(...)
photoReferencesFrom(...)
measurementFrom(...)
outside(...)
validateStorageKey(...)
```

Remove imports no longer used after deletion:

```java
import com.metabion.domain.DailyDietLogDeviation;
import com.metabion.domain.DailyDietLogMeal;
import com.metabion.domain.DailyDietLogPhotoReference;
import com.metabion.domain.MeasurementType;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Locale;
```

- [ ] **Step 5: Run focused tests**

Run:

```bash
./gradlew test --tests com.metabion.service.DietLogServiceTest --tests com.metabion.service.StorageKeyValidatorTest --tests com.metabion.service.MeasurementValidatorTest
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/metabion/service/DietLogService.java src/main/java/com/metabion/service/DietLogRequestMapper.java src/test/java/com/metabion/service/DietLogServiceTest.java
git commit -m "Extract diet log request mapping"
```

---

### Task 5: DietLogResponseAssembler

**Files:**
- Create: `src/main/java/com/metabion/service/DietLogResponseAssembler.java`
- Modify: `src/main/java/com/metabion/service/DietLogService.java`
- Modify: `src/test/java/com/metabion/service/DietLogServiceTest.java`

- [ ] **Step 1: Create DietLogResponseAssembler**

Create `src/main/java/com/metabion/service/DietLogResponseAssembler.java`:

```java
package com.metabion.service;

import com.metabion.domain.DailyDietLog;
import com.metabion.domain.DailyMeasurementEntry;
import com.metabion.dto.DailyDietLogResponse;
import com.metabion.dto.DailyDietLogSummaryResponse;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class DietLogResponseAssembler {

    private static final int NOTES_PREVIEW_LENGTH = 120;

    public DailyDietLogResponse full(DailyDietLog log, List<DailyMeasurementEntry> measurements) {
        return DailyDietLogResponse.from(log, measurements);
    }

    public DailyDietLogSummaryResponse summary(DailyDietLog log, int measurementCount) {
        var patient = log.getPatientProfile();
        return new DailyDietLogSummaryResponse(
                log.getId(),
                patientProfileId(log),
                patient == null || patient.getUser() == null ? null : patient.getUser().getEmail(),
                log.getLogDate(),
                log.getAdherenceLevel(),
                log.getAppetiteLevel(),
                log.getMeals().size(),
                log.getDeviations().size(),
                measurementCount,
                notesPreview(log.getNotes()));
    }

    private static Long patientProfileId(DailyDietLog log) {
        var patient = log.getPatientProfile();
        return patient == null ? null : patient.getId();
    }

    private static String notesPreview(String value) {
        var trimmed = DietLogRequestMapper.trimToNull(value);
        if (trimmed == null || trimmed.length() <= NOTES_PREVIEW_LENGTH) {
            return trimmed;
        }
        return trimmed.substring(0, NOTES_PREVIEW_LENGTH);
    }
}
```

- [ ] **Step 2: Wire assembler into DietLogService**

Add field:

```java
private final DietLogResponseAssembler responseAssembler;
```

Add constructor parameter after `DietLogRequestMapper requestMapper`:

```java
DietLogResponseAssembler responseAssembler
```

Assign:

```java
this.responseAssembler = responseAssembler;
```

Update `DietLogServiceTest.setUp`:

```java
var responseAssembler = new DietLogResponseAssembler();
service = new DietLogService(
        users,
        patientProfiles,
        dailyDietLogs,
        measurements,
        accessControl,
        measurementValidator,
        requestMapper,
        responseAssembler);
```

- [ ] **Step 3: Delegate response creation**

In `DietLogService`, replace:

```java
return DailyDietLogResponse.from(saved, savedMeasurements);
return DailyDietLogResponse.from(log, measurementsFor(log));
.map(log -> summaryFrom(log, measurementCounts.getOrDefault(log.getId(), 0)))
```

with:

```java
return responseAssembler.full(saved, savedMeasurements);
return responseAssembler.full(log, measurementsFor(log));
.map(log -> responseAssembler.summary(log, measurementCounts.getOrDefault(log.getId(), 0)))
```

- [ ] **Step 4: Remove moved response helpers**

Delete from `DietLogService`:

```java
private static final int NOTES_PREVIEW_LENGTH = 120;
summaryFrom(...)
notesPreview(...)
```

Keep `patientProfileId(...)` if it is still used by clinical access and measurement counting.

- [ ] **Step 5: Run focused tests**

Run:

```bash
./gradlew test --tests com.metabion.service.DietLogServiceTest
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/metabion/service/DietLogService.java src/main/java/com/metabion/service/DietLogResponseAssembler.java src/test/java/com/metabion/service/DietLogServiceTest.java
git commit -m "Extract diet log response assembly"
```

---

### Task 6: Use MeasurementWindowService in DietLogService Read Paths

**Files:**
- Modify: `src/main/java/com/metabion/service/DietLogService.java`
- Modify: `src/test/java/com/metabion/service/DietLogServiceTest.java`

- [ ] **Step 1: Wire MeasurementWindowService into DietLogService**

Add field:

```java
private final MeasurementWindowService measurementWindows;
```

Add constructor parameter before `MeasurementValidator measurementValidator`:

```java
MeasurementWindowService measurementWindows
```

Assign:

```java
this.measurementWindows = measurementWindows;
```

Update `DietLogServiceTest.setUp` to pass the same `measurementWindows` instance used by `MeasurementValidator`.

- [ ] **Step 2: Replace day-window construction in measurementsFor**

In `measurementsFor`, replace:

```java
var zone = zoneFor(patient);
var fromInclusive = log.getLogDate().atStartOfDay(zone).toInstant();
var toExclusive = log.getLogDate().plusDays(1).atStartOfDay(zone).toInstant();
```

with:

```java
var window = measurementWindows.dayWindow(patient, log.getLogDate());
```

Replace repository arguments:

```java
window.fromInclusive(),
window.toExclusive()
```

- [ ] **Step 3: Replace range-window construction in measurementCountsFor**

In `measurementCountsFor`, replace:

```java
var zone = zoneFor(patient);
var minDate = logsByDate.keySet().stream().min(Comparator.naturalOrder()).orElseThrow();
var maxDate = logsByDate.keySet().stream().max(Comparator.naturalOrder()).orElseThrow();
var fromInclusive = minDate.atStartOfDay(zone).toInstant();
var toExclusive = maxDate.plusDays(1).atStartOfDay(zone).toInstant();
```

with:

```java
var zone = measurementWindows.zoneFor(patient);
var window = measurementWindows.dateRangeWindow(patient, logsByDate.keySet());
```

Replace repository arguments:

```java
window.fromInclusive(),
window.toExclusive()
```

Keep `zone` for converting standalone measurement instants back to local dates:

```java
.map(measuredAt -> measuredAt.atZone(zone).toLocalDate())
```

- [ ] **Step 4: Remove duplicated zoneFor from DietLogService**

Delete `zoneFor(PatientProfile patient)` from `DietLogService`.

Change `validateLogDate` future-date check from:

```java
if (logDate.isAfter(LocalDate.now(zoneFor(patient)))) {
```

to:

```java
if (logDate.isAfter(LocalDate.now(measurementWindows.zoneFor(patient)))) {
```

Remove now-unused `java.time.ZoneId` import.

- [ ] **Step 5: Use regular Stream import**

If `measurementsFor` still uses stream concatenation, add:

```java
import java.util.stream.Stream;
```

and replace:

```java
return java.util.stream.Stream.concat(linked.stream(), standalone.stream())
```

with:

```java
return Stream.concat(linked.stream(), standalone.stream())
```

- [ ] **Step 6: Run focused tests**

Run:

```bash
./gradlew test --tests com.metabion.service.MeasurementWindowServiceTest --tests com.metabion.service.MeasurementValidatorTest --tests com.metabion.service.DietLogServiceTest
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/metabion/service/DietLogService.java src/test/java/com/metabion/service/DietLogServiceTest.java
git commit -m "Centralize diet log measurement windows"
```

---

### Task 7: DietLogServiceTest Cleanup

**Files:**
- Modify: `src/test/java/com/metabion/service/DietLogServiceTest.java`

- [ ] **Step 1: Rename create-only test**

Rename:

```java
void createsOrReplacesCurrentPatientLog()
```

to:

```java
void createsCurrentPatientLog()
```

- [ ] **Step 2: Add authenticated patient helper**

Add helper:

```java
private PatientProfile givenAuthenticatedPatient() {
    var patientUser = user(1L, "patient@example.com", RoleName.PATIENT);
    var patient = patientProfile(10L, patientUser);
    when(users.findByEmail("patient@example.com")).thenReturn(Optional.of(patientUser));
    when(patientProfiles.findByUserId(1L)).thenReturn(Optional.of(patient));
    return patient;
}
```

Use this helper in tests that currently repeat exactly:

```java
var patientUser = user(1L, "patient@example.com", RoleName.PATIENT);
var patient = patientProfile(10L, patientUser);
when(users.findByEmail("patient@example.com")).thenReturn(Optional.of(patientUser));
when(patientProfiles.findByUserId(1L)).thenReturn(Optional.of(patient));
```

Do not use the helper in tests that intentionally use mixed-case authentication, non-patient roles, admin roles, or alternate patient ids.

- [ ] **Step 3: Run DietLogServiceTest**

Run:

```bash
./gradlew test --tests com.metabion.service.DietLogServiceTest
```

Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add src/test/java/com/metabion/service/DietLogServiceTest.java
git commit -m "Clean up diet log service tests"
```

---

### Task 8: Final Verification

**Files:**
- Verify all changed production and test files.

- [ ] **Step 1: Run focused diet-log service suite**

Run:

```bash
./gradlew test --tests com.metabion.service.StorageKeyValidatorTest --tests com.metabion.service.MeasurementWindowServiceTest --tests com.metabion.service.MeasurementValidatorTest --tests com.metabion.service.DietLogServiceTest
```

Expected: PASS.

- [ ] **Step 2: Run full test suite**

Run:

```bash
./gradlew test
```

Expected: PASS.

- [ ] **Step 3: Inspect final DietLogService responsibilities**

Run:

```bash
rg -n "validateStorageKey|validateMeasurement|validateMeasurementDate|outside\\(|notesPreview|java\\.util\\.stream\\.Stream\\.concat|ZoneId" src/main/java/com/metabion/service/DietLogService.java
```

Expected: no matches.

Run:

```bash
rg -n "ZoneId zoneFor\\(" src/main/java/com/metabion/service
```

Expected: only `src/main/java/com/metabion/service/MeasurementWindowService.java` contains the `zoneFor` method.

- [ ] **Step 4: Inspect git status**

Run:

```bash
git status --short
```

Expected: clean working tree after all task commits.
