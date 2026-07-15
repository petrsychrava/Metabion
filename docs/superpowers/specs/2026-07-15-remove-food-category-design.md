# Remove Food Category Design

## Goal

Remove the unused food-category concept from diet-log meals without preserving database or API compatibility, because the application has not yet been used.

## Scope

Food category is the `FoodCategory` enum and the `foodCategory` property of a diet-log meal. The removal applies to persisted meals, REST request and response DTOs, server-rendered forms and clinical detail views, mapping and validation, localized labels, and tests.

`DietDeviationCategory` is explicitly out of scope and remains unchanged. Meal type, description, notes, ordering, meal-linked photos, and deviations also remain unchanged.

## Design

`DailyDietLogMeal` will retain only meal type, description, notes, and sort order. Its constructor and all callers will omit food category. The nested meal DTOs used by the REST API and form mapping will likewise omit the field. Requests containing `foodCategory` are no longer supported, and responses no longer expose it.

The two patient meal-entry templates will no longer render a food-category select. The clinical diet-log detail view will no longer display a category row. Controllers will stop supplying enum values for the removed select. The matching English and Czech message keys will be removed.

The `FoodCategory` enum source will be deleted. A new Flyway migration will remove the existing food-category check constraint and column from `daily_diet_log_meals`. Historical migration `V9` will remain immutable, keeping Flyway checksum history valid while the next migration establishes the intended schema for fresh and existing databases.

## Validation and Testing

Focused unit, controller, form, photo-service, persistence, and API tests will be updated to create meals without a category and assert the compact request/response/form contracts. The API controller test will verify a meal can be submitted without `foodCategory` and that the response omits it. The full Gradle test suite will verify the broad change.

## Constraints

- Do not retain compatibility shims, optional fields, deprecated overloads, or migration backfills for food category.
- Do not alter `DietDeviationCategory` or deviation behavior.
- Use Flyway as schema owner; add the next available migration version.
- Do not modify unrelated dirty files in the worktree.
