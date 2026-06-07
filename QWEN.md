# QWEN.md — Project Context for Qwen Code

## What is this file?

QWEN.md is a project-level instruction file that Qwen Code reads at the start of every conversation. It provides context about the codebase, collaboration preferences, and working conventions so I can help you effectively without asking repetitive questions.

## Project Overview

**Metabion** is a **Spring Boot 4.0.6** authentication, user management, onboarding, and staff workflow backend service built with **Gradle** and **Java 25**.

### Tech Stack
- **Java 25** with Spring Boot 4.0.6
- **Gradle** (wrapper preferred) with Jacoco for test coverage
- **Spring Web, Thymeleaf, Validation, Security, Data JPA, Flyway, Session JDBC, Mail**
- **PostgreSQL** for production, **H2** and **Testcontainers** for testing
- **Flyway** for database migrations
- **Spring Security** with JDBC session management
- **Bucket4j** for rate limiting
- **Spring Mail** for email delivery (with a logging fallback for testing)

### Architecture
The application follows a layered architecture:

```
src/main/java/com/metabion/
├── config/          # Security, localization, rate limiting, and app configuration
├── controller/      # API and web MVC endpoints
├── domain/          # JPA entities (User, UserRole, tokens)
├── dto/             # Data transfer objects
├── exception/       # Custom exceptions
├── repository/      # Spring Data JPA repositories
└── service/         # Business logic (User, Email)
```

### Key Domains
- **User Registration** with email verification via tokens
- **Password Reset** flow with hashed reset tokens
- **Role-based Access** (UserRole entity with composite key)
- **Staff Invitations** and invitation acceptance
- **Patient Onboarding** submission and staff review
- **Cohort Membership**, cohort staff assignment, and patient expert assignment
- **User Preferences** for theme and language
- **Localization** via message bundles and authenticated locale handling
- **Rate Limiting** on auth endpoints
- **Email Service** abstraction with SMTP and logging implementations

## Collaboration Preferences

### How I Should Work With You
- **Run verification after changes:** Always run `./gradlew test` and `./gradlew build` after making code modifications to ensure nothing breaks
- **Respect existing conventions:** Follow the established Java naming conventions, 4-space indentation, and package structure
- **Prefer live verification over memory:** When in doubt about the current state, check the actual files and git history rather than relying on assumptions
- **Keep responses concise:** You prefer direct, actionable responses without unnecessary summaries

### What to Avoid
- Don't introduce dependencies not already in `build.gradle` without asking
- Don't modify security configuration without explicit approval
- Don't commit secrets or credentials
- Don't make broad assumptions about code I haven't read

### Testing Strategy
- Tests use **JUnit 5** with **Spring Boot Test**
- Integration tests use **Testcontainers** for PostgreSQL
- Email testing uses **Greenmail** (mock SMTP server)
- H2 is available for lightweight unit tests
- Jacoco reports are generated after every test run

## Build & Development Commands

| Command | Purpose |
|---|---|
| `./gradlew build` | Compile, test, and package |
| `./gradlew test` | Run all tests |
| `./gradlew run` | Start the application locally |
| `./gradlew clean` | Clean build artifacts |

## Git Workflow
- Commit messages use **imperative mood, present tense**
- Keep commits focused and descriptive
- Reference issue/ticket numbers when applicable
