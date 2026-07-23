# Repository Guidelines

## Project Overview

Metabion is a **Spring Boot 4.0.6** application built with **Gradle** and **Java 25**. It provides patient and staff REST APIs, a server-rendered Thymeleaf application, and a Spring AI MCP server.

The implemented product areas are:

- Patient registration, email verification, login/logout, password recovery, and session management.
- Role-based access for patients, nutrition specialists, physicians, coordinators, and administrators.
- Staff invitations, patient onboarding submissions, clinical review, cohorts, and staff/patient assignments, exposed through the Thymeleaf workspace and a session-authenticated REST API (`/api/cohorts`, `/api/patients`).
- Daily diet logs, meals, deviations, glucose/ketone measurements, photo upload/storage, and clinical views.
- Symptom questionnaires, daily check-ins, scoring, patient/clinical trends, and SVG trend rendering.
- Versioned and localized education content with authoring, review, approval, publishing, and patient completion tracking.
- Theme, language, and glucose-unit preferences.
- Scoped patient access tokens and a patient-facing MCP tool server.
- A custom OAuth 2.0 authorization-code flow with PKCE, dynamic client registration, client metadata discovery, rotating refresh tokens, and token-family reuse revocation for MCP clients.
- An MFA extension point through `MfaChallengeService`; the default implementation remains a no-op.

Authentication for the web application and ordinary REST APIs is session-based, not JWT-based. Bearer tokens are limited to patient/MCP access and the custom OAuth flow.

## Project Structure

```text
Metabion/
├── build.gradle
├── settings.gradle
├── AGENTS.md
├── plans/                         # Historical auth plans and current auth architecture notes
├── src/
│   ├── main/
│   │   ├── java/com/metabion/
│   │   │   ├── Main.java
│   │   │   ├── config/            # Security, locale, rate limits, MCP/bearer auth, OAuth properties
│   │   │   ├── controller/
│   │   │   │   ├── api/           # REST, OAuth, and metadata endpoints
│   │   │   │   └── web/           # Thymeleaf MVC controllers and view helpers
│   │   │   ├── domain/            # JPA entities and domain enums
│   │   │   ├── dto/               # Shared request/response records
│   │   │   │   ├── mcp/
│   │   │   │   └── oauth/
│   │   │   ├── exception/
│   │   │   ├── mcp/               # Spring AI MCP tools
│   │   │   ├── repository/        # Spring Data repositories
│   │   │   └── service/
│   │   │       └── oauth/         # OAuth authorization, clients, PKCE, and refresh tokens
│   │   └── resources/
│   │       ├── application*.properties
│   │       ├── messages*.properties
│   │       ├── db/migration/       # Flyway migrations
│   │       ├── static/             # CSS and other browser assets
│   │       └── templates/          # Thymeleaf templates
│   └── test/
│       └── java/com/metabion/      # Unit, slice, repository, and integration tests
└── build/                          # Generated output, git-ignored
```

Keep production code under `src/main/java/com/metabion/`. Tests belong under `src/test/java/com/metabion/` and should mirror production packages where practical.

## Build, Test, and Development Commands

Use the Gradle wrapper rather than a system Gradle installation.

| Command | Description |
|---|---|
| `./gradlew test` | Run the full JUnit suite and finalize with the Jacoco report. |
| `./gradlew test --tests 'com.metabion.package.ClassName'` | Run a focused test class. |
| `./gradlew build` | Compile, test, run Jacoco, and package the application. |
| `./gradlew bootRun` | Start the application with the `dev` profile, as configured in `build.gradle`. |
| `./gradlew clean` | Remove generated build output. |

Jacoco HTML output is generated under `build/reports/jacoco/test/html/`. Local startup expects PostgreSQL and uses environment-driven database and mail settings; the default datasource URL is `jdbc:postgresql://localhost:5432/metabion`.

The root `package.json` does not define a frontend build or usable test workflow. Do not substitute `npm test` for Gradle verification.

## Technology Stack

- Java 25 toolchain and Gradle wrapper.
- Spring Boot 4.0.6 with Spring Web MVC, Thymeleaf, Validation, Security, Data JPA, Session JDBC, Mail, and Flyway.
- Spring AI 2.0.0 MCP server over streamable HTTP at `/api/mcp`.
- PostgreSQL in application profiles; H2 and Testcontainers PostgreSQL in tests.
- Hibernate schema validation with Flyway-owned migrations.
- BCrypt password hashing with cost 12 and Bucket4j authentication rate limiting.
- Local filesystem-backed diet photo storage through `FileStorageService`.
- GreenMail for mail tests, Spring Security Test, and JUnit Platform.

Do not add dependencies without checking `build.gradle` and confirming that the existing stack cannot meet the requirement.

## Architecture and Domain Conventions

The codebase follows a layered structure:

- `controller/api`: REST boundaries, OAuth endpoints, and request validation.
- `controller/web`: server-rendered flows and view-model assembly.
- `mcp`: tool definitions that delegate to the same patient application services used by HTTP flows.
- `service`: business rules, access checks, orchestration, file/email boundaries, and token handling.
- `service/oauth`: OAuth authorization, client resolution/registration, PKCE, refresh rotation, and family revocation.
- `repository`: Spring Data persistence access.
- `domain`: JPA mappings, lifecycle methods, and value enums.
- `dto`: Java records for API, form, MCP, and OAuth boundaries.
- `config`: Spring Security, localization, time, rate limiting, MCP restrictions, and bearer authentication.

Reuse shared business services instead of implementing parallel rules in controllers or MCP tools. `PatientAppFacade` is the MCP-facing application boundary for patient features. Centralize patient/staff visibility rules in `AccessControlService` and the relevant domain service.

Important invariants include:

- Patients may access their own data; clinical staff access is constrained by active cohort or expert assignments; administrators have broader review access.
- Roles are represented by `RoleName`: `PATIENT`, `NUTRITION_SPECIALIST`, `PHYSICIAN`, `COORDINATOR`, and `ADMIN`.
- Diet logs are patient/date based and own their meals, deviations, photo references, and measurements.
- Education content is versioned and localized; lifecycle transitions must preserve author/reviewer/publisher authorization rules.
- Symptom check-ins retain questionnaire-version context so historical scoring remains reproducible.
- Patient bearer tokens are resource-bound, expiry/revocation checked, and scope-authorized per operation.
- OAuth authorization codes require PKCE. Refresh tokens rotate, belong to a token family, and reuse revokes the family and linked access tokens.

## Coding Style and Naming

- Follow standard Java conventions and 4-space indentation.
- Use `PascalCase` for classes, `camelCase` for methods and variables, and `UPPER_SNAKE_CASE` for constants.
- Keep application packages under `com.metabion`.
- Prefer constructor injection for Spring beans.
- Prefer Java records for request/response DTOs, with Jakarta Bean Validation at untrusted boundaries.
- Keep controllers thin and transaction boundaries in services or repositories as appropriate.
- Keep comments sparse and useful; do not narrate obvious code.
- Follow the existing exception mapping: API errors belong in `GlobalExceptionHandler`, while MVC errors belong in `WebExceptionHandler`.

## Persistence, Files, and Configuration

- Flyway migrations live in `src/main/resources/db/migration/` and use `V{n}__{slug}.sql`. The current sequence runs through `V17__oauth_client_capabilities.sql`; always choose the next unused version from live repository state.
- Treat Flyway as the schema owner. Production-like profiles use `spring.jpa.hibernate.ddl-auto=validate`; do not rely on Hibernate schema creation.
- Spring Session tables are also Flyway-managed, and automatic session schema initialization is disabled.
- Put shared settings in `application.properties` and environment-specific overrides in profile files such as `application-dev.properties` and `application-prod.properties`.
- Put user-facing text in `messages.properties` and `messages_cs.properties`; keep keys aligned across bundles.
- Diet photo bytes are accessed through `FileStorageService`; database rows store metadata and storage keys, not file contents.
- Local storage defaults below `./var/metabion-storage`. Treat `var/` as runtime data, not source, and do not commit uploaded files.
- Use environment variables or profiles for environment-specific values. Never commit secrets, credentials, plaintext tokens, passwords, session identifiers, or uploaded patient data.

## Security Guidelines

Security-sensitive changes require focused tests and careful review.

- Preserve session-based authentication for the web application and regular REST API unless explicitly asked to change it.
- Keep CSRF enabled. Existing exclusions are deliberately narrow for public auth/OAuth endpoints and bearer-authenticated MCP requests; do not broaden them casually.
- Treat `GET /api/csrf` as cross-cutting infrastructure for authenticated, same-origin REST clients. Keep the bootstrap endpoint role-neutral unless the application-wide security model explicitly changes.
- Keep login, registration, and password-recovery responses generic where required to prevent account enumeration.
- Maintain dummy-BCrypt timing equalization for unknown users and the existing lockout/rate-limit behavior.
- Verification, reset, patient access, authorization-code, and refresh-token credentials must be generated securely and stored only in hashed form where the flow permits.
- Never log passwords, token values, authorization codes, session IDs, credentials, or patient-upload contents. `PatientAccessAuditService` records tool metadata, not bearer token values.
- Preserve OAuth redirect URI validation, PKCE verification, client grant-type constraints, refresh-token rotation, and family-wide reuse revocation.
- Preserve MCP scope checks and resource binding. The MCP endpoint is localhost-restricted by default through `metabion.mcp.allowed-localhost-only`; changes to exposure require an explicit security review.
- Preserve secure session-cookie behavior (`HttpOnly`, `SameSite=Strict`, and `Secure` in production) and session-fixation protection.
- Be especially cautious in `SecurityConfig`, bearer filters/security-context persistence, `SecurityService`, `UserService`, `PatientAccessTokenService`, and `service/oauth`.

## Testing Guidelines

- Run `./gradlew test` after code changes whenever feasible. Use `./gradlew build` when packaging or build lifecycle behavior also changed.
- Use focused tests during iteration, then run the full relevant suite before completion.
- Use `@DataJpaTest` for repository mappings and constraints, MVC/security tests for endpoint policy, and `@SpringBootTest` for cross-layer flows.
- Use H2 only for database-portable behavior. Use Testcontainers PostgreSQL for PostgreSQL constraints, locking, concurrency, and token-rotation semantics.
- Use GreenMail or the logging mail implementation for email flows.
- Add or update tests for authentication, authorization, scopes, token hashing/rotation/revocation, persistence mappings, validation, file ownership, content lifecycle transitions, and clinical visibility.
- Relevant integration coverage lives under `src/test/java/com/metabion/integration/`; OAuth concurrency/reuse integration tests also live under `service/oauth/` and PostgreSQL-specific repository tests under `repository/`.

## Working Conventions for Agents

- Read the relevant production code, tests, configuration, and latest migration before proposing or making changes.
- Prefer live repository state over plans or memory. The numbered `plans/01-08` documents describe the original authentication implementation and are historical context, not a complete description of the current product.
- For patient bearer/OAuth work, also consult `plans/PATIENT_AUTH_ARCHITECTURE.md`, then verify every assumption against current code and tests.
- Respect existing user changes in the worktree; do not revert or reformat unrelated files.
- Prefer small, focused edits over broad refactors.
- Keep responses concise and include the exact verification command and result when code changed.
- If tests cannot run, state why and identify the residual risk.

## Tooling Priority for Agents

Use IntelliJ IDEA MCP tools first for repository work whenever they are available and functioning.

Prefer IDEA MCP for:

- Reading and searching project files or symbols.
- Editing or creating project files.
- Inspecting compiler diagnostics and IDE analysis.
- Compiling or validating changed files when IDEA exposes a suitable action.

Shell commands remain appropriate for:

- Git operations such as `git status`, `git diff`, `git add`, `git commit`, and `git log`.
- Exact Gradle test, build, or run commands.
- Environment, process, filesystem-size, and dependency-cache checks.
- Commands explicitly requested by the user.

If an IDEA MCP action fails, retry once or use the closest IDEA alternative before falling back to shell. State the fallback when shell is used for a task IDEA would normally cover.

## Commit and Pull Request Guidelines

- Use concise, descriptive, imperative commit messages, for example `Add symptom trend endpoint`.
- Keep commits focused on one logical change.
- Reference related issues or tickets where applicable.
- Pull requests should summarize behavior and security implications, link issues, note migrations/configuration changes, and list local verification commands.
