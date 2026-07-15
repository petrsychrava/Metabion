# Accurate Trend Charts Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox syntax for tracking.

**Goal:** Replace the fixed glucose and ketone marker rows with two coordinated, value-scaled SVG charts that accurately show symptoms, flare state, glucose, ketones, units, timestamps, and missing-data gaps.

**Architecture:** Enrich `DailyTrendResponse` with the subject patient's display unit and timezone, convert glucose through a focused pure component, and build a presentation-only `TrendChartModel` containing scales, coordinates, segments, and marker shapes. `TrendSvgRenderer` will consume that model and render two localized accessible SVGs; controllers and templates remain server-rendered.

**Tech Stack:** Java 25, Spring Boot 4.0.6 MVC, Thymeleaf, SVG, JUnit 5, AssertJ, Mockito, Gradle wrapper.

## Global Constraints

- Preserve session-based authentication, existing patient/clinical access checks, and current REST endpoint authorization.
- Add no dependency and no Flyway migration.
- Keep the existing trends table and date filters unchanged.
- Use a default symptom scale of 0–30, expanding to a rounded upper bound above the observed maximum when a valid or historical score exceeds 30 (42 → 45).
- Use the subject patient's glucose preference, defaulting to mmol/L; ketones remain mmol/L.
- Glucose uses the left measurement y-axis and ketones use the right measurement y-axis.
- Missing calendar days break line segments; isolated values remain visible as points.
- Encode flare state with shape and color: circle for no flare, triangle for suspected flare, square for active flare.
- Add every new user-facing key to both `messages.properties` and `messages_cs.properties`.
- Keep both charts for observation-free ranges and render dedicated localized symptom, glucose, and ketone empty states in visible SVG text and descriptions.
- Preserve instant-based ordering/x positions and local-date segmentation while retaining the UTC offset in displayed measurement timestamps.
- Use contrast-tested trend variables in system/light/dark theme blocks; text colors meet 4.5:1 and meaningful series lines/markers meet 3:1 against the panel.
- Preserve unrelated worktree changes in `.idea/`, `application.properties`, and `var/`.

---

## File Map

- Modify `src/main/java/com/metabion/dto/DailyTrendResponse.java`: add chart display metadata to the existing response contract.
- Modify `src/main/java/com/metabion/service/DailyTrendService.java`: populate the subject patient's glucose unit and effective timezone.
- Create `src/main/java/com/metabion/controller/web/TrendGlucoseConverter.java`: perform presentation-only glucose unit conversion.
- Create `src/main/java/com/metabion/controller/web/TrendChartModel.java`: define chart geometry, axes, segments, points, and flare marker shapes.
- Create `src/main/java/com/metabion/controller/web/TrendChartModelBuilder.java`: validate plottable values, convert units, calculate scales/coordinates, and split sparse series.
- Replace `src/main/java/com/metabion/controller/web/TrendSvgRenderer.java`: render the two accessible SVG charts from `TrendChartModel`.
- Modify `src/main/java/com/metabion/controller/web/WebTrendController.java`: call the renderer's localized single-argument API.
- Modify `src/main/resources/static/css/app.css`: style chart stack, series, axes, flare shapes, focus, and SVG tooltips.
- Modify `src/main/resources/messages.properties`: add English chart names and per-series empty-state text.
- Modify `src/main/resources/messages_cs.properties`: add matching Czech chart names and per-series empty-state text.
- Modify `src/test/java/com/metabion/service/DailyTrendServiceTest.java`: verify response display metadata.
- Create `src/test/java/com/metabion/controller/web/TrendGlucoseConverterTest.java`: verify conversions and unsupported inputs.
- Create `src/test/java/com/metabion/controller/web/TrendChartModelBuilderTest.java`: verify scales, coordinates, segments, local time, and edge cases.
- Replace `src/test/java/com/metabion/controller/web/TrendSvgRendererTest.java`: verify two charts, dual axes, flare shapes, gaps, tooltips, escaping, and no-data output.
- Modify `src/test/java/com/metabion/controller/web/WebTrendControllerTest.java`: verify patient and clinical pages use the new renderer output.
- Modify `src/test/java/com/metabion/controller/api/SymptomTrackingControllerTest.java`: update the response fixture for new metadata.

---

### Task 1: Expose Patient Chart Metadata

**Files:**
- Modify: `src/main/java/com/metabion/dto/DailyTrendResponse.java:15-20`
- Modify: `src/main/java/com/metabion/service/DailyTrendService.java:84-110`
- Test: `src/test/java/com/metabion/service/DailyTrendServiceTest.java:83-159`
- Test fixtures: `src/test/java/com/metabion/controller/api/SymptomTrackingControllerTest.java:260-293`
- Test fixtures: `src/test/java/com/metabion/controller/web/WebTrendControllerTest.java:225-266`
- Test fixtures: `src/test/java/com/metabion/controller/web/TrendSvgRendererTest.java`

**Interfaces:**
- Produces: `DailyTrendResponse.glucoseUnit(): MeasurementUnit`
- Produces: `DailyTrendResponse.timezone(): String`
- Preserves: all existing `DayTrend` and `MeasurementPoint` fields unchanged.

- [x] **Step 1: Add a failing service assertion for subject metadata**

In `currentPatientTrendCombinesSymptomsDietLogsGlucoseAndKetonesForEveryDay`, set a non-default preference before invoking the service and assert both new values:

```java
patient.setGlucoseUnitPreference(MeasurementUnit.MG_DL);

var response = service.currentPatientTrend(patientAuth, from, to);

assertThat(response.glucoseUnit()).isEqualTo(MeasurementUnit.MG_DL);
assertThat(response.timezone()).isEqualTo("UTC");
```

- [x] **Step 2: Run the focused test and verify RED**

Run:

```bash
./gradlew test --tests 'com.metabion.service.DailyTrendServiceTest'
```

Expected: test compilation fails because `DailyTrendResponse` does not yet expose `glucoseUnit()` or `timezone()`.

- [x] **Step 3: Add response metadata and populate effective defaults**

Change the outer record signature to:

```java
public record DailyTrendResponse(
        Long patientProfileId,
        LocalDate from,
        LocalDate to,
        MeasurementUnit glucoseUnit,
        String timezone,
        List<DayTrend> days
) {
```

Change the service return statement to:

```java
var glucoseUnit = patient.getGlucoseUnitPreference() == null
        ? MeasurementUnit.MMOL_L
        : patient.getGlucoseUnitPreference();
var timezone = measurementWindows.zoneFor(patient).getId();
return new DailyTrendResponse(patientProfileId, from, to, glucoseUnit, timezone, dayTrends);
```

Add the `MeasurementUnit` import to `DailyTrendService`. Update every `new DailyTrendResponse(...)` fixture found by `rg -n "new DailyTrendResponse\\(" src/main src/test` with `MeasurementUnit.MMOL_L, "UTC"` between `to` and `days` unless the test requires another unit/timezone.

- [x] **Step 4: Verify service and boundary tests are GREEN**

Run:

```bash
./gradlew test --tests 'com.metabion.service.DailyTrendServiceTest' --tests 'com.metabion.controller.api.SymptomTrackingControllerTest' --tests 'com.metabion.controller.web.WebTrendControllerTest' --tests 'com.metabion.controller.web.TrendSvgRendererTest'
```

Expected: `BUILD SUCCESSFUL`; existing JSON and page assertions continue to pass with the additive metadata fields.

- [x] **Step 5: Commit the metadata change**

```bash
git add src/main/java/com/metabion/dto/DailyTrendResponse.java src/main/java/com/metabion/service/DailyTrendService.java src/test/java/com/metabion/service/DailyTrendServiceTest.java src/test/java/com/metabion/controller/api/SymptomTrackingControllerTest.java src/test/java/com/metabion/controller/web/WebTrendControllerTest.java src/test/java/com/metabion/controller/web/TrendSvgRendererTest.java
git commit -m "Expose trend chart display metadata"
```

---

### Task 2: Centralize Glucose Display Conversion

**Files:**
- Create: `src/main/java/com/metabion/controller/web/TrendGlucoseConverter.java`
- Create: `src/test/java/com/metabion/controller/web/TrendGlucoseConverterTest.java`

**Interfaces:**
- Produces: `BigDecimal TrendGlucoseConverter.convert(BigDecimal value, MeasurementUnit source, MeasurementUnit target)`
- Behavior: returns `null` for null values or unsupported source/target units; converts glucose only between `MMOL_L` and `MG_DL` using factor 18.

- [x] **Step 1: Write failing conversion tests**

```java
package com.metabion.controller.web;

import com.metabion.domain.MeasurementUnit;
import org.springframework.stereotype.Component;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class TrendGlucoseConverterTest {

    private final TrendGlucoseConverter converter = new TrendGlucoseConverter();

    @Test
    void convertsGlucoseInBothDirectionsAndPreservesSameUnitValues() {
        assertThat(converter.convert(new BigDecimal("5.80"), MeasurementUnit.MMOL_L, MeasurementUnit.MG_DL))
                .isEqualByComparingTo("104.40");
        assertThat(converter.convert(new BigDecimal("104.40"), MeasurementUnit.MG_DL, MeasurementUnit.MMOL_L))
                .isEqualByComparingTo("5.80");
        assertThat(converter.convert(new BigDecimal("5.80"), MeasurementUnit.MMOL_L, MeasurementUnit.MMOL_L))
                .isEqualByComparingTo("5.80");
    }

    @Test
    void rejectsMissingAndUnsupportedConversionInputsWithoutGuessing() {
        assertThat(converter.convert(null, MeasurementUnit.MMOL_L, MeasurementUnit.MG_DL)).isNull();
        assertThat(converter.convert(BigDecimal.ONE, null, MeasurementUnit.MMOL_L)).isNull();
        assertThat(converter.convert(BigDecimal.ONE, MeasurementUnit.MMOL_L, null)).isNull();
    }
}
```

- [x] **Step 2: Run the test and verify RED**

Run:

```bash
./gradlew test --tests 'com.metabion.controller.web.TrendGlucoseConverterTest'
```

Expected: test compilation fails because `TrendGlucoseConverter` does not exist.

- [x] **Step 3: Implement the pure converter**

```java
package com.metabion.controller.web;

import com.metabion.domain.MeasurementUnit;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Component
final class TrendGlucoseConverter {

    private static final BigDecimal MG_DL_PER_MMOL_L = new BigDecimal("18");

    BigDecimal convert(BigDecimal value, MeasurementUnit source, MeasurementUnit target) {
        if (value == null || source == null || target == null) {
            return null;
        }
        if (source == target) {
            return value;
        }
        if (source == MeasurementUnit.MMOL_L && target == MeasurementUnit.MG_DL) {
            return value.multiply(MG_DL_PER_MMOL_L).setScale(2, RoundingMode.HALF_UP);
        }
        if (source == MeasurementUnit.MG_DL && target == MeasurementUnit.MMOL_L) {
            return value.divide(MG_DL_PER_MMOL_L, 2, RoundingMode.HALF_UP);
        }
        return null;
    }
}
```

- [x] **Step 4: Run the test and verify GREEN**

Run:

```bash
./gradlew test --tests 'com.metabion.controller.web.TrendGlucoseConverterTest'
```

Expected: `BUILD SUCCESSFUL` and 2 tests pass.

- [x] **Step 5: Commit the converter**

```bash
git add src/main/java/com/metabion/controller/web/TrendGlucoseConverter.java src/test/java/com/metabion/controller/web/TrendGlucoseConverterTest.java
git commit -m "Add trend glucose unit conversion"
```

---

### Task 3: Build Scaled and Segmented Chart Models

**Files:**
- Create: `src/main/java/com/metabion/controller/web/TrendChartModel.java`
- Create: `src/main/java/com/metabion/controller/web/TrendChartModelBuilder.java`
- Create: `src/test/java/com/metabion/controller/web/TrendChartModelBuilderTest.java`

**Interfaces:**
- Consumes: `TrendGlucoseConverter.convert(...)` from Task 2.
- Consumes: `DailyTrendResponse.glucoseUnit()` and `DailyTrendResponse.timezone()` from Task 1.
- Produces: `TrendChartModel TrendChartModelBuilder.build(DailyTrendResponse trend)`.
- Produces immutable nested records `Geometry`, `Axis`, `DateTick`, `Segment<T>`, `SymptomPoint`, and `MeasurementPoint`.

- [x] **Step 1: Write failing tests for axes, conversion, and y-coordinates**

Create tests using a response containing glucose `5.00` and `5.20` mmol/L plus ketones `2.00` and `0.20` mmol/L. Assert:

```java
var response = trend(
        "UTC",
        MeasurementUnit.MMOL_L,
        day(LocalDate.of(2026, 6, 16), null, null,
                List.of(glucose("5.00", MeasurementUnit.MMOL_L, "2026-06-16T08:00:00Z")),
                List.of(ketone("2.00", "2026-06-16T08:05:00Z"))),
        day(LocalDate.of(2026, 7, 9), new BigDecimal("3.00"), FlareState.NO_FLARE,
                List.of(glucose("5.20", MeasurementUnit.MMOL_L, "2026-07-09T08:00:00Z")),
                List.of(ketone("0.20", "2026-07-09T08:05:00Z"))));
var model = builder.build(response);

assertThat(model.glucoseAxis().unit()).isEqualTo(MeasurementUnit.MMOL_L);
assertThat(model.glucoseAxis().max().subtract(model.glucoseAxis().min()))
        .isGreaterThanOrEqualTo(new BigDecimal("2.0"));
assertThat(model.ketoneAxis().min()).isEqualByComparingTo(BigDecimal.ZERO);
assertThat(model.ketoneAxis().max()).isGreaterThan(new BigDecimal("2.00"));
assertThat(flatten(model.glucoseSegments())).extracting(TrendChartModel.MeasurementPoint::y)
        .doesNotHaveDuplicates();
assertThat(flatten(model.ketoneSegments())).extracting(TrendChartModel.MeasurementPoint::y)
        .doesNotHaveDuplicates();
```

Add an mg/dL-preference case asserting a stored `5.80 mmol/L` point becomes `104.40 mg/dL` in the model.

- [x] **Step 2: Run the builder test and verify RED**

Run:

```bash
./gradlew test --tests 'com.metabion.controller.web.TrendChartModelBuilderTest'
```

Expected: test compilation fails because the chart model and builder do not exist.

- [x] **Step 3: Define the immutable chart model**

```java
package com.metabion.controller.web;

import com.metabion.domain.FlareState;
import com.metabion.domain.MeasurementType;
import com.metabion.domain.MeasurementUnit;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

record TrendChartModel(
        Geometry geometry,
        List<DateTick> dateTicks,
        Axis symptomAxis,
        List<Segment<SymptomPoint>> symptomSegments,
        Axis glucoseAxis,
        List<Segment<MeasurementPoint>> glucoseSegments,
        Axis ketoneAxis,
        List<Segment<MeasurementPoint>> ketoneSegments
) {
    record Geometry(int width, int height, int left, int right, int top, int bottom) {
    }

    record Axis(BigDecimal min, BigDecimal max, List<BigDecimal> ticks, MeasurementUnit unit) {
    }

    record DateTick(int x, LocalDate date) {
    }

    record Segment<T>(List<T> points) {
    }

    record SymptomPoint(int x, int y, LocalDate date, BigDecimal value,
                        FlareState flareState, MarkerShape shape) {
    }

    record MeasurementPoint(int x, int y, OffsetDateTime measuredAt, BigDecimal value,
                            MeasurementUnit unit, MeasurementType type) {
    }

    enum MarkerShape {
        CIRCLE,
        TRIANGLE,
        SQUARE
    }
}
```

- [x] **Step 4: Implement builder geometry, supported-value filtering, and scale policies**

Create `TrendChartModelBuilder` as a Spring `@Component` with constructor injection of `TrendGlucoseConverter`. Use these constants and policies:

```java
static final TrendChartModel.Geometry GEOMETRY =
        new TrendChartModel.Geometry(640, 220, 64, 576, 32, 176);
private static final BigDecimal DEFAULT_MAX_SYMPTOM_SCORE = new BigDecimal("30");
private static final BigDecimal SYMPTOM_STEP = new BigDecimal("15");
private static final BigDecimal MIN_GLUCOSE_MMOL_SPAN = new BigDecimal("2");
private static final BigDecimal MIN_GLUCOSE_MG_SPAN = new BigDecimal("36");
private static final BigDecimal GLUCOSE_MMOL_STEP = new BigDecimal("0.5");
private static final BigDecimal GLUCOSE_MG_STEP = new BigDecimal("10");
private static final BigDecimal KETONE_STEP = new BigDecimal("0.5");
private static final BigDecimal MIN_KETONE_MAX = BigDecimal.ONE;
private static final int MIN_DATE_TICK_SPACING = 80;

private final TrendGlucoseConverter glucoseConverter;

TrendChartModelBuilder(TrendGlucoseConverter glucoseConverter) {
    this.glucoseConverter = glucoseConverter;
}
```

The outer method must default metadata safely and return all series:

```java
TrendChartModel build(DailyTrendResponse trend) {
    var glucoseUnit = trend.glucoseUnit() == MeasurementUnit.MG_DL
            ? MeasurementUnit.MG_DL
            : MeasurementUnit.MMOL_L;
    var zone = zone(trend.timezone());
    var glucose = glucosePoints(trend, glucoseUnit, zone);
    var ketones = ketonePoints(trend, zone);
    var symptomAxis = symptomAxis(trend);
    var glucoseAxis = glucoseAxis(glucose.stream().map(RawMeasurementPoint::value).toList(), glucoseUnit);
    var ketoneAxis = ketoneAxis(ketones.stream().map(RawMeasurementPoint::value).toList());
    return new TrendChartModel(
            GEOMETRY,
            dateTicks(trend.from(), trend.to(), zone),
            symptomAxis,
            symptomSegments(trend, symptomAxis, zone),
            glucoseAxis,
            measurementSegments(glucose, glucoseAxis, trend.from(), trend.to(), zone),
            ketoneAxis,
            measurementSegments(ketones, ketoneAxis, trend.from(), trend.to(), zone));
}
```

Define the builder's raw value before the extraction helpers:

```java
private record RawMeasurementPoint(
        LocalDate date,
        Instant instant,
        OffsetDateTime measuredAt,
        BigDecimal value,
        MeasurementUnit unit,
        MeasurementType type
) {
}
```

Extract and normalize only supported measurements:

```java
private ZoneId zone(String timezone) {
    if (timezone == null || timezone.isBlank()) {
        return ZoneId.systemDefault();
    }
    try {
        return ZoneId.of(timezone);
    } catch (DateTimeException ignored) {
        return ZoneId.systemDefault();
    }
}

private List<RawMeasurementPoint> glucosePoints(DailyTrendResponse trend,
                                                 MeasurementUnit target,
                                                 ZoneId zone) {
    return safe(trend.days()).stream()
            .filter(Objects::nonNull)
            .flatMap(day -> safe(day.glucoseMeasurements()).stream())
            .filter(Objects::nonNull)
            .filter(point -> point.measurementType() == MeasurementType.GLUCOSE)
            .filter(point -> point.value() != null && point.unit() != null && point.measuredAt() != null)
            .map(point -> {
                var converted = glucoseConverter.convert(point.value(), point.unit(), target);
                return converted == null ? null : new RawMeasurementPoint(
                        point.measuredAt().atZone(zone).toLocalDate(),
                        point.measuredAt(),
                        point.measuredAt().atZone(zone).toOffsetDateTime(),
                        converted,
                        target,
                        MeasurementType.GLUCOSE);
            })
            .filter(Objects::nonNull)
            .sorted(Comparator.comparing(RawMeasurementPoint::instant))
            .toList();
}

private List<RawMeasurementPoint> ketonePoints(DailyTrendResponse trend, ZoneId zone) {
    return safe(trend.days()).stream()
            .filter(Objects::nonNull)
            .flatMap(day -> safe(day.ketoneMeasurements()).stream())
            .filter(Objects::nonNull)
            .filter(point -> point.measurementType() == MeasurementType.KETONE)
            .filter(point -> point.unit() == MeasurementUnit.MMOL_L)
            .filter(point -> point.value() != null && point.measuredAt() != null)
            .map(point -> new RawMeasurementPoint(
                    point.measuredAt().atZone(zone).toLocalDate(),
                    point.measuredAt(),
                    point.measuredAt().atZone(zone).toOffsetDateTime(),
                    point.value(),
                    MeasurementUnit.MMOL_L,
                    MeasurementType.KETONE))
            .sorted(Comparator.comparing(RawMeasurementPoint::instant))
            .toList();
}

private <T> List<T> safe(List<T> values) {
    return values == null ? List.of() : values;
}
```

Use rounded bounds:

```java
private BigDecimal floorTo(BigDecimal value, BigDecimal step) {
    return value.divide(step, 0, RoundingMode.FLOOR).multiply(step);
}

private BigDecimal ceilTo(BigDecimal value, BigDecimal step) {
    return value.divide(step, 0, RoundingMode.CEILING).multiply(step);
}

private List<BigDecimal> threeTicks(BigDecimal min, BigDecimal max) {
    return List.of(min, min.add(max).divide(new BigDecimal("2"), 2, RoundingMode.HALF_UP), max);
}
```

Implement both axis policies with the same helpers:

```java
private TrendChartModel.Axis glucoseAxis(List<BigDecimal> values, MeasurementUnit unit) {
    if (values.isEmpty()) {
        return unit == MeasurementUnit.MG_DL
                ? axis(new BigDecimal("72"), new BigDecimal("144"), unit)
                : axis(new BigDecimal("4"), new BigDecimal("8"), unit);
    }
    var min = values.stream().min(BigDecimal::compareTo).orElseThrow();
    var max = values.stream().max(BigDecimal::compareTo).orElseThrow();
    var minimumSpan = unit == MeasurementUnit.MG_DL ? MIN_GLUCOSE_MG_SPAN : MIN_GLUCOSE_MMOL_SPAN;
    var step = unit == MeasurementUnit.MG_DL ? GLUCOSE_MG_STEP : GLUCOSE_MMOL_STEP;
    var span = max.subtract(min).max(minimumSpan);
    var center = min.add(max).divide(new BigDecimal("2"), 4, RoundingMode.HALF_UP);
    var halfSpan = span.divide(new BigDecimal("2"), 4, RoundingMode.HALF_UP);
    var lower = floorTo(center.subtract(halfSpan), step).max(BigDecimal.ZERO);
    var upper = ceilTo(center.add(halfSpan), step);
    if (upper.subtract(lower).compareTo(minimumSpan) < 0) {
        upper = ceilTo(lower.add(minimumSpan), step);
    }
    return axis(lower, upper, unit);
}

private TrendChartModel.Axis ketoneAxis(List<BigDecimal> values) {
    if (values.isEmpty()) {
        return axis(BigDecimal.ZERO, MIN_KETONE_MAX, MeasurementUnit.MMOL_L);
    }
    var max = values.stream().max(BigDecimal::compareTo).orElseThrow().max(BigDecimal.ZERO);
    var upper = ceilTo(max, KETONE_STEP);
    if (upper.compareTo(max) <= 0) {
        upper = upper.add(KETONE_STEP);
    }
    return axis(BigDecimal.ZERO, upper.max(MIN_KETONE_MAX), MeasurementUnit.MMOL_L);
}

private TrendChartModel.Axis axis(BigDecimal min, BigDecimal max, MeasurementUnit unit) {
    return new TrendChartModel.Axis(min, max, threeTicks(min, max), unit);
}
```

For glucose, expand the observed range around its midpoint to the minimum span, then floor/ceil with the unit-specific step. For an empty series, use `4–8 mmol/L` or `72–144 mg/dL`. For ketones, use minimum `0–1 mmol/L`; otherwise set the upper bound to the next `0.5` increment strictly above the observed maximum.

- [x] **Step 5: Add failing tests for local-time x positions and sparse segments**

Add these cases:

```java
@Test
void usesPatientLocalMeasurementTimeAndOffsetForXCoordinates() {
    var response = trend("America/New_York", MeasurementUnit.MMOL_L,
            day(LocalDate.of(2026, 6, 10), null, null,
                    List.of(glucose("5.8", MeasurementUnit.MMOL_L, "2026-06-10T11:00:00Z"),
                            glucose("6.2", MeasurementUnit.MMOL_L, "2026-06-10T23:00:00Z")),
                    List.of()));

    var points = flatten(builder.build(response).glucoseSegments());

    assertThat(points).extracting(TrendChartModel.MeasurementPoint::measuredAt)
            .containsExactly(OffsetDateTime.parse("2026-06-10T07:00:00-04:00"),
                    OffsetDateTime.parse("2026-06-10T19:00:00-04:00"));
    assertThat(points.get(1).x()).isGreaterThan(points.get(0).x());
}

@Test
void splitsSeriesAcrossMissingDaysButConnectsSameAndConsecutiveDates() {
    var model = builder.build(responseWithMeasurementsOnJune1June2AndJune4());

    assertThat(model.glucoseSegments()).extracting(segment -> segment.points().size())
            .containsExactly(2, 1);
}
```

Define the test helpers explicitly:

```java
private DailyTrendResponse trend(String timezone,
                                 MeasurementUnit glucoseUnit,
                                 DailyTrendResponse.DayTrend... days) {
    var values = List.of(days);
    return new DailyTrendResponse(
            10L,
            values.getFirst().date(),
            values.getLast().date(),
            glucoseUnit,
            timezone,
            values);
}

private DailyTrendResponse.DayTrend day(LocalDate date,
                                        BigDecimal symptomScore,
                                        FlareState flareState,
                                        List<DailyTrendResponse.MeasurementPoint> glucose,
                                        List<DailyTrendResponse.MeasurementPoint> ketones) {
    return new DailyTrendResponse.DayTrend(
            date,
            symptomScore == null ? null : 100L,
            symptomScore,
            flareState,
            null,
            null,
            null,
            glucose,
            ketones);
}

private DailyTrendResponse.MeasurementPoint glucose(String value,
                                                    MeasurementUnit unit,
                                                    String measuredAt) {
    return new DailyTrendResponse.MeasurementPoint(
            300L,
            MeasurementType.GLUCOSE,
            new BigDecimal(value),
            unit,
            Instant.parse(measuredAt),
            MeasurementContext.FASTING);
}

private DailyTrendResponse.MeasurementPoint ketone(String value, String measuredAt) {
    return new DailyTrendResponse.MeasurementPoint(
            301L,
            MeasurementType.KETONE,
            new BigDecimal(value),
            MeasurementUnit.MMOL_L,
            Instant.parse(measuredAt),
            MeasurementContext.FASTING);
}

private DailyTrendResponse responseWithMeasurementsOnJune1June2AndJune4() {
    return trend(
            "UTC",
            MeasurementUnit.MMOL_L,
            day(LocalDate.of(2026, 6, 1), null, null,
                    List.of(glucose("5.0", MeasurementUnit.MMOL_L, "2026-06-01T08:00:00Z")), List.of()),
            day(LocalDate.of(2026, 6, 2), null, null,
                    List.of(glucose("5.2", MeasurementUnit.MMOL_L, "2026-06-02T08:00:00Z")), List.of()),
            day(LocalDate.of(2026, 6, 3), null, null, List.of(), List.of()),
            day(LocalDate.of(2026, 6, 4), null, null,
                    List.of(glucose("5.4", MeasurementUnit.MMOL_L, "2026-06-04T08:00:00Z")), List.of()));
}

private <T> List<T> flatten(List<TrendChartModel.Segment<T>> segments) {
    return segments.stream().flatMap(segment -> segment.points().stream()).toList();
}
```

- [x] **Step 6: Implement x/y mapping, marker shapes, and segment splitting**

Use the effective zone and the full local range for x coordinates:

```java
private int x(LocalDateTime value, LocalDate from, LocalDate to, ZoneId zone) {
    return x(value.atZone(zone).toInstant(), from, to, zone);
}

private int x(Instant value, LocalDate from, LocalDate to, ZoneId zone) {
    var rangeStart = from.atStartOfDay(zone).toInstant();
    var rangeEnd = to.plusDays(1).atStartOfDay(zone).toInstant();
    var total = Duration.between(rangeStart, rangeEnd).toMillis();
    var offset = Duration.between(rangeStart, value).toMillis();
    var ratio = total <= 0 ? 0.5 : Math.max(0, Math.min(1, offset / (double) total));
    return (int) Math.round(GEOMETRY.left() + ratio * (GEOMETRY.right() - GEOMETRY.left()));
}

private int y(BigDecimal value, BigDecimal min, BigDecimal max) {
    var span = max.subtract(min);
    var ratio = span.signum() == 0 ? 0.5 : value.subtract(min).doubleValue() / span.doubleValue();
    var clamped = Math.max(0, Math.min(1, ratio));
    return (int) Math.round(GEOMETRY.bottom() - clamped * (GEOMETRY.bottom() - GEOMETRY.top()));
}
```

Map symptom dates to local noon and map flare shapes explicitly:

```java
private TrendChartModel.MarkerShape markerShape(FlareState state) {
    if (state == null) {
        return TrendChartModel.MarkerShape.CIRCLE;
    }
    return switch (state) {
        case SUSPECTED_FLARE -> TrendChartModel.MarkerShape.TRIANGLE;
        case ACTIVE_FLARE -> TrendChartModel.MarkerShape.SQUARE;
        case NO_FLARE -> TrendChartModel.MarkerShape.CIRCLE;
    };
}
```

Build ticks, chart points, and chronological segments with these helpers:

```java
private List<TrendChartModel.DateTick> dateTicks(LocalDate from, LocalDate to, ZoneId zone) {
    var dates = from.datesUntil(to.plusDays(1)).toList();
    if (dates.size() == 1) {
        var date = dates.getFirst();
        return List.of(new TrendChartModel.DateTick(x(date.atTime(12, 0), from, to, zone), date));
    }
    var plotWidth = GEOMETRY.right() - GEOMETRY.left();
    var maximumTickCount = Math.min(dates.size(), plotWidth / MIN_DATE_TICK_SPACING + 1);
    for (var tickCount = maximumTickCount; tickCount >= 2; tickCount--) {
        var ticks = evenlySpacedDateTicks(dates, tickCount, from, to, zone);
        if (hasMinimumDateTickSpacing(ticks)) {
            return ticks;
        }
    }
    throw new IllegalStateException("Unable to place trend date ticks");
}

private List<TrendChartModel.DateTick> evenlySpacedDateTicks(List<LocalDate> dates,
                                                              int tickCount,
                                                              LocalDate from,
                                                              LocalDate to,
                                                              ZoneId zone) {
    var ticks = new ArrayList<TrendChartModel.DateTick>();
    for (var position = 0; position < tickCount; position++) {
        var dateIndex = (int) Math.round(position * (dates.size() - 1.0) / (tickCount - 1.0));
        var date = dates.get(dateIndex);
        ticks.add(new TrendChartModel.DateTick(x(date.atTime(12, 0), from, to, zone), date));
    }
    return List.copyOf(ticks);
}

private boolean hasMinimumDateTickSpacing(List<TrendChartModel.DateTick> ticks) {
    for (var index = 1; index < ticks.size(); index++) {
        if (ticks.get(index).x() - ticks.get(index - 1).x() < MIN_DATE_TICK_SPACING) {
            return false;
        }
    }
    return true;
}

private List<TrendChartModel.Segment<TrendChartModel.SymptomPoint>> symptomSegments(
        DailyTrendResponse trend, TrendChartModel.Axis symptomAxis, ZoneId zone) {
    var points = safe(trend.days()).stream()
            .filter(Objects::nonNull)
            .filter(day -> day.date() != null && day.symptomScore() != null)
            .sorted(Comparator.comparing(DailyTrendResponse.DayTrend::date))
            .map(day -> {
                var score = day.symptomScore().max(symptomAxis.min());
                return new TrendChartModel.SymptomPoint(
                        x(day.date().atTime(12, 0), trend.from(), trend.to(), zone),
                        y(score, symptomAxis.min(), symptomAxis.max()),
                        day.date(),
                        day.symptomScore(),
                        day.flareState(),
                        markerShape(day.flareState()));
            })
            .toList();
    return segments(points, TrendChartModel.SymptomPoint::date);
}

private List<TrendChartModel.Segment<TrendChartModel.MeasurementPoint>> measurementSegments(
        List<RawMeasurementPoint> raw,
        TrendChartModel.Axis axis,
        LocalDate from,
        LocalDate to,
        ZoneId zone) {
    var points = raw.stream()
            .map(point -> new TrendChartModel.MeasurementPoint(
                    x(point.instant(), from, to, zone),
                    y(point.value(), axis.min(), axis.max()),
                    point.measuredAt(),
                    point.value(),
                    point.unit(),
                    point.type()))
            .toList();
    return segments(points, point -> point.measuredAt().toLocalDate());
}

private <T> List<TrendChartModel.Segment<T>> segments(List<T> points,
                                                      Function<T, LocalDate> dateOf) {
    var result = new ArrayList<TrendChartModel.Segment<T>>();
    var current = new ArrayList<T>();
    LocalDate previousDate = null;
    for (var point : points) {
        var currentDate = dateOf.apply(point);
        if (previousDate != null && previousDate.plusDays(1).isBefore(currentDate)) {
            result.add(new TrendChartModel.Segment<>(List.copyOf(current)));
            current.clear();
        }
        current.add(point);
        previousDate = currentDate;
    }
    if (!current.isEmpty()) {
        result.add(new TrendChartModel.Segment<>(List.copyOf(current)));
    }
    return List.copyOf(result);
}
```

The extraction filters above omit points with null value, type, unit, timestamp, or unsupported type/unit combinations.

- [x] **Step 7: Run model and converter tests and verify GREEN**

Run:

```bash
./gradlew test --tests 'com.metabion.controller.web.TrendGlucoseConverterTest' --tests 'com.metabion.controller.web.TrendChartModelBuilderTest'
```

Expected: `BUILD SUCCESSFUL`; conversion, scaling, local-time positioning, segment, isolated-point, and empty-series cases pass.

- [x] **Step 8: Commit the chart model**

```bash
git add src/main/java/com/metabion/controller/web/TrendChartModel.java src/main/java/com/metabion/controller/web/TrendChartModelBuilder.java src/test/java/com/metabion/controller/web/TrendChartModelBuilderTest.java
git commit -m "Build scaled trend chart models"
```

---

### Task 4: Render Two Accessible SVG Charts

**Files:**
- Replace: `src/main/java/com/metabion/controller/web/TrendSvgRenderer.java`
- Replace: `src/test/java/com/metabion/controller/web/TrendSvgRendererTest.java`

**Interfaces:**
- Consumes: `TrendChartModelBuilder.build(DailyTrendResponse)` from Task 3.
- Produces: `String TrendSvgRenderer.render(DailyTrendResponse trend)`.
- Depends on: Spring `MessageSource` and `LocaleContextHolder` for localized labels.

- [x] **Step 1: Replace fixed-band tests with failing two-chart assertions**

Construct the renderer with a real builder and a `StaticMessageSource`. Register English messages for `trends.noData`, `trends.symptomChart`, `trends.measurementChart`, `trends.symptomScore`, `trends.glucose`, `trends.ketones`, the two measurement units, and all three flare states.

Add assertions equivalent to:

```java
var svg = renderer.render(response);

assertThat(svg).contains("class=\"trend-chart trend-chart-symptoms\"")
        .contains("class=\"trend-chart trend-chart-measurements\"")
        .contains("class=\"trend-axis-label glucose\"")
        .contains("class=\"trend-axis-label ketone\"")
        .contains("data-value=\"5.00\"")
        .contains("data-value=\"5.20\"")
        .contains("data-value=\"2.00\"")
        .contains("data-value=\"0.20\"");

var glucoseY = yCoordinates(svg, "glucose");
var ketoneY = yCoordinates(svg, "ketone");
assertThat(glucoseY).doesNotHaveDuplicates();
assertThat(ketoneY).doesNotHaveDuplicates();
```

Use this parsing helper in the renderer test:

```java
private List<Integer> yCoordinates(String svg, String series) {
    var matcher = Pattern.compile(
            "class=\\\"trend-point " + Pattern.quote(series) + "\\\"[^>]*data-y=\\\"(\\d+)\\\"")
            .matcher(svg);
    var values = new ArrayList<Integer>();
    while (matcher.find()) {
        values.add(Integer.parseInt(matcher.group(1)));
    }
    return List.copyOf(values);
}
```

The renderer must therefore put `data-y` on each point group in addition to the semantic value/unit attributes.

Add separate tests asserting circle/triangle/square markup, only one polyline per sparse segment with at least two points, focusable point groups, escaped labels, localized no-data output, and visible values in tooltip text.

- [x] **Step 2: Run renderer tests and verify RED**

Run:

```bash
./gradlew test --tests 'com.metabion.controller.web.TrendSvgRendererTest'
```

Expected: assertions fail because the renderer still emits one SVG and fixed measurement y rows.

- [x] **Step 3: Inject the model builder and message source**

Use this class boundary:

```java
@Component
public class TrendSvgRenderer {

    private final TrendChartModelBuilder models;
    private final MessageSource messages;

    public TrendSvgRenderer(TrendChartModelBuilder models, MessageSource messages) {
        this.models = models;
        this.messages = messages;
    }

    public String render(DailyTrendResponse trend) {
        if (trend == null || trend.days() == null || trend.days().isEmpty()) {
            return noDataSvg(message("trends.noData"));
        }
        var model = models.build(trend);
        return "<div class=\"trend-chart-stack\">"
                + symptomSvg(model)
                + measurementSvg(model)
                + "</div>";
    }

    private String message(String key, Object... arguments) {
        return messages.getMessage(key, arguments, LocaleContextHolder.getLocale());
    }
}
```

Add the localized empty-state and escaping helpers used by that boundary:

```java
private String noDataSvg(String label) {
    var escaped = escape(label);
    return """
            <svg class="trend-chart trend-chart-empty" viewBox="0 0 640 220"
                 role="img" aria-label="%s">
              <text x="320" y="110" text-anchor="middle">%s</text>
            </svg>
            """.formatted(escaped, escaped);
}

private String escape(String value) {
    return value == null ? "" : value
            .replace("&", "&amp;")
            .replace("\"", "&quot;")
            .replace("<", "&lt;")
            .replace(">", "&gt;");
}
```

- [x] **Step 4: Render axes and segmented lines**

For each graph, render its own `<svg>` with `viewBox="0 0 640 220"`, `<title>`, `<desc>`, horizontal date ticks from `model.dateTicks()`, and y ticks from the relevant axes.

Render a polyline only when a segment contains at least two points:

```java
private <T> String polyline(TrendChartModel.Segment<T> segment,
                            Function<T, Integer> x,
                            Function<T, Integer> y,
                            String cssClass) {
    if (segment.points().size() < 2) {
        return "";
    }
    var points = segment.points().stream()
            .map(point -> x.apply(point) + "," + y.apply(point))
            .collect(Collectors.joining(" "));
    return "<polyline class=\"trend-line " + cssClass + "\" points=\"" + points + "\" fill=\"none\" />";
}
```

Use the glucose axis for glucose y ticks and the left axis line; use the ketone axis for ketone y ticks and the right axis line. Never share one numeric y transform between the two measurement series.

- [x] **Step 5: Render flare shapes and accessible measurement points**

Render shapes from `MarkerShape`:

```java
private String symptomShape(TrendChartModel.SymptomPoint point) {
    return switch (point.shape()) {
        case CIRCLE -> "<circle class=\"trend-marker flare-no\" cx=\"%d\" cy=\"%d\" r=\"6\" />"
                .formatted(point.x(), point.y());
        case TRIANGLE -> "<polygon class=\"trend-marker flare-suspected\" points=\"%d,%d %d,%d %d,%d\" />"
                .formatted(point.x(), point.y() - 7, point.x() - 7, point.y() + 6,
                        point.x() + 7, point.y() + 6);
        case SQUARE -> "<rect class=\"trend-marker flare-active\" x=\"%d\" y=\"%d\" width=\"12\" height=\"12\" />"
                .formatted(point.x() - 6, point.y() - 6);
    };
}
```

Wrap every point in a group such as `<g class="trend-point" tabindex="0" role="img" aria-label="2026-07-09, Glucose 5.20 mmol/L">`. Include escaped `data-value`, `data-unit`, and date/time attributes plus `<title>` fallback text. Add a nested `.trend-tooltip` group with two text lines: local date/time and exact localized value/state. Clamp its x position inside the plot and place it above the point unless the point is near the top.

- [x] **Step 6: Run renderer tests and verify GREEN**

Run:

```bash
./gradlew test --tests 'com.metabion.controller.web.TrendSvgRendererTest'
```

Expected: `BUILD SUCCESSFUL`; two-chart, dual-axis, flare-shape, segmentation, accessibility, escaping, and no-data tests pass.

- [x] **Step 7: Commit the renderer**

```bash
git add src/main/java/com/metabion/controller/web/TrendSvgRenderer.java src/test/java/com/metabion/controller/web/TrendSvgRendererTest.java
git commit -m "Render accurate dual trend charts"
```

---

### Task 5: Localize, Style, and Verify the Web Pages

**Files:**
- Modify: `src/main/java/com/metabion/controller/web/WebTrendController.java:25-46,91-96,124-126`
- Modify: `src/main/resources/static/css/app.css:361-430`
- Modify: `src/main/resources/messages.properties:276-284`
- Modify: `src/main/resources/messages_cs.properties:275-283`
- Modify: `src/test/java/com/metabion/controller/web/WebTrendControllerTest.java:80-225`
- Test: `src/test/java/com/metabion/config/LocalizationConfigTest.java`

**Interfaces:**
- Consumes: `TrendSvgRenderer.render(DailyTrendResponse)` from Task 4.
- Preserves: `trendSvg` model attribute and both existing templates.

- [x] **Step 1: Add failing web and localization assertions**

Update the mocked renderer setup to return recognizable two-chart markup from the single-argument method:

```java
when(trendSvgRenderer.render(any())).thenReturn("""
        <div class="trend-chart-stack">
          <svg class="trend-chart trend-chart-symptoms" role="img"></svg>
          <svg class="trend-chart trend-chart-measurements" role="img"></svg>
        </div>
        """);
```

Assert both chart classes appear on patient and selected clinical pages, and verify `trendSvgRenderer.render(trendResponse())` without the old no-data-label overload.

Add localization assertions for these keys in English and Czech:

```properties
trends.symptomChart=Symptom score and flare-state trend
trends.measurementChart=Glucose and ketone trend
trends.noSymptomData=No symptom observations
trends.noGlucoseData=No glucose measurements
trends.noKetoneData=No ketone measurements
```

```properties
trends.symptomChart=Trend skóre symptomů a stavu vzplanutí
trends.measurementChart=Trend glukózy a ketonů
trends.noSymptomData=Nejsou dostupná pozorování symptomů
trends.noGlucoseData=Nejsou dostupná měření glukózy
trends.noKetoneData=Nejsou dostupná měření ketonů
```

- [x] **Step 2: Run web and localization tests and verify RED**

Run:

```bash
./gradlew test --tests 'com.metabion.controller.web.WebTrendControllerTest' --tests 'com.metabion.config.LocalizationConfigTest'
```

Expected: compilation or assertions fail until the controller uses the new renderer method and both bundles contain the new keys.

- [x] **Step 3: Simplify controller rendering and add aligned message keys**

Change `addTrendModel` to:

```java
private void addTrendModel(Model model, DailyTrendResponse trend, DateRange range) {
    model.addAttribute("from", range.from());
    model.addAttribute("to", range.to());
    model.addAttribute("trend", trend);
    model.addAttribute("trendSvg", trendSvgRenderer.render(trend));
}
```

Remove the controller's `MessageSource` field, constructor parameter, `LocaleContextHolder` import, and private `message` method if they have no other callers. Add the three keys shown in Step 1 to both message bundles.

- [x] **Step 4: Replace fixed-band CSS with dual-chart styling**

Keep `.trend-chart-wrap` and `.trend-chart` responsive behavior. Add:

```css
.trend-chart-stack {
    min-width: 640px;
}

.trend-chart + .trend-chart {
    margin-top: 24px;
}

.trend-line-glucose,
.trend-axis-glucose {
    stroke: var(--trend-glucose);
}

.trend-line-ketone,
.trend-axis-ketone {
    stroke: var(--trend-ketone);
}

.trend-axis-label.glucose {
    fill: var(--trend-glucose);
}

.trend-axis-label.ketone {
    fill: var(--trend-ketone);
}

.trend-marker.flare-no {
    fill: var(--trend-flare-no);
}

.trend-marker.flare-suspected {
    fill: var(--trend-flare-suspected);
}

.trend-marker.flare-active {
    fill: var(--trend-flare-active);
}

.trend-point:focus {
    outline: none;
}

.trend-point:focus .trend-marker,
.trend-point:hover .trend-marker {
    stroke: var(--text);
    stroke-width: 2;
}

.trend-tooltip {
    opacity: 0;
    pointer-events: none;
}

.trend-point:focus .trend-tooltip,
.trend-point:hover .trend-tooltip {
    opacity: 1;
}

.trend-tooltip rect {
    fill: var(--panel);
    stroke: var(--border);
}

.trend-tooltip text,
.trend-axis-label,
.trend-tick-label {
    fill: var(--text);
}
```

Define dedicated `--trend-symptom`, `--trend-glucose`, `--trend-ketone`, flare, axis, and grid tokens in `:root`, system dark mode, and explicit `LIGHT`/`DARK` preference blocks. Use them consistently for axes, tick labels, lines, markers, legends, grid lines, and date ticks. Verify at least 4.5:1 contrast where a series color is used for text and at least 3:1 for meaningful lines, markers, and grid strokes. Render ISO date labels at `0.75rem` and select the largest evenly distributed tick set that preserves the first/last date and at least 80 SVG units between adjacent labels.

- [x] **Step 5: Run focused trend, web, and localization tests**

Run:

```bash
./gradlew test --tests 'com.metabion.controller.web.TrendGlucoseConverterTest' --tests 'com.metabion.controller.web.TrendChartModelBuilderTest' --tests 'com.metabion.controller.web.TrendSvgRendererTest' --tests 'com.metabion.controller.web.WebTrendControllerTest' --tests 'com.metabion.service.DailyTrendServiceTest' --tests 'com.metabion.controller.api.SymptomTrackingControllerTest' --tests 'com.metabion.config.LocalizationConfigTest'
```

Expected: `BUILD SUCCESSFUL` with all focused tests passing.

- [x] **Step 6: Run full repository verification**

Run:

```bash
./gradlew test
git diff --check
```

Expected: Gradle reports `BUILD SUCCESSFUL`; `git diff --check` produces no output.

- [ ] **Step 7: Perform browser verification against the reported scenario**

Using the existing signed-in local session, reload `http://localhost:8080/app/trends?from=2026-06-15&to=2026-07-14` and confirm:

- The symptom point `3.00 / No flare` appears in the upper chart as a circle at the correct score.
- Glucose `5.00` and `5.20 mmol/L` have distinct y positions against the left axis.
- Ketones `2.00` and `0.20 mmol/L` have distinct y positions against the right axis.
- No line bridges the unmeasured dates from June 16 to July 9.
- Keyboard focus and pointer hover reveal exact point details.
- Both charts remain aligned at desktop width and horizontally usable at narrow width.

Status: blocked in the isolated in-app browser because it redirected to sign-in and could not reuse the user's authenticated Chrome session. Deterministic renderer, geometry, localization, tooltip-boundary, and contrast tests cover the implementation; an authenticated visual pass remains outstanding.

- [x] **Step 8: Commit localization and web integration**

```bash
git add src/main/java/com/metabion/controller/web/WebTrendController.java src/main/resources/static/css/app.css src/main/resources/messages.properties src/main/resources/messages_cs.properties src/test/java/com/metabion/controller/web/WebTrendControllerTest.java src/test/java/com/metabion/config/LocalizationConfigTest.java
git commit -m "Polish trend chart web presentation"
```

---

## Completion Review

- [x] Confirm `git status --short` contains only intended trend-chart changes plus the user's pre-existing unrelated files.
- [x] Confirm every implementation commit is focused and uses the messages listed above.
- [x] Confirm no dependency, migration, authentication, authorization, or database schema behavior changed.
- [x] Invoke `superpowers:verification-before-completion` before reporting success.
- [x] Invoke `superpowers:requesting-code-review` for a final review of the completed implementation.
