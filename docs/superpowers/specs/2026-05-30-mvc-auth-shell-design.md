# MVC Auth Shell Design

Date: 2026-05-30

## Goal

Replace the current root "Hello World" response with a real server-rendered web entry point that uses the authentication work already implemented in Metabion.

The first UI is intentionally focused on authentication: registration, email verification, login, logout, forgot password, reset password, and an authenticated landing page at `/app`. It should establish the long-term web architecture for a patient-facing food diary and lab-result application without implementing those future product features yet.

## Long-Term Direction

Metabion should use Spring MVC and Thymeleaf for the primary browser UI. This fits the expected product shape: form-heavy patient workflows, food diary entries, lab-result uploads, account pages, and future authenticated views.

The existing REST API should remain available. MVC pages and REST controllers should share service-layer logic instead of duplicating authentication, account, diary, or upload behavior. This keeps the option open for a future SPA, mobile clients, or async browser features while allowing the web application to remain a conventional server-rendered Spring Boot application.

## Chosen Approach

Use MVC wrappers around the existing auth services.

The project should add Thymeleaf as the server-rendered template engine. The web layer will call the same services used by the existing JSON endpoints:

- `UserService.register(...)`
- `UserService.verify(...)`
- `UserService.requestPasswordReset(...)`
- `UserService.resetPassword(...)`
- `SecurityService.login(...)`
- `SecurityService.logout(...)`

This preserves custom behavior such as timing equalization, generic login failures, lockout/rate-limit handling, token hashing, session creation, session invalidation after password reset, and the MFA extension point.

Alternatives considered:

- Spring Security native `formLogin`: idiomatic for MVC, but it risks bypassing or duplicating the existing `SecurityService.login(...)` behavior.
- Thymeleaf page backed by JavaScript calls to `/api/auth/login`: keeps a single API endpoint, but unnecessarily mixes MVC with client-side login handling for a non-SPA application.
- Static HTML/CSS/JS only: lightweight, but a weaker long-term fit for a non-SPA, form-heavy Spring application.

## Routes

### `GET /`

Root should become an application entry route, not a text response.

- Anonymous users redirect to `/login`.
- Authenticated users redirect to `/app`.

### `GET /login`

Renders the login form for anonymous users.

- Fields: email and password.
- If already authenticated, redirect to `/app`.
- It should render a generic login failure message when the model contains a failure flag.

### `POST /login`

Handles the MVC login form.

- Reads form fields `email` and `password`.
- Creates a `LoginRequest`.
- Calls `SecurityService.login(request, httpRequest, httpResponse)`.
- On `AUTHENTICATED`, redirects to `/app`.
- On `MFA_REQUIRED`, renders a generic message that additional verification is not available in this web interface yet. The current default MFA service is no-op, so this is defensive behavior only.
- On authentication, rate-limit, validation, locked, disabled, or unknown-user failure, re-renders `/login` with a generic error.

The web login must not reveal whether the email exists, the account is unverified, the account is locked, or the request was rate-limited.

### `GET /register`

Renders a registration form for anonymous users.

- Fields should match the existing `RegisterRequest` contract.
- If already authenticated, redirect to `/app`.
- Uses the shared auth page layout.

### `POST /register`

Handles the MVC registration form.

- Creates a `RegisterRequest`.
- Calls `UserService.register(request)`.
- Shows a generic confirmation page or message telling the user to check email for verification.
- Does not reveal whether an email already exists beyond the behavior already enforced by `UserService`.

### `GET /verify?token=...`

Handles email verification links clicked by users.

- Calls `UserService.verify(token)`.
- On success, renders a human-readable confirmation and links to `/login`.
- On invalid, expired, or consumed token, renders a human-readable failure page.
- Does not authenticate the user automatically.

Verification emails should point to this MVC route, not to `/api/auth/verify`.

### `GET /forgot-password`

Renders a forgot-password form for anonymous users.

- Field: email.
- If already authenticated, redirect to `/app`.

### `POST /forgot-password`

Handles the MVC forgot-password form.

- Creates a `ForgotPasswordRequest`.
- Calls `UserService.requestPasswordReset(request)`.
- Always shows a generic confirmation, such as "If an account exists, reset instructions have been sent."
- Does not reveal whether an account exists or whether a rate limit was hit.

### `GET /reset-password?token=...`

Renders the reset-password form from an email link.

- Keeps the token in a hidden form field.
- Collects the new password.
- Does not validate or consume the token on GET unless the implementation can do so without changing token semantics.

### `POST /reset-password`

Handles the MVC password reset form.

- Creates a `ResetPasswordRequest`.
- Calls `UserService.resetPassword(request)`.
- On success, redirects or renders a success page linking to `/login`.
- On invalid or expired token, renders a generic invalid-link message.
- On invalid password, re-renders the form with validation feedback that does not expose account information.

Password reset emails already point to `/reset-password?token=...`; this should remain the browser-facing route.

### `GET /app`

Renders the initial authenticated application shell.

- Requires authentication.
- Displays the signed-in email.
- Displays roles if readily available from the current principal.
- Provides a logout form.
- Does not implement food diary, lab-result uploads, profile pages, registration, verification, or password reset.

The `/app` route is reserved as the future patient application area.

### `POST /logout`

Handles web logout.

- Requires authentication.
- Calls `SecurityService.logout(httpRequest, httpResponse)`.
- Redirects to `/login`.

The existing REST `POST /api/auth/logout` endpoint remains available for API clients.

## Email Links

Email links should land on MVC pages because they are clicked by users in a browser.

`SmtpEmailService` should build:

- Verification links as `${app.base-url}/verify?token=...`.
- Password reset links as `${app.base-url}/reset-password?token=...`.

`LoggingEmailService` should log the same MVC route shapes in the dev profile with token values redacted. It must not write live verification or reset tokens to logs.

The existing REST endpoints remain available for API compatibility and tests:

- `GET /api/auth/verify?token=...`
- `POST /api/auth/register`
- `POST /api/auth/login`
- `POST /api/auth/logout`
- `POST /api/auth/forgot-password`
- `POST /api/auth/reset-password`

## Security

Session-based authentication remains the web authentication model.

Security configuration should:

- Permit `GET /`, `GET /login`, static assets, and `POST /login`.
- Permit anonymous access to `GET /register`, `POST /register`, `GET /verify`, `GET /forgot-password`, `POST /forgot-password`, `GET /reset-password`, and `POST /reset-password`.
- Require authentication for `/app` and `POST /logout`.
- Keep `/api/**` behavior compatible with the existing REST API.
- Keep CSRF enabled for MVC forms.
- Include Thymeleaf CSRF hidden fields on MVC form posts.

The existing public JSON auth endpoints currently excluded from CSRF should stay as they are unless a separate security review changes them. MVC form posts should use CSRF tokens rather than being added to the API CSRF ignore list.

No passwords, tokens, session IDs, or credentials may be logged or rendered.

## UI Scope

The UI should be restrained and product-like, suitable for an early healthcare/patient tool.

The auth pages should share a small common layout with clear labels, generic error handling, and no marketing content.

`/app` should be a minimal authenticated shell. It should not pretend that diary or lab-upload features exist yet. It can contain a simple signed-in state and logout action.

CSS should be simple and local, for example under `src/main/resources/static/css/app.css`.

## Component Boundaries

Expected production additions:

- A web MVC controller for root, auth pages, app, and logout routes.
- Small form objects or binding models for MVC form input if needed.
- Thymeleaf templates under `src/main/resources/templates`.
- Static CSS under `src/main/resources/static`.

The existing `AuthController` REST API should remain a REST controller under `/api/auth`.

The existing `UserService` and `SecurityService` should remain the sources of auth behavior. If web-specific validation or model preparation is needed, keep that logic in the web layer and do not move view concerns into service classes.

## Error Handling

Login failures shown to the browser should use one generic user-facing message, such as "Invalid email or password."

Validation errors should not provide account-enumerating information. Empty or malformed input can be handled as a normal form validation failure, but authentication-related failures should remain generic.

Registration and forgot-password completion pages should be generic enough to avoid account enumeration.

Verification and reset-password token failures can tell the user the link is invalid or expired, but should not disclose account details.

The MFA-required path should not be treated as success until a real MFA web flow exists. Since `NoOpMfaChallengeService` currently returns no MFA requirement, this branch should be covered defensively without expanding scope.

## Testing

Add focused MVC/security tests for the new web layer.

Tests should cover:

- Anonymous `GET /` redirects to `/login`.
- Authenticated `GET /` redirects to `/app`.
- Anonymous `GET /login` renders successfully.
- Authenticated `GET /login` redirects to `/app`.
- Anonymous `GET /app` is rejected or redirected according to security configuration.
- Successful `POST /login` uses the same login service path and redirects to `/app`.
- Failed `POST /login` re-renders the login template with a generic error.
- `GET /register` renders successfully for anonymous users.
- Successful `POST /register` uses `UserService.register(...)` and renders a check-email result.
- `GET /verify` uses `UserService.verify(...)` and renders success/failure pages.
- `GET /forgot-password` renders successfully for anonymous users.
- `POST /forgot-password` uses `UserService.requestPasswordReset(...)` and renders a generic result.
- `GET /reset-password` renders a token-carrying form.
- `POST /reset-password` uses `UserService.resetPassword(...)` and renders or redirects to a success result.
- SMTP verification links point to `/verify?token=...`.
- SMTP password reset links continue to point to `/reset-password?token=...`.
- Dev logging email messages mention the same MVC routes as SMTP links, with token values redacted.
- `POST /logout` invokes the logout path and redirects to `/login`.
- Templates render without missing model attributes.
- Existing REST auth tests continue to pass.

The implementation should be verified with:

```bash
./gradlew test
```

## Out Of Scope

- Food diary features.
- Lab-result upload or viewing.
- MFA verification UI.
- SPA setup or frontend build tooling.
- Mobile-specific authentication.
