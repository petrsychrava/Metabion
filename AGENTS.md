# Repository Guidelines

## Project Structure

This is a **Spring Boot 4** application built with **Gradle**, targeting **Java 25**.

```
Metabion/
├── build.gradle          # Gradle build configuration
├── settings.gradle       # Project name: Metabion
├── src/
│   ├── main/
│   │   ├── java/com/metabion/Main.java   # Application entry point
│   │   └── resources/                      # Application resources
│   └── test/
│       ├── java/                           # Test sources
│       └── resources/                      # Test resources
├── .gradle/            # Gradle wrapper and cache
└── build/              # Build output (git-ignored)
```

- Source code lives under `src/main/java/com/metabion/`.
- Tests go under `src/test/java/`, mirroring the main package structure.

## Build, Test, and Development Commands

| Command | Description |
|---|---|
| `./gradlew build` | Compiles, tests, and packages the application |
| `./gradlew test` | Runs all unit and integration tests |
| `./gradlew run` | Runs the application locally |
| `./gradlew clean` | Removes the `build/` directory |

The project uses the Gradle Wrapper (`gradlew` / `gradlew.bat`); prefer these over a system-installed Gradle to ensure version consistency.

## Coding Style & Naming Conventions

- **Language:** Java (JLS conventions).
- **Indentation:** 4 spaces (standard for Java).
- **Naming:**
  - Classes: `PascalCase` (e.g., `Main`, `UserController`).
  - Methods & variables: `camelCase` (e.g., `hello()`, `userName`).
  - Constants: `UPPER_SNAKE_CASE` (e.g., `MAX_RETRIES`).
- **Package:** All code resides in `com.metabion`.
- **IDE:** IntelliJ IDEA is the primary IDE (`.idea/` and `.iml` files present).

## Testing Guidelines

- **Framework:** JUnit Platform with Spring Boot Test support.
- **Run tests:** `./gradlew test`
- Test files should mirror the main package structure, e.g., `src/test/java/com/metabion/MainTest.java`.
- Tests are annotated with `@SpringBootTest` or lighter `@WebMvcTest` / `@DataJpaTest` as appropriate.

## Commit & Pull Request Guidelines

- **Commit messages** should be concise and descriptive (e.g., `Add user registration endpoint`).
- Use the imperative mood and present tense.
- Reference related issues or tickets where applicable.
- **Pull requests** should include:
  - A clear description of the changes.
  - Linked issue numbers.
  - Steps to verify the changes locally.

## Configuration

- Application properties should be placed in `src/main/resources/application.properties` or `application.yml`.
- Use environment variables or Spring profiles for environment-specific configuration.
- Never commit secrets or credentials; use `.env` files or external secret management in production.
