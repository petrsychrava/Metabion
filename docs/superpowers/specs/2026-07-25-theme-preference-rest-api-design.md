# Theme Preference REST API Design

## Summary

Expose the existing authenticated user's persisted theme preference through the account REST API. The API will match the established language-preference endpoint and supports the existing `SYSTEM`, `LIGHT`, and `DARK` enum values.

## Scope

- Add `GET /api/account/preferences/theme`, returning the current value as `{ "theme": "SYSTEM" }`.
- Add `PUT /api/account/preferences/theme`, accepting `{ "theme": "LIGHT" | "DARK" | "SYSTEM" }` and returning `{ "status": "ok" }` after persisting it.
- Add a validated request DTO at the API boundary.
- Add focused MVC tests for retrieval, update, authentication, CSRF, missing input, and unsupported enum values.

## Existing Boundaries

`ThemePreference` is already persisted on `User`, defaults to `SYSTEM`, and is handled by `UserPreferenceService`. The API controller will delegate to `currentThemePreference` and `updateThemePreference`; it will not duplicate validation or persistence behavior.

No Flyway migration, entity change, service change, web-template change, or client-side change is needed.

## Endpoint Behavior

Both endpoints use the existing session-authenticated API security. The update endpoint remains CSRF-protected. Jackson enum conversion rejects unsupported values with a bad-request response, while Bean Validation rejects a missing `theme` property.

## Testing

Extend `AccountControllerTest` with the same behaviors covered for language preference, substituting the theme paths and enum values. Run the focused test class during development, then the full Gradle test suite before completion.
