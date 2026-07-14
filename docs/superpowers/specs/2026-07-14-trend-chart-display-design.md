# Trend Chart Display Design

## Purpose

Correct the patient and clinical trend charts so plotted positions represent the recorded values accurately. The redesign must keep symptom and flare information distinct from biochemical measurements, preserve missing-data gaps, and remain readable when glucose records use different stored units.

The existing table remains unchanged and continues to show the stored measurement values, units, and timestamps. This design changes the graphical presentation only, plus the response metadata needed to choose a consistent glucose display unit.

## Current Problem

`TrendSvgRenderer` currently scales symptom scores numerically, but it places every glucose marker at `y=196` and every ketone marker at `y=208`. Consequently, different glucose or ketone values have identical vertical positions. The current symptom polyline also joins available points across days without data, visually implying observations that were never recorded.

## Considered Approaches

### Aligned small multiples

Use one chart each for symptoms, glucose, and ketones on a shared date axis. This gives every metric an independent scale and is straightforward to interpret, but requires more vertical space than necessary for two related measurement series.

### One normalized graph

Normalize every series to a percentage of its own range and draw all metrics together. This is compact, but the y-position no longer represents a real clinical value and can imply false comparability between metrics.

### Event timeline

Show only dates containing data, with exact values grouped into event summaries. This works well for sparse records, but is weaker at revealing longitudinal patterns.

### Selected: two coordinated graphs

Use one graph for symptoms and flare state, then one dual-axis graph for glucose and ketones. This preserves real numeric values while keeping related measurements together and limiting page height.

## Visual Design

### Shared date axis

The graphs are vertically stacked and use the same horizontal date range, plot width, and tick positions. This allows a user to compare symptoms, flare state, glucose, and ketones on the same dates without mixing their numeric scales.

Symptom observations are positioned at the center of their local date. Measurement observations use `measuredAt`, converted to the patient's timezone, so multiple readings on the same date retain their chronological order.

### Symptoms and flare state

The upper graph uses a fixed symptom-score y-axis from 0 to 30. Each symptom point also encodes its flare state through both shape and color:

- `NO_FLARE`: green circle.
- `SUSPECTED_FLARE`: amber triangle.
- `ACTIVE_FLARE`: red square.

The marker's accessible label and tooltip include the local date, exact symptom score, and localized flare-state label. Shape ensures the state remains understandable without relying on color alone.

### Glucose and ketones

The lower graph plots both measurement series against the shared date axis:

- Glucose uses the left y-axis.
- Ketones use the right y-axis.
- Each axis, line, marker, and direct series label uses the corresponding series color.
- Both axes show explicit numeric ticks and units.

The separate axes are visually anchored to their series to mitigate the usual ambiguity of dual-axis charts. Accessible labels and tooltips always include the series name, exact displayed value, unit, and local measurement time.

### Sparse data

Each series is divided into segments. Points connect only when their local dates are the same or consecutive. A missing calendar day breaks the line. This prevents the graph from implying continuous observations across unmeasured periods.

An isolated observation is rendered as a visible point. If a graph or one measurement series has no values, the graph remains present and displays a localized no-data message for the missing content without hiding other available series.

### Responsive behavior

Both SVGs share the same responsive wrapper and minimum plot width. On narrow screens they remain aligned and may scroll horizontally together, following the current page's responsive chart pattern.

## Scaling and Units

### Symptoms

The symptom axis always spans 0 through 30.

### Glucose

All visible glucose observations are converted to the patient's glucose-unit preference before scale calculation and rendering. The preference defaults to mmol/L when absent. Patient and clinical views use the subject patient's preference.

The glucose axis uses rounded bounds around the visible minimum and maximum with a minimum span of:

- 2 mmol/L, or
- 36 mg/dL.

For one value or a narrower observed range, the scale is centered around the data while maintaining that minimum span. Bounds must still accommodate every displayed value. This prevents small variations from being exaggerated while preserving useful detail.

Glucose conversion is centralized and tested in both directions. Tooltip and accessible-label formatting preserves clinically useful precision after conversion.

### Ketones

Ketones remain in mmol/L. Their scale starts at zero and uses a rounded upper bound above the highest visible value. A stable nonzero default upper bound is used for a single zero value or an empty series.

### Unsupported data

Null measurements and unsupported type/unit combinations are omitted from plotted series without failing the page. They remain available in the existing table. Plot generation never substitutes a guessed value or unit.

## Components and Data Flow

1. `DailyTrendService` continues loading and grouping check-ins, diet logs, and measurements for the requested date range.
2. `DailyTrendResponse` identifies the subject patient's preferred glucose display unit and timezone in addition to retaining raw measurement values and units.
3. A focused chart-model builder converts glucose values, orders observations, splits sparse series into segments, calculates scales, and produces plot coordinates and accessible display text.
4. `TrendSvgRenderer` renders the prepared model as two SVG graphs. It owns SVG markup and escaping, not measurement conversion or scaling policy.
5. `WebTrendController` continues placing the rendered chart markup and the unmodified trend response in the patient and clinical view models.

Separating chart-model calculation from SVG markup keeps numeric behavior independently testable and prevents the renderer from accumulating unrelated domain logic.

## Accessibility and Localization

- Each SVG has a localized accessible name and description.
- Every plotted point exposes date/time, series, value, unit, and flare state where applicable.
- Interactive markers are keyboard focusable, and their details appear on hover and focus.
- Flare state uses shape plus color.
- Axis meaning is communicated by visible labels and units, not color alone.
- New user-facing strings are added to both English and Czech message bundles with aligned keys.
- All generated text and attribute values are escaped before insertion into SVG markup.

## Error and Edge-Case Handling

- Null or empty trend responses render a localized no-data chart state.
- One-day ranges and isolated observations render valid coordinates without division by zero.
- Identical measurement values still produce a nonzero scale span.
- Multiple measurements on one day are ordered by local measurement time.
- Missing dates split line segments.
- Measurements at the date-range boundaries remain inside the plot.
- Unsupported measurement units are excluded from plotting and do not prevent valid series from rendering.

## Testing Strategy

### Chart-model unit tests

- Glucose conversion from mmol/L to mg/dL and from mg/dL to mmol/L.
- Preferred-unit defaulting.
- Minimum glucose scale spans and rounded bounds.
- Zero-based ketone scale and rounded upper bound.
- Independent glucose and ketone y-coordinates for different values.
- Local-time x-coordinates for multiple same-day measurements.
- Segment breaks across missing days and connections across consecutive dates.
- Stable behavior for one point, identical values, and empty series.

### Renderer unit tests

- Two coordinated SVG graphs with aligned date coordinates.
- Left glucose and right ketone axes with units and series styling.
- Circle, triangle, and square symptom markers for the three flare states.
- Isolated points and multiple line segments.
- Localized no-data states.
- Escaped labels and accessible point details.

### Web tests

- Patient and clinical pages render both graphs.
- The subject patient's preferred glucose unit reaches the graph model.
- Existing table values and links remain present.
- English and Czech labels remain available.

Run the focused chart and web-controller tests during development, followed by the full Gradle test suite.

## Out of Scope

- Changing measurement validation ranges.
- Adding clinical reference ranges or diagnostic thresholds.
- Altering stored measurement values or historical records.
- Replacing the existing server-rendered page with a client-side chart library.
- Redesigning the trends table or date filter.
