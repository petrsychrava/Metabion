# Repository Guidelines

## Project Overview

Metabion is a **Spring Boot 4.0.6** authentication, user management, onboarding, and staff workflow backend service built with **Gradle** and **Java 25**.

The application is currently focused on patient account registration, verification, login/logout, password reset, role-based access, session authentication, staff invitations, patient onboarding review, user preferences, localization, and an MFA extension point.

## Project Structure

```text
Metabion/
├── build.gradle
├── settings.gradle
├── AGENTS.md
├── QWEN.md
├── IMPLEMENTATION_PLAN.md
├── plans/
├── src/
│   ├── main/
│   │   ├── java/com/metabion/
│   │   │   ├── Main.java
│   │   │   ├── config/       # Security, localization, rate limiting, and app configuration
│   │   │   ├── controller/   # API and web MVC endpoints
│   │   │   ├── domain/       # JPA entities
│   │   │   ├── dto/          # Request/response records
│   │   │   ├── exception/    # Application exceptions
│   │   │   ├── repository/   # Spring Data repositories
│   │   │   └── service/      # Business logic
│   │   └── resources/
│   │       ├── application.properties
│   │       ├── messages*.properties # Localization bundles
│   │       └── db/migration/ # Flyway migrations
│   └── test/
│       ├── java/com/metabion/
│       └── resources/
└── build/                   # Generated output, git-ignored
```

Keep production code under `src/main/java/com/metabion/`. Tests belong under `src/test/java/com/metabion/` and should mirror the production package where practical.

## Build, Test, and Development Commands

Use the Gradle wrapper instead of a system Gradle installation.

| Command | Description |
|---|---|
| `./gradlew build` | Compile, test, run Jacoco, and package |
| `./gradlew test` | Run all unit and integration tests; finalizes with Jacoco report |
| `./gradlew run` | Run the application locally |
| `./gradlew clean` | Remove generated build output |

Jacoco HTML reports are generated under `build/reports/jacoco/test/html/` after tests.

## Technology Stack

- Java 25 toolchain
- Spring Boot 4.0.6
- Spring Web, Thymeleaf, Validation, Security, Data JPA, Flyway, Session JDBC, Mail
- PostgreSQL for production persistence
- H2 and Testcontainers PostgreSQL for tests
- Flyway-owned database migrations
- BCrypt password hashing with cost 12
- Bucket4j for auth rate limiting
- GreenMail for mail-related tests
- JUnit Platform with Spring Boot Test and Spring Security Test

## Architecture and Domain Conventions

The codebase follows a layered structure:

- `controller`: HTTP API boundaries and validation entry points.
- `controller/api`: REST API endpoints.
- `controller/web`: server-rendered web MVC endpoints.
- `service`: business rules, security flows, token handling, email orchestration.
- `repository`: Spring Data persistence access.
- `domain`: JPA entities and value objects.
- `dto`: request and response records.
- `config`: security and framework configuration.

Important domain flows include:

- Registration with email verification tokens.
- Login/logout using server-side sessions, not JWT.
- Password reset with hashed reset tokens.
- Role-based access through `RoleName`, `UserRole`, and assignment entities.
- Staff invitations and invitation acceptance.
- Patient onboarding submissions and staff review.
- Cohort membership, cohort staff assignment, and patient expert assignment.
- User theme and language preferences.
- Localization through message bundles and authenticated locale handling.
- MFA extensibility through `MfaChallengeService`; the current default is no-op.
- Email delivery through `EmailService` with SMTP and logging implementations.

## Coding Style and Naming

- Follow standard Java conventions and 4-space indentation.
- Classes use `PascalCase`; methods and variables use `camelCase`; constants use `UPPER_SNAKE_CASE`.
- Keep all application packages under `com.metabion`.
- Prefer constructor injection for Spring beans.
- DTOs should be Java records with Jakarta Bean Validation where appropriate.
- Keep comments sparse and useful; do not narrate obvious code.
- Do not introduce new dependencies without checking `build.gradle` and confirming they are necessary.

## Persistence and Configuration

- Flyway migrations live in `src/main/resources/db/migration/` and use `V{n}__{slug}.sql` names.
- Treat Flyway as the owner of database schema. Production-like configurations should validate schema rather than relying on Hibernate to create it.
- Application settings belong in `src/main/resources/application.properties` or profile-specific files.
- User-facing message bundles belong in `src/main/resources/messages*.properties`.
- Use environment variables or Spring profiles for environment-specific values.
- Never commit secrets, credentials, plaintext tokens, passwords, or session identifiers.

## Security Guidelines

Security-sensitive changes need extra care and focused verification.

- Preserve session-based authentication unless explicitly asked to change it.
- Keep CSRF enabled except for explicitly permitted public auth endpoints.
- Keep login failure responses generic to avoid account enumeration.
- Maintain timing equalization for unknown users where applicable.
- Store only hashed verification and reset tokens.
- Do not log passwords, tokens, session IDs, or credentials.
- Be cautious when editing `SecurityConfig`, authentication flow, lockout logic, token generation, email verification, and password reset behavior.

## Testing Guidelines

- Run `./gradlew test` after code changes whenever feasible.
- Use focused test slices when appropriate: `@DataJpaTest`, web/security MVC tests, or `@SpringBootTest` for full-context behavior.
- Use H2 for lightweight persistence tests when the behavior is database-portable.
- Use Testcontainers PostgreSQL for Postgres-specific behavior and integration coverage.
- Use GreenMail or logging mail implementations for email-flow tests.
- Add or update tests for changes to authentication, authorization, token handling, persistence mappings, and validation.

## Working Conventions for Agents

- Read the relevant code before proposing or making changes.
- Respect existing user changes in the worktree; do not revert unrelated modifications.
- Prefer small, focused edits over broad refactors.
- Keep responses concise and include the verification command and result when code was changed.
- If tests cannot be run, state why and describe the residual risk.
- For multi-phase work, consult `IMPLEMENTATION_PLAN.md` and the relevant file in `plans/` before implementation.
- Prefer live repository state over memory or assumptions.

## Tooling Priority for Agents

Use IntelliJ IDEA MCP tools first for repository work whenever they are available and functioning.

Prefer IDEA MCP over shell commands for:

- Reading project files.
- Searching project files or symbols.
- Editing or creating project files.
- Inspecting file problems, compiler diagnostics, and IDE analysis.
- Building or compiling changed files when IDEA MCP can validate the change.

Do not use shell commands for those tasks while IDEA MCP tools are available and suitable.

Shell commands are allowed for tasks IDEA MCP does not cover well, including:

- Git operations such as `git status`, `git diff`, `git add`, `git commit`, and `git log`.
- Gradle commands when an exact test, build, or run command is needed.
- Environment, process, filesystem size, or dependency-cache checks.
- Commands explicitly requested by the user.

If an IDEA MCP tool fails, retry once or use the closest IDEA MCP alternative before falling back to shell. When falling back to shell for a task normally covered by IDEA MCP, state the reason.

## Commit and Pull Request Guidelines

- Commit messages should be concise, descriptive, imperative, and present tense, for example `Add user registration endpoint`.
- Keep commits focused on one logical change.
- Reference related issues or tickets where applicable.
- Pull requests should include a clear description, linked issues, and local verification steps.

## Configuration

- Application properties should be placed in `src/main/resources/application.properties` or `application.yml`.
- Use environment variables or Spring profiles for environment-specific configuration.
- Never commit secrets or credentials; use `.env` files or external secret management in production.
