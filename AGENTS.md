# Repository Guidelines

## Project Overview

Metabion is a **Spring Boot 4.0.6** application built with **Gradle** and **Java 25**. It provides patient and staff REST APIs, a server-rendered Thymeleaf application, and a Spring AI MCP server.

The implemented product areas are:

- Patient registration, email verification, login/logout, password recovery, and session management.
- Role-based access for patients, nutrition specialists, physicians, coordinators, and administrators.
- Staff invitations, patient onboarding submissions, clinical review, cohorts, and staff/patient assignments, exposed through the Thymeleaf workspace and a session-authenticated REST API (`/api/cohorts`, `/api/patients`).
- Daily diet logs, meals, deviations, glucose/ketone measurements, photo upload/storage, and clinical views.
- Symptom questionnaires, daily check-ins, scoring, patient/clinical trends, and SVG trend rendering.
- Laboratory biomarker catalogs, patient/clinical result sets, unit conversion, confirmation status, audited changes/removal, and trends.
- Versioned red-flag rules evaluated on symptom and laboratory writes, with current flags and paginated history exposed through REST, MCP, and the SPA.
- Versioned and localized education content with authoring, review, approval, publishing, and patient completion tracking.
- Theme, language, and glucose-unit preferences.
- A session-authenticated Vue 3 + TypeScript + Vite SPA in `frontend/`, covering patient flows (auth, dashboard, diet logs, symptom check-ins, trends, labs, red flags, onboarding, education, and account/access-token management) and a clinical workspace at `/clinical` (overview, patient check-ins/trends, labs, red flags, onboarding review, and education).
- Scoped patient access tokens and separate OAuth-issued clinical access tokens, with patient MCP tools and opt-in clinician MCP tools at the same endpoint.
- A custom OAuth 2.0 authorization-code flow with PKCE, dynamic client registration, client metadata discovery, patient/clinician subject separation, and optional rotating refresh tokens with token-family reuse revocation.
- An MFA extension point through `MfaChallengeService`; the default implementation remains a no-op.

Authentication for the web application and ordinary REST APIs is session-based, not JWT-based. Patient and clinician bearer authentication is limited to `/api/mcp` and its subpaths; OAuth endpoints issue and refresh these credentials.

## Project Structure

```text
Metabion/
├── build.gradle
├── settings.gradle
├── AGENTS.md
├── plans/                         # Historical authentication plans and architecture notes
├── docs/                          # Database setup and superpowers specs/plans
├── frontend/                      # Patient and clinical SPA; own package.json, src/, and tests/
├── src/
│   ├── main/
│   │   ├── java/com/metabion/
│   │   │   ├── Main.java
│   │   │   ├── config/            # Security, locale, rate limits, database selection, MCP/OAuth
│   │   │   ├── controller/
│   │   │   │   ├── api/           # REST, OAuth, and metadata endpoints
│   │   │   │   └── web/           # Thymeleaf MVC controllers and view helpers
│   │   │   ├── domain/            # JPA entities and domain enums
│   │   │   ├── dto/               # Shared request/response records
│   │   │   │   ├── assignment/
│   │   │   │   ├── mcp/
│   │   │   │   ├── oauth/
│   │   │   │   └── redflag/
│   │   │   ├── exception/
│   │   │   ├── mcp/               # PatientMcpTools and opt-in ClinicianMcpTools
│   │   │   ├── repository/        # Spring Data repositories
│   │   │   └── service/
│   │   │       ├── oauth/         # OAuth authorization, clients, PKCE, and refresh tokens
│   │   │       └── redflag/       # Rule catalog/engine, evaluation, snapshots, and queries
│   │   └── resources/
│   │       ├── application*.properties
│   │       ├── messages*.properties
│   │       ├── db/migration/
│   │       │   ├── postgresql/    # PostgreSQL Flyway history
│   │       │   └── oracle/        # Oracle Flyway history with matching versions
│   │       ├── static/             # CSS and other browser assets
│   │       └── templates/          # Thymeleaf templates
│   └── test/
│       └── java/com/metabion/      # Unit, slice, repository, and integration tests
└── build/                          # Generated output, git-ignored
```

Keep backend production code under `src/main/java/com/metabion/`. Backend tests belong under `src/test/java/com/metabion/` and should mirror production packages where practical. SPA code and tests live in `frontend/src/` and `frontend/tests/`.

## Build, Test, and Development Commands

Use the Gradle wrapper rather than a system Gradle installation.

| Command                                                   | Description                                                                   |
|-----------------------------------------------------------|-------------------------------------------------------------------------------|
| `./gradlew test`                                          | Run the full JUnit suite and finalize with the Jacoco report.                 |
| `./gradlew test --tests 'com.metabion.package.ClassName'` | Run a focused test class.                                                     |
| `./gradlew build`                                         | Compile, test, run Jacoco, and package the application.                       |
| `./gradlew bootRun`                                       | Start the application with `dev,postgresql`, as configured in `build.gradle`. |
| `./gradlew bootRun -Pprofiles=dev,oracle`                 | Start with Oracle database configuration.                                     |
| `./gradlew clean`                                         | Remove generated build output.                                                |

Jacoco HTML output is generated under `build/reports/jacoco/test/html/`. PostgreSQL is the default database (`jdbc:postgresql://localhost:5432/metabion`); Oracle defaults to `jdbc:oracle:thin:@//localhost:1521/FREEPDB1`. Configure database access with `DB_URL`, `DB_USERNAME`, and `DB_PASSWORD`. See `docs/database-configuration.md` for database setup and optional Oracle verification.

The root `package.json` is a stub and stays untouched. Run SPA commands from `frontend/`: `npm run dev`, `npm run test`, `npm run typecheck`, and `npm run build`. The Vite server on :5173 proxies `/api` and `/app` to :8080. `frontend/README.md` documents setup (Node 22), but its patient-only routing description is outdated; use the live router and auth store for role behavior. Do not substitute frontend tests for Gradle verification of the backend.

## Technology Stack

- Java 25 toolchain and Gradle wrapper.
- Spring Boot 4.0.6 with Spring Web MVC, Thymeleaf, Validation, Security, Data JPA, Session JDBC, Mail, and Flyway.
- Spring AI 2.0.0 MCP server over streamable HTTP at `/api/mcp`.
- PostgreSQL by default and Oracle through vendor-specific profiles, JDBC drivers, and Flyway migrations; H2, Testcontainers PostgreSQL, and opt-in Oracle integration coverage in tests.
- Hibernate schema validation with Flyway-owned migrations.
- BCrypt password hashing with cost 12 and Bucket4j authentication rate limiting.
- Local filesystem-backed diet photo storage through `FileStorageService`.
- GreenMail for mail tests, Spring Security Test, and JUnit Platform.
- Vue 3, TypeScript, Vite, Pinia, Vue Router, Vue I18n, Tailwind CSS, and Chart.js in the SPA; Vitest, Vue Test Utils, and MSW for frontend tests.

Do not add dependencies without checking `build.gradle` or `frontend/package.json`, as appropriate, and confirming that the existing stack cannot meet the requirement.

## Architecture and Domain Conventions

The codebase follows a layered structure:

- `controller/api`: REST boundaries, OAuth endpoints, and request validation.
- `controller/web`: server-rendered flows and view-model assembly.
- `mcp`: patient and clinician tool definitions that delegate to the same application services used by HTTP flows.
- `service`: business rules, access checks, orchestration, file/email boundaries, and token handling.
- `service/oauth`: OAuth authorization, client resolution/registration, PKCE, refresh rotation, and family revocation.
- `service/redflag`: validated rule catalogs, fact resolution, deterministic evaluation, persisted snapshots, and patient/clinical response assembly.
- `repository`: Spring Data persistence access.
- `domain`: JPA mappings, lifecycle methods, and value enums.
- `dto`: Java records for API, form, MCP, and OAuth boundaries.
- `config`: Spring Security, localization, time, rate limiting, database vendor selection, MCP restrictions, and bearer authentication.

Reuse shared business services instead of implementing parallel rules in controllers or MCP tools. `PatientAppFacade` and `ClinicalMcpFacade` are the MCP-facing application boundaries. Centralize patient/staff visibility rules in `AccessControlService` and the relevant domain service. Keep database-specific SQL behind narrow adapters, such as `EducationLessonCompletionInsertPort`, selected by `DatabaseConfiguration`.

Important invariants include:

- Patients may access their own data; clinical staff access is constrained by active cohort or expert assignments; administrators have broader review access.
- Roles are represented by `RoleName`: `PATIENT`, `NUTRITION_SPECIALIST`, `PHYSICIAN`, `COORDINATOR`, and `ADMIN`.
- The SPA clinical workspace admits `NUTRITION_SPECIALIST`, `PHYSICIAN`, and `ADMIN`; clinical access takes precedence when selecting the home route. Coordinator-only users go to `/staff-notice`, linking to the Thymeleaf workspace.
- Diet logs are patient/date based and own their meals, deviations, photo references, and measurements.
- Education content is versioned and localized; lifecycle transitions must preserve author/reviewer/publisher authorization rules.
- Symptom check-ins retain questionnaire-version context so historical scoring remains reproducible.
- Laboratory writes preserve normalized values, source/confirmation status, optimistic version checks, and audit history. Patients can modify/remove only their own patient-created result sets; clinical writes require patient access checks.
- Red-flag evaluation runs synchronously in the symptom/laboratory write transaction. Updates supersede the prior evaluation for that source, retaining rule-version and matched-input snapshots; laboratory removal creates a clearing evaluation. Invalid catalogs or snapshot failures must not silently leave a successful source write without its evaluation.
- Red-flag reads use persisted evaluations. Patient responses omit rule versions and matched inputs; clinical responses include them. Preserve the separate response assemblers for both reads and MCP write outcomes.
- Patient and clinician bearer tokens are resource-bound, expiry/revocation checked, and scope-authorized per operation. Shared primitives are `McpTokenCodec`, `McpTokenEligibility`, and `McpScopeCatalog`.
- OAuth authorization codes require PKCE and retain a `PATIENT` or `CLINICIAN` subject. A grant cannot mix patient and clinician scopes. Clients without the `refresh_token` grant receive only an access token; when refresh tokens are issued, they rotate and reuse revokes their family and linked access tokens.

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

- Flyway migrations live in `src/main/resources/db/migration/postgresql/` and `src/main/resources/db/migration/oracle/`, using `V{n}__{slug}.sql`. Both histories currently run through `V22__clinical_mcp_token_storage.sql`; choose the next unused version from live repository state and keep vendor versions/descriptions aligned. Do not place SQL directly in `db/migration/` or rewrite applied PostgreSQL migrations.
- Treat Flyway as the schema owner. Production-like profiles use `spring.jpa.hibernate.ddl-auto=validate`; do not rely on Hibernate schema creation.
- Spring Session tables are also Flyway-managed, and automatic session schema initialization is disabled.
- Put shared settings in `application.properties`, environment overrides in `application-dev.properties`/`application-prod.properties`, and datasource/Flyway settings in `application-postgresql.properties`/`application-oracle.properties`. PostgreSQL is the default and is included by the `dev` and `prod` profile groups; select Oracle explicitly after the environment profile (for example `dev,oracle`). Keep `metabion.database` aligned with the selected datasource and migration location.
- Keep JPA mappings portable across PostgreSQL and Oracle, including large text/binary types, identifiers, and empty-string/null semantics. Vendor-specific constraints and SQL require verification against the corresponding database.
- Put server-rendered user-facing text in `messages.properties` and `messages_cs.properties`, and SPA text in `frontend/src/i18n/en.json` and `frontend/src/i18n/cs.json`; keep keys aligned within each pair.
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
- Verification, reset, patient/clinical access, authorization-code, and refresh-token credentials must be generated securely and stored only in hashed form where the flow permits.
- Never log passwords, token values, authorization codes, session IDs, credentials, or patient-upload contents. `McpAccessAuditService` records authentication/tool outcomes and actor, token ID, client, request-path, and optional target-patient metadata; keep clinical payloads and raw exception details out of audit messages.
- Preserve OAuth redirect URI validation, PKCE verification, client grant-type constraints, refresh-token rotation, and family-wide reuse revocation.
- Preserve MCP scope checks and resource binding. The MCP endpoint is localhost-restricted by default through `metabion.mcp.allowed-localhost-only`; changes to exposure require an explicit security review.
- `ClinicianMcpTools` registration requires both `metabion.mcp.enabled` and `metabion.mcp.clinician-enabled=true` (`METABION_MCP_CLINICIAN_ENABLED`, default `false`). Clinician tokens require an enabled, unlocked physician or nutrition specialist and reject users with `ADMIN` or `COORDINATOR`, even when combined with a clinician role. Recheck current eligibility and assignment boundaries; token scopes do not replace patient visibility checks.
- Preserve separate patient/clinical token storage and tool-family checks. `McpBearerTokenAuthenticationFilter` routes `pat_` and `clin_` tokens through the matching service and accepts valid legacy unprefixed patient tokens. Clinical scope grants persist `clinician:*` authority strings, not Java enum names.
- Clinical tokens are issued through OAuth; `/api/account/clinical-access-tokens` provides session-authenticated listing and revocation. Preserve `McpSecurityContextRepository` isolation so bearer authentication never becomes a browser session.
- Preserve secure session-cookie behavior (`HttpOnly`, `SameSite=Strict`, and `Secure` in production) and session-fixation protection.
- Be especially cautious in `SecurityConfig`, bearer filters/security-context persistence, `SecurityService`, `UserService`, both access-token services, shared MCP token primitives, and `service/oauth`.

## Testing Guidelines

- Run `./gradlew test` after code changes whenever feasible. Use `./gradlew build` when packaging or build lifecycle behavior also changed.
- Use focused tests during iteration, then run the full relevant suite before completion.
- Use `@DataJpaTest` for repository mappings and constraints, MVC/security tests for endpoint policy, and `@SpringBootTest` for cross-layer flows.
- Use H2 only for database-portable behavior. Use Testcontainers PostgreSQL for PostgreSQL constraints, locking, concurrency, and token-rotation semantics.
- `OracleDatabaseIT` is opt-in via `ORACLE_TEST_URL`, `ORACLE_TEST_USERNAME`, and `ORACLE_TEST_PASSWORD`; run `./gradlew test --tests 'com.metabion.integration.OracleDatabaseIT'` only against a disposable schema. It is skipped without the Oracle URL. See `docs/database-configuration.md`; H2/PostgreSQL tests do not establish Oracle compatibility.
- Use GreenMail or the logging mail implementation for email flows.
- Add or update tests for authentication, authorization, scopes, token hashing/rotation/revocation, persistence mappings, validation, file ownership, content lifecycle transitions, and clinical visibility.
- Cover clinician MCP subject separation, excluded/mixed roles, changed assignments, scope persistence, and audit redaction. Red-flag changes need rule-boundary, transaction rollback, supersession/removal, history pagination, and patient/clinical disclosure coverage under `service/redflag/` and the relevant API/MCP tests.
- Relevant integration coverage lives under `src/test/java/com/metabion/integration/`; OAuth concurrency/reuse integration tests also live under `service/oauth/` and PostgreSQL-specific repository tests under `repository/`.
- For SPA changes, run `npm run test`, `npm run typecheck`, and `npm run build` from `frontend/`. Preserve role-aware routing, CSRF/session handling, localized text, preference synchronization, patient timezone handling, and red-flag state cleanup on logout/session expiry.

## Working Conventions for Agents

- Read the relevant production code, tests, configuration, and latest migration before proposing or making changes.
- Prefer live repository state over plans or memory. `plans/01-08` and `plans/PATIENT_AUTH_ARCHITECTURE.md` describe the original authentication implementation. Newer feature specs/plans live under `docs/superpowers/specs/` and `docs/superpowers/plans/`; all are context, not a substitute for current code and tests.
- For MCP/OAuth work, consult the clinician MCP design in `docs/superpowers/specs/2026-08-18-clinician-mcp-tools-design.md` alongside current token services, tools, and tests. For database changes, consult `docs/database-configuration.md` and both migration histories.
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
