# Diet Log Meal Card Layout Design

## Context

The current patient diet log web form is a long form with separate repeated sections for meals, deviations, photos, and measurements. That separation makes it hard for a patient or reviewer to understand which deviation or photo belongs to which meal. The form also renders three empty rows for each repeated section, including measurements, which makes the default experience heavier than necessary.

The target experience is a meal-centered daily journal. The date should default to the current patient date, each meal should own its related deviation and photos, and measurements should be simplified to one glucose entry and one ketone entry.

## Goals

- Preselect the diet log date to the current date when the patient opens the form without a date query parameter.
- Replace the fixed three-row meal form with dynamic meal cards.
- Show two empty meal cards by default for a new log.
- Allow patients to add and remove meal cards in the web form.
- Place deviation fields inside the meal card they belong to.
- Place photo upload and attached photo caption fields inside the meal card they belong to.
- Simplify measurements to one glucose value and one ketone value.
- Remove measurement unit selectors from the diet log form.
- Use the patient profile glucose unit for glucose measurements.
- Use `mmol/L` for ketone measurements.
- Use a time-only measurement field in the web form and combine it with the diet log date server-side.
- Show clinical diet log detail with meals grouped together with their deviations and photos.

## Non-Goals

- Do not redesign the whole application shell or sidebar.
- Do not move glucose unit preference out of the account page; it already belongs there.
- Do not add arbitrary repeatable measurement rows to the web form.
- Do not make measurements meal-specific in this design.

## Recommended Layout

Use the meal-card layout selected during brainstorming.

Desktop order:

1. Date
2. Measurements
3. Daily summary
4. Meal cards
5. Save action

Mobile order:

1. Date
2. Measurements
3. Daily summary
4. Meal 1 card
5. Meal 2 card
6. Add meal
7. Save action

The desktop page can use a two-column layout when space allows: meal cards in the main column, measurements and summary in the side column. On smaller screens, all sections stack into a single column. The mobile form should avoid accordion-only navigation for the primary meal fields so users can scan the day without opening every row. Optional meal subsections may remain compact until used.

## Meal Cards

A new empty log shows two meal cards. Each card contains:

- Meal type
- Food category
- Food description
- Meal notes
- Optional deviation
- Meal photos

Meal type and food category are required only when the meal card has any meal, deviation, or photo content. Fully empty meal cards are ignored on submit.

Patients can add another meal card with an Add meal control. Remove controls should be available for extra cards and for empty default cards. The form should keep at least one visible meal card after removal so the page never loses the primary meal entry point.

## Meal Deviations

Deviation entry moves inside each meal card. The default state is no deviation. If the patient chooses to add a deviation, the card shows:

- Deviation category
- Severity
- Deviation notes

Deviation category and severity are required when a deviation is present. Deviation notes remain optional.

The persisted model should add `meal_id` to `daily_diet_log_deviations`. Use a composite foreign key like the existing photo-to-meal relationship so a deviation can only reference a meal from the same daily diet log. There are no existing deviation rows to preserve, so the implementation does not need legacy unassigned-deviation behavior. New persisted deviations should always be associated with the meal card that produced them.

The fallback of inferring meal association from list order or metadata is rejected. It would be brittle when meals are removed, reordered, partially filled, edited by API clients, or displayed later in clinical review.

## Meal Photos

Photo upload moves inside each meal card. Uploaded photo references should be submitted as part of the meal card that owns them. The database already supports `daily_diet_log_photo_references.meal_id`; the web form and service mapping should populate it for new meal-scoped photo attachments.

Photo captions remain optional. A meal can have zero or more photos.

## Measurements

The web form shows exactly two measurement rows:

- Glucose
- Ketones

No measurement type selector appears in these rows. No unit selector appears in these rows.

Glucose uses the current patient profile glucose unit. Ketones always use `mmol/L`. The page may display static unit text beside each value field so patients understand what they are entering.

The web form should use a time-only input for each measurement. On submit, the server combines:

- Diet log date
- Measurement time
- Patient timezone

into the existing `Instant measuredAt` value expected by measurement persistence and validation. Measurement date is not separately selectable per measurement.

Empty glucose and ketone rows are ignored. If a measurement value is entered, the form must provide enough data to pass the existing measurement validation, including measured time and context if those remain required by the request model.

## Daily Summary

Adherence, appetite, and overall notes remain daily-level fields. They are not tied to individual meals.

## Data And Mapping

The web form should become meal-centric even if persistence remains normalized.

Conceptual web form shape:

```text
logDate
adherenceLevel
appetiteLevel
notes
glucoseMeasurement
ketoneMeasurement
meals[]
  meal fields
  optional deviation fields
  photo references[]
```

Service mapping should convert this shape into:

- `DailyDietLog`
- `DailyDietLogMeal`
- `DailyDietLogDeviation` linked to `DailyDietLogMeal`
- `DailyDietLogPhotoReference` linked to `DailyDietLogMeal`
- `DailyMeasurementEntry` rows linked to the daily diet log

The public API can keep its existing normalized request shape if it can still represent meal-linked deviations and photos explicitly. The immediate UX redesign is for the server-rendered web form, but the implementation must not rely on implicit ordering to recover meal relationships.

## Clinical Review

Clinical diet log detail should group data by meal:

```text
Meal
  meal fields
  deviations for this meal
  photos for this meal
```

Measurements remain a separate daily block because this design does not make measurements meal-specific.

## Validation And Error Handling

- A future log date remains invalid.
- Empty meal cards are ignored.
- Partially completed meal cards must satisfy meal requirements.
- Added deviations must include category and severity.
- Photo upload failures should stay local to the meal card when possible and should not silently attach photos to the wrong meal.
- Measurement values must satisfy the existing type and unit range checks.
- Measurement time parsing errors should point to the glucose or ketone row that caused the error.
- If JavaScript is unavailable, the form should still render the default two meal cards and allow saving them; dynamic add/remove can be progressive enhancement.

## Testing

Add or update tests for:

- New empty patient form defaults to today's date and two meal cards.
- Existing logs render their persisted meal cards.
- Dynamic meal indexes submit correctly after add and remove operations.
- Meal-linked deviations persist with `meal_id`.
- Meal-linked photos persist with `meal_id`.
- Glucose measurement uses the patient profile unit.
- Ketone measurement uses `mmol/L`.
- Time-only measurement input combines with diet log date and patient timezone.
- Empty glucose and ketone rows are ignored.
- Clinical detail groups deviations and photos under the related meal.

## Open Implementation Notes

The design intentionally leaves exact Java class names and DTO refactoring choices to the implementation plan. The implementation should prefer the existing controller, DTO, service, and Thymeleaf patterns, and should keep the schema migration focused on meal-linked deviations.
