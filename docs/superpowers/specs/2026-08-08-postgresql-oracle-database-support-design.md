# PostgreSQL and Oracle 26ai Database Support Design

**Date:** 2026-08-08

**Status:** Approved for implementation planning

## Goal

Allow Metabion to run against either the existing PostgreSQL database or Oracle AI Database 26ai, with the database selected at application startup and the selected database schema created and upgraded by Flyway.

The existing PostgreSQL behavior remains the default. Oracle support is enabled by an explicit startup profile and does not introduce runtime datasource switching.

## Scope

In scope:

- startup-time selection of PostgreSQL or Oracle
- Oracle JDBC and Flyway dependencies
- an Oracle 26ai-compatible Flyway migration history
- vendor-specific schema translations that preserve the current data model and invariants
- removal of persistence mappings that hard-code PostgreSQL types
- replacement of the single PostgreSQL-specific application query with vendor-specific adapters
- PostgreSQL regression coverage and opt-in Oracle integration coverage
- operator documentation for both startup modes

Out of scope:

- switching databases while one process is running
- automatic copying or transformation of existing PostgreSQL data into Oracle
- support for Oracle 19c, 21c, or other older Oracle releases
- automatic database provisioning, Oracle licensing, or production infrastructure setup
- converting every existing PostgreSQL Testcontainers test into a dual-database parameterized suite

## Decision Summary

Use Spring profiles for startup selection:

- PostgreSQL is the default profile and remains the current local and production path.
- Oracle is selected with the oracle profile, optionally combined with dev or prod.
- The application binds a small database-vendor enum from metabion.database. Invalid values fail startup rather than falling back silently.

Use separate Flyway locations:

    classpath:db/migration/postgresql
    classpath:db/migration/oracle

The existing PostgreSQL migrations are moved into the PostgreSQL location without changing their SQL contents or version numbers. Oracle receives equivalent V1 through V21 migrations in its own location. Future schema changes are added to both locations with matching logical version numbers and descriptions.

Keep JPA and service contracts database-neutral. Isolate the one native write query behind an application port with PostgreSQL and Oracle implementations.

## Architecture

### Startup configuration

Shared settings remain in application.properties. Database-specific settings are moved to:

- application-postgresql.properties
- application-oracle.properties

The shared configuration defines PostgreSQL as the default vendor. The PostgreSQL profile contains the current PostgreSQL URL, driver, Hibernate dialect, and Flyway location. The Oracle profile contains the Oracle URL, Oracle JDBC driver, Oracle Flyway location, and the Oracle vendor value.

The application must support these forms:

    ./gradlew bootRun
    ./gradlew bootRun -Pprofiles=dev,oracle

The bootRun task will use the profiles Gradle property when present and otherwise use dev,postgresql. A packaged application can use the standard Spring profile setting:

    SPRING_PROFILES_ACTIVE=prod,oracle

Database credentials and URLs remain environment-driven:

- DB_URL
- DB_USERNAME
- DB_PASSWORD

Oracle URLs use the Oracle Thin easy-connect service format:

    jdbc:oracle:thin:@//host:1521/service

The local Oracle Free development service is FREEPDB1. This is a development default only, not a production credential or provisioning contract.

### Vendor selection and the education-completion write

Add a configuration properties type that binds metabion.database to a finite vendor enum with POSTGRESQL and ORACLE values. A database configuration component selects one implementation of an EducationLessonCompletionInsertPort at startup.

The existing Spring Data repository continues to own ordinary entity reads, deletes, and persistence operations. Its PostgreSQL-specific insertCompletionIfAbsent query is removed. EducationContentService calls the port instead.

The PostgreSQL port implementation executes the current atomic insert:

    INSERT INTO education_lesson_completions(
        patient_profile_id, module_version_id, lesson_version_id
    )
    VALUES (:patientProfileId, :moduleVersionId, :lessonVersionId)
    ON CONFLICT ON CONSTRAINT
        ux_education_lesson_completions_patient_lesson DO NOTHING

The Oracle port implementation executes an atomic MERGE using the existing patient and lesson uniqueness key:

    MERGE INTO education_lesson_completions target
    USING (
        SELECT :patientProfileId patient_profile_id,
               :moduleVersionId module_version_id,
               :lessonVersionId lesson_version_id
        FROM dual
    ) source
    ON (
        target.patient_profile_id = source.patient_profile_id
        AND target.lesson_version_id = source.lesson_version_id
    )
    WHEN NOT MATCHED THEN
        INSERT (
            patient_profile_id, module_version_id, lesson_version_id
        )
        VALUES (
            source.patient_profile_id,
            source.module_version_id,
            source.lesson_version_id
        )

Both implementations return one for an inserted row and zero for an existing row. They run in the caller's transaction and retain the database unique constraint as a second line of protection.

### Hibernate and JPA

Move the PostgreSQL dialect out of shared configuration. The PostgreSQL profile may keep the existing explicit PostgreSQL dialect. The Oracle profile leaves the dialect available for Hibernate's JDBC metadata resolution so Hibernate 7.2.12.Final can detect Oracle 26ai and select its modern Oracle behavior.

The domain model keeps GenerationType.IDENTITY. Oracle migrations use identity columns compatible with Hibernate's Oracle 12c-or-newer identity support.

Replace vendor-specific columnDefinition values such as TEXT with dialect-neutral JPA mappings:

- large String fields use @Lob or an equivalent Hibernate portable large-character mapping
- encrypted byte arrays use @Lob and map to an Oracle BLOB
- ordinary String, numeric, enum, date, and timestamp mappings remain unchanged

The resulting Oracle schema uses native BOOLEAN columns because Oracle 26ai supports SQL BOOLEAN and Hibernate can resolve that capability from the database version. The mappings are verified through Hibernate schema validation rather than relying on generated schema creation.

## Flyway Migration Design

### Directory layout

The current V1 through V21 PostgreSQL files are moved as-is to:

    src/main/resources/db/migration/postgresql/

Oracle equivalents are added to:

    src/main/resources/db/migration/oracle/

The root migration directory will not contain mixed-vendor files. Each active profile points Flyway to exactly one vendor directory, preventing both histories from being discovered in the same application context.

The PostgreSQL migration contents remain immutable after the move. Their Flyway version, description, and checksum content are preserved. Existing PostgreSQL databases must continue to see the same applied migration history.

The Oracle migration history uses the same logical versions and descriptions as PostgreSQL so that the two schemas evolve in parallel. A future schema change is complete only when both vendor locations contain its equivalent migration.

### Oracle type and DDL translations

The Oracle migrations use these translations:

| PostgreSQL construct | Oracle 26ai construct |
| --- | --- |
| BIGSERIAL primary keys | NUMBER(19) identity columns |
| BIGINT foreign keys | NUMBER(19) |
| BOOLEAN and TRUE/FALSE defaults | BOOLEAN and TRUE/FALSE |
| TIMESTAMPTZ | TIMESTAMP WITH TIME ZONE |
| BYTEA | BLOB |
| TEXT | CLOB |
| NOW() | CURRENT_TIMESTAMP |
| ISO timestamp seed strings | Explicit TO_TIMESTAMP_TZ expressions |
| PostgreSQL partial unique index | Function-based unique index with CASE expressions |
| PostgreSQL trigger function | PL/SQL trigger |
| PostgreSQL constraint trigger | Declarative constraint or an equivalent Oracle trigger strategy |

Identity columns use an Oracle form equivalent to:

    NUMBER(19) GENERATED BY DEFAULT ON NULL AS IDENTITY

The exact identity clause is kept consistent across all Oracle tables and validated against the generated-key behavior of the active Hibernate and Oracle JDBC versions.

Function-based indexes preserve the existing conditional uniqueness semantics. For example, an active-only pair is indexed with both columns wrapped in the same condition:

    CASE WHEN is_current THEN source_type END
    CASE WHEN is_current THEN source_id END

The same approach is used for active cohort memberships, active expert assignments, active cohort-staff assignments, pending staff invitations, and non-removed lab result sets.

PostgreSQL V4, V5, and V21 trigger logic is rewritten as Oracle PL/SQL while preserving the current role-integrity, append-only transition, and red-flag state invariants. The Oracle implementation must avoid mutating-table errors and must perform all checks in the same transaction as the triggering write.

Spring Session's V3 schema receives an Oracle equivalent, including BLOB storage for session attribute bytes and the same table and index names expected by Spring Session JDBC.

Seed data is preserved. Timestamp literals in Oracle scripts use explicit timezone-aware conversion rather than relying on the Oracle session NLS format.

### Schema validation

Production-like profiles keep:

    spring.jpa.hibernate.ddl-auto=validate

Flyway remains the schema owner. Hibernate-generated DDL is not used to create or repair either database.

## Error Handling and Operational Behavior

- An invalid metabion.database value fails configuration binding during startup.
- Missing Oracle JDBC configuration causes the Oracle startup to fail rather than selecting PostgreSQL.
- A PostgreSQL profile never loads Oracle migrations, and an Oracle profile never loads PostgreSQL migrations.
- Flyway migration failures and Hibernate validation mismatches fail application startup.
- The selected database vendor may be logged as non-sensitive startup metadata; URLs, credentials, tokens, and patient data are never logged.
- No migration or application path silently converts or copies patient data between databases.

## Testing Strategy

### Configuration and resource tests

Add tests that verify:

- the default vendor is PostgreSQL
- the Oracle profile binds the Oracle vendor
- invalid vendor values fail configuration
- PostgreSQL and Oracle Flyway locations are mutually exclusive
- the correct education-completion adapter is selected for each vendor
- both migration directories contain matching version/description sets

### PostgreSQL regression coverage

Keep the existing PostgreSQL Testcontainers and H2 tests. Run the full Gradle test suite after the migration move and ORM mapping changes. The existing PostgreSQL repository tests remain the authoritative coverage for PostgreSQL locking, unique constraints, trigger behavior, and token rotation.

### Oracle integration coverage

Add an opt-in Oracle integration suite using the official Oracle AI Database Free 26ai container or an externally supplied Oracle 26ai JDBC URL. The suite is disabled unless the Oracle test environment is explicitly enabled, so ordinary local tests do not require the large Oracle image.

The Oracle suite verifies:

- a fresh database applies every Oracle migration
- Flyway validation succeeds on a second startup
- Hibernate schema validation succeeds
- identity-generated IDs are returned correctly
- boolean, timestamp, CLOB, BLOB, and enum mappings round-trip
- education completion insertion is idempotent through Oracle MERGE
- unique conditional indexes reject duplicate active records
- role-integrity and append-only triggers reject invalid changes
- representative authentication, session, education, and clinical persistence flows work

The Oracle test command and required environment variables are documented with the application database setup.

## Documentation

Add docs/database-configuration.md covering:

- PostgreSQL as the default
- Oracle profile activation
- Gradle and packaged-application commands
- DB_URL, DB_USERNAME, and DB_PASSWORD
- Oracle service-name JDBC syntax
- Flyway's vendor-specific migration locations
- the requirement to use Oracle AI Database 26ai or newer
- the opt-in Oracle integration test
- the fact that schema support does not migrate existing PostgreSQL data

## Expected File Areas

Configuration:

- build.gradle
- src/main/resources/application.properties
- src/main/resources/application-dev.properties
- src/main/resources/application-prod.properties
- src/main/resources/application-postgresql.properties
- src/main/resources/application-oracle.properties
- src/main/java/com/metabion/config/DatabaseProperties.java
- src/main/java/com/metabion/config/DatabaseConfiguration.java

Persistence:

- src/main/java/com/metabion/repository/EducationLessonCompletionRepository.java
- src/main/java/com/metabion/repository/EducationLessonCompletionInsertPort.java
- PostgreSQL and Oracle insert adapter implementations
- affected domain mappings that currently use PostgreSQL columnDefinition values

Migrations:

- src/main/resources/db/migration/postgresql/V1 through V21
- src/main/resources/db/migration/oracle/V1 through V21

Tests and documentation:

- configuration and migration-layout tests
- Oracle integration test support
- docs/database-configuration.md

## Acceptance Criteria

The feature is complete when:

1. The application starts against the current PostgreSQL setup without a behavior change.
2. Starting with the Oracle profile connects to Oracle AI Database 26ai and applies the Oracle Flyway history.
3. Hibernate validation passes for both fresh PostgreSQL and fresh Oracle schemas.
4. The application-level education completion operation has identical insert-or-zero semantics on both databases.
5. Existing PostgreSQL tests pass.
6. The opt-in Oracle integration suite passes when an Oracle 26ai environment is available.
7. The database selection and setup commands are documented.
8. No existing PostgreSQL data is modified by the database-support change.
