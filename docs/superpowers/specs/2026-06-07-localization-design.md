# Localization Design

## Summary

Metabion will support English and Czech localization for human-facing server-rendered text, emails, and a user-selectable language preference. English is the default and fallback language. The first phase does not localize API validation/error text and does not introduce content-library database tables.

This design establishes locale codes, fallback behavior, and preference handling that future educational content can reuse without committing to a premature content-library schema.

## Goals

- Support English (`en`) and Czech (`cs`) from the beginning.
- Use English as the default locale and fallback for missing Czech text.
- Add a visible language switcher for both anonymous and authenticated web users.
- Persist authenticated users' language preference.
- Remember anonymous users' language choice with a cookie-backed locale resolver.
- Localize current web UI text and app-generated email subjects and bodies.
- Preserve existing API error-code contracts.

## Non-Goals

- Do not localize API validation or error response messages in this phase.
- Do not translate stable API codes such as `invalid_credentials` or `validation_failed`.
- Do not add database tables for the future educational content library.
- Do not introduce a translation workflow, editorial publishing workflow, or content review model.
- Do not change authentication, CSRF, token handling, login failure semantics, or rate limiting behavior.

## Supported Locales

The application supports these locales:

- `en`: English
- `cs`: Czech

English is the default locale. If a request asks for an unsupported locale, the application rejects explicit switch requests with `400 Bad Request` and falls back to English for ordinary locale resolution.

Runtime message lookup uses English fallback when a Czech key is absent. Tests should catch missing required translations before release.

## Localization Configuration

Add a `LocalizationConfig` that defines:

- A Spring `MessageSource` with application message bundles.
- `Locale.ENGLISH` as the default locale.
- Czech as an additional supported locale.
- A `CookieLocaleResolver` for anonymous and request-level locale storage.

Language changes should go through the web preference controller rather than relying on a free-form query parameter. That controller validates the requested language, updates the locale cookie, and persists the authenticated user's preference when a user is signed in.

## Locale Resolution

Locale resolution order:

1. Explicit language switch request from the web UI.
2. Authenticated user's stored `language_preference`.
3. Anonymous locale cookie.
4. Browser `Accept-Language`.
5. English fallback.

The authenticated user's stored preference wins after login. If an anonymous user selects Czech and then signs in to an account configured for English, the account preference takes effect. New users default to English until they change the language.

## User Preference Model

Add a `LanguagePreference` enum with values:

- `EN`
- `CS`

The enum converts to and from Java `Locale` instances and rejects unsupported values. The `users` table gets a new non-null `language_preference` column with default `EN`, added through Flyway migration `V8__user_language_preference.sql`.

`User` gets a `languagePreference` field matching the existing `themePreference` pattern. `UserPreferenceService` gains:

- `currentLanguagePreference(Authentication authentication)`
- `updateLanguagePreference(Authentication authentication, LanguagePreference preference)`

The service validates null and unsupported preferences, then persists the authenticated user's selection.

## Web UI

Add a language selector near the existing theme selector in `layout.html`. Authenticated web users submit it through the existing user preference controller pattern and are redirected back to the current app page using the same safe redirect rules as theme preference updates.

Anonymous pages also need a language selector, including:

- Login
- Registration
- Email verification result page
- Forgot password
- Reset password
- Staff invitation acceptance

Anonymous language changes update the locale cookie and redirect back to the current page. They do not require authentication.

Templates should use Thymeleaf message expressions such as `#{message.key}` for static labels. Controllers should avoid hard-coded English result titles, messages, and actions by passing message keys or resolving messages with `MessageSource`.

The HTML `lang` attribute should reflect the resolved request locale.

## Email Localization

Email subjects and bodies move from hard-coded English strings to message bundles. Link and token generation stays unchanged.

Emails covered in this phase:

- Account verification
- Password reset
- Staff invitation

Where the recipient user is known, email language should use that user's `language_preference`. Where only a request locale is available, use the resolved request locale. Where neither is available, use English.

Email tests should verify English and Czech subjects and body text while continuing to avoid logging or exposing tokens.

## API Behavior

API response contracts remain stable. Existing machine-readable fields such as `error` and `fields` stay unchanged.

The first phase does not add localized API validation text. This keeps the API simple and avoids spending effort on messages that current clients do not need. A future frontend can still display localized text by mapping stable API codes client-side or by extending the API later with an additional `message` field without changing existing codes.

## Future Content Library Compatibility

The future educational content library should reuse the same locale codes and fallback rule:

- Store shared content identity separately from localized title, summary, and body text.
- Prefer the user's selected language when a published translation exists.
- Fall back to English when Czech content is missing, unpublished, or otherwise unavailable.

No content-library schema is introduced in this phase.

## Error Handling

- Invalid explicit language preference submissions return `400 Bad Request`.
- Unsupported ordinary request locales fall back to English.
- Missing message keys should be covered by tests for required web and email surfaces.
- Authenticated preference updates require an authenticated user.
- Anonymous language updates must only affect the anonymous locale cookie.
- Safe redirect handling must prevent open redirects.

## Testing

Add focused tests for:

- `LanguagePreference` parsing and locale conversion.
- Default English locale and Czech locale resolution.
- Fallback to English when the requested locale is unsupported.
- Anonymous language switch sets the locale cookie and safely redirects.
- Authenticated language switch persists `users.language_preference`.
- Invalid language switch request returns `400 Bad Request`.
- Web templates render with message bundles.
- Email service produces English and Czech subjects/bodies.
- Existing API error codes remain unchanged.

Run `./gradlew test` after implementation.
