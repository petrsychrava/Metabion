# Dark Mode Design

## Summary

Metabion will support a persisted per-user theme preference for authenticated application pages. The default value is `SYSTEM`, which follows the browser or operating-system color preference. Users can choose `LIGHT`, `DARK`, or `SYSTEM` from the authenticated application shell. The visual direction is a clinical green-black dark palette that keeps the current restrained UI style and improves contrast through theme tokens.

Public authentication pages will use system preference only in this initial implementation. That keeps account-level persistence focused on signed-in users and avoids adding cookies or anonymous preference storage before there is a product need.

## Goals

- Add long-term user theme preference across sessions and devices.
- Preserve automatic dark mode through `prefers-color-scheme` when the user selects `SYSTEM`.
- Keep the change consistent with the current Thymeleaf and shared CSS architecture.
- Avoid changing the authentication model or introducing frontend framework dependencies.
- Keep theme controls available from authenticated pages without disrupting existing navigation.

## Non-Goals

- Anonymous or pre-login persisted theme selection.
- Organization-wide theme policy.
- Per-device theme preferences.
- Reworking page layout or navigation beyond adding a compact theme control.

## Data Model

Add a `ThemePreference` enum under `com.metabion.domain` with values:

- `SYSTEM`
- `LIGHT`
- `DARK`

Add a non-null `theme_preference` column to the users table, defaulting to `SYSTEM`. The `User` entity will expose this as a required enum field. Existing users will receive `SYSTEM` through the migration default.

## Web Flow

Authenticated Thymeleaf pages already share `layout :: appShell`. The shell will render a compact theme selector in the sidebar near the sign-out control. The selector will submit the selected preference to a web endpoint that updates the current user and redirects back to the referring page when safe, or `/app` otherwise.

The shell will expose the current preference to the document root with `data-theme-preference="SYSTEM"`, `data-theme-preference="LIGHT"`, or `data-theme-preference="DARK"`. That attribute is enough for CSS and a tiny script to apply the right theme.

Public pages that use `layout :: head` will not know an account preference. They will still support dark mode through CSS `prefers-color-scheme`.

## CSS and Client Behavior

The shared stylesheet will define light theme tokens as the default. A `prefers-color-scheme: dark` media query will provide the clinical green-black tokens for system dark mode. Explicit `LIGHT` and `DARK` selections will override system behavior through document attributes.

Current hardcoded colors for secondary buttons, sidebar backgrounds, active navigation, and shadows will become CSS variables so both themes stay coherent.

A small script in the shared layout will apply the selected theme early enough to avoid an obvious flash on authenticated pages. The script will not persist state in localStorage because the backend is the source of truth for signed-in preferences.

## Service and Controller Boundaries

Theme preference updates will live behind a small service method instead of being handled directly in the controller. The service will:

- Resolve the current authenticated user.
- Validate and store the requested `ThemePreference`.
- Keep the update isolated from security-sensitive authentication flows.

The controller will expose `POST /app/preferences/theme` as a CSRF-protected endpoint and return a redirect.

## Error Handling

Invalid enum values will be rejected as a bad request. Because the selector will submit known enum values, this is mainly defensive behavior.

If the current user cannot be resolved, the normal security flow will require authentication before the endpoint is reached.

Redirect targets must be constrained to application-local paths to avoid open redirects.

## Testing

Add focused tests for:

- Flyway/JPA persistence of the default `SYSTEM` theme preference.
- Service update behavior for the authenticated user.
- Web endpoint requiring CSRF and authentication.
- Web endpoint persisting the selected preference and redirecting safely.
- Template rendering of the selector and current preference.

Run `./gradlew test` after implementation.
