# MVC Login Shell Design

Date: 2026-05-30

## Goal

Replace the current root "Hello World" response with a real server-rendered web entry point that uses the authentication work already implemented in Metabion.

The first UI is intentionally small: a login page, an authenticated landing page at `/app`, and logout. It should establish the long-term web architecture for a patient-facing food diary and lab-result application without implementing those future product features yet.

## Long-Term Direction

Metabion should use Spring MVC and Thymeleaf for the primary browser UI. This fits the expected product shape: form-heavy patient workflows, food diary entries, lab-result uploads, account pages, and future authenticated views.

The existing REST API should remain available. MVC pages and REST controllers should share service-layer logic instead of duplicating authentication, account, diary, or upload behavior. This keeps the option open for a future SPA, mobile clients, or async browser features while allowing the web application to remain a conventional server-rendered Spring Boot application.

## Chosen Approach

Use an MVC wrapper around the existing `SecurityService.login(...)` and `SecurityService.logout(...)`.

The project should add Thymeleaf as the server-rendered template engine. The web layer will call the same authentication service used by the existing JSON endpoints, preserving custom behavior such as timing equalization, generic login failures, lockout/rate-limit handling, session creation, and the MFA extension point.

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

## Security

Session-based authentication remains the web authentication model.

Security configuration should:

- Permit `GET /`, `GET /login`, static assets, and `POST /login`.
- Require authentication for `/app`.
- Keep `/api/**` behavior compatible with the existing REST API.
- Keep CSRF enabled for MVC forms.
- Include Thymeleaf CSRF hidden fields on `POST /login` and `POST /logout`.

The existing public JSON auth endpoints currently excluded from CSRF should stay as they are unless a separate security review changes them.

No passwords, tokens, session IDs, or credentials may be logged or rendered.

## UI Scope

The UI should be restrained and product-like, suitable for an early healthcare/patient tool.

`/login` should be a focused form page with clear labels, generic error handling, and no marketing content.

`/app` should be a minimal authenticated shell. It should not pretend that diary or lab-upload features exist yet. It can contain a simple signed-in state and logout action.

CSS should be simple and local, for example under `src/main/resources/static/css/app.css`.

## Component Boundaries

Expected production additions:

- A web MVC controller for root, login, app, and logout routes.
- A small form object or binding model for login form input if needed.
- Thymeleaf templates under `src/main/resources/templates`.
- Static CSS under `src/main/resources/static`.

The existing `AuthController` REST API should remain a REST controller under `/api/auth`.

The existing `SecurityService` should remain the source of login/logout behavior. If web-specific validation or model preparation is needed, keep that logic in the web layer and do not move view concerns into `SecurityService`.

## Error Handling

Login failures shown to the browser should use one generic user-facing message, such as "Invalid email or password."

Validation errors should not provide account-enumerating information. Empty or malformed input can be handled as a normal form validation failure, but authentication-related failures should remain generic.

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
- Registration UI.
- Email verification UI.
- Forgot-password or reset-password UI.
- MFA verification UI.
- SPA setup or frontend build tooling.
- Mobile-specific authentication.

