# PostgreSQL and Oracle 26ai Database Support Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (\`- [ ]\`) syntax for tracking.

**Goal:** Add startup-selected Oracle AI Database 26ai support while preserving PostgreSQL as the default, with separate Flyway histories and equivalent application behavior.

**Architecture:** Keep one Spring application context and one datasource per process. Select the vendor through Spring profiles and the metabion.database enum, point Flyway at exactly one vendor-specific migration directory, and isolate the only native application DML statement behind a vendor-neutral port with PostgreSQL and Oracle implementations.

**Tech Stack:** Spring Boot 4.0.6, Hibernate ORM 7.2.12.Final, Java 25, Gradle, Flyway 11.14.0, PostgreSQL 16, Oracle AI Database 26ai, Oracle JDBC ojdbc11 23.26.3.0.0, JUnit 5, Spring Boot Test, Testcontainers.

## Global Constraints

- PostgreSQL remains the default database and existing PostgreSQL migration SQL remains semantically unchanged.
- Oracle support targets Oracle AI Database 26ai or newer; Oracle 19c and 21c compatibility are not part of this change.
- Database selection happens at startup only; runtime datasource switching is not implemented.
- Flyway remains the schema owner and production-like profiles keep spring.jpa.hibernate.ddl-auto=validate.
- Use the existing Gradle wrapper and do not add a dependency when an existing dependency already provides the required API.
- Preserve session-based authentication, CSRF behavior, bearer-token behavior, OAuth behavior, and all patient/staff access rules.
- Do not modify unrelated pre-existing worktree changes in .idea, .superpowers, .codex, or var.
- Do not migrate or copy existing PostgreSQL patient data into Oracle.
- Oracle 26ai reserves `RESOURCE`. Preserve the existing logical column name by using the lowercase quoted identifier `"resource"` only in the Oracle V15/V17 DDL and in the three affected JPA mappings; do not rename the column and do not enable global identifier quoting. See the [Oracle reserved-word reference](https://docs.oracle.com/en/database/oracle/oracle-database/26/sqlrf/Oracle-SQL-Reserved-Words.html).
- Use Oracle 26ai `CREATE ASSERTION ... DEFERRABLE INITIALLY DEFERRED` for the V4 role/profile integrity rules. Do not approximate the PostgreSQL commit-deferred behavior with an after-statement compound trigger. See the [Oracle assertion reference](https://docs.oracle.com/en/database/oracle/oracle-database/26/sqlrf/create-assertion.html).
- Every implementation task writes its focused test first, observes the expected failure, implements the minimum change, and reruns the focused test before moving on.

---

## Repository Map

Configuration and build files:

- build.gradle
- src/main/resources/application.properties
- src/main/resources/application-dev.properties
- src/main/resources/application-prod.properties
- new src/main/resources/application-postgresql.properties
- new src/main/resources/application-oracle.properties
- new src/main/java/com/metabion/config/DatabaseVendor.java
- new src/main/java/com/metabion/config/DatabaseProperties.java
- new src/main/java/com/metabion/config/DatabaseConfiguration.java

Persistence code:

- src/main/java/com/metabion/repository/EducationLessonCompletionRepository.java
- src/main/java/com/metabion/service/EducationContentService.java
- new src/main/java/com/metabion/repository/EducationLessonCompletionInsertPort.java
- new PostgreSQL and Oracle implementations of that port
- src/main/java/com/metabion/domain/Cohort.java
- src/main/java/com/metabion/domain/EducationLessonLocalization.java
- src/main/java/com/metabion/domain/LabResultAuditEvent.java
- src/main/java/com/metabion/domain/RedFlagTriggerEvent.java
- src/main/java/com/metabion/domain/User.java
- src/main/java/com/metabion/domain/PatientAccessToken.java
- src/main/java/com/metabion/domain/OAuthAuthorizationCode.java
- src/main/java/com/metabion/domain/OAuthRefreshToken.java

Flyway resources:

- current src/main/resources/db/migration/V1 through V21
- new src/main/resources/db/migration/postgresql/V1 through V21
- new src/main/resources/db/migration/oracle/V1 through V21

Tests and documentation:

- new configuration and migration-layout tests under src/test/java/com/metabion/config
- existing EducationContentServiceReadProgressTest and EducationContentRepositoryTest
- new OracleDatabaseIT under src/test/java/com/metabion/integration
- new docs/database-configuration.md

## Task 1: Define the Database Vendor Configuration Contract

**Files:**

- Create: src/main/java/com/metabion/config/DatabaseVendor.java
- Create: src/main/java/com/metabion/config/DatabaseProperties.java
- Test: src/test/java/com/metabion/config/DatabaseVendorTest.java
- Test: src/test/java/com/metabion/config/DatabasePropertiesTest.java

**Interfaces:**

- Produces DatabaseVendor.POSTGRESQL and DatabaseVendor.ORACLE.
- Produces DatabaseVendor.fromProperty(String), accepting case-insensitive postgresql and oracle values and rejecting every other value with IllegalArgumentException.
- Produces a ConfigurationProperties record bound to metabion.database.

- [ ] **Step 1: Write the failing enum tests**

Create DatabaseVendorTest with these behaviors:

    @Test
    void acceptsPostgresqlAndOraclePropertyValues() {
        assertThat(DatabaseVendor.fromProperty("postgresql"))
                .isEqualTo(DatabaseVendor.POSTGRESQL);
        assertThat(DatabaseVendor.fromProperty("ORACLE"))
                .isEqualTo(DatabaseVendor.ORACLE);
    }

    @Test
    void rejectsUnsupportedDatabaseValues() {
        assertThatThrownBy(() -> DatabaseVendor.fromProperty("mysql"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("postgresql")
                .hasMessageContaining("oracle");
    }

    @Test
    void rejectsBlankDatabaseValues() {
        assertThatThrownBy(() -> DatabaseVendor.fromProperty(" "))
                .isInstanceOf(IllegalArgumentException.class);
    }

- [ ] **Step 2: Run the focused tests and confirm the feature is missing**

Run:

    ./gradlew test --tests com.metabion.config.DatabaseVendorTest

Expected: compilation/test failure because DatabaseVendor does not exist yet. Do not change the test to make an existing implementation pass.

- [ ] **Step 3: Implement the minimum configuration contract**

Implement DatabaseVendor as an enum with a static fromProperty method that trims and lowercases using Locale.ROOT, then switches on postgresql and oracle. Throw IllegalArgumentException with the accepted values for every other input.

Implement DatabaseProperties as:

    @ConfigurationProperties(prefix = "metabion")
    public record DatabaseProperties(DatabaseVendor database) {
        public DatabaseProperties {
            Objects.requireNonNull(database, "database");
        }
    }

Use Spring Boot configuration-properties enum binding for the normal application path. Keep fromProperty as the explicit validation API used by unit tests and any non-Spring callers.

- [ ] **Step 4: Add and run property-binding tests**

Use ApplicationContextRunner with EnableConfigurationProperties(DatabaseProperties.class) and these property values:

    metabion.database=postgresql

Assert that DatabaseProperties.database() is POSTGRESQL. Repeat with oracle and assert ORACLE. Add an invalid value test and assert the context fails with a BindException whose cause identifies the unsupported enum value.

Run:

    ./gradlew test --tests com.metabion.config.DatabaseVendorTest --tests com.metabion.config.DatabasePropertiesTest

Expected: PASS.

- [ ] **Step 5: Commit**

    git add src/main/java/com/metabion/config/DatabaseVendor.java src/main/java/com/metabion/config/DatabaseProperties.java src/test/java/com/metabion/config/DatabaseVendorTest.java src/test/java/com/metabion/config/DatabasePropertiesTest.java
    git commit -m "Add database vendor configuration contract"

## Task 2: Add Vendor Dependencies and Startup Profiles

**Files:**

- Modify: build.gradle
- Modify: src/main/resources/application.properties
- Modify: src/main/resources/application-dev.properties
- Modify: src/main/resources/application-prod.properties
- Create: src/main/resources/application-postgresql.properties
- Create: src/main/resources/application-oracle.properties
- Modify: src/main/java/com/metabion/config/DatabaseProperties.java
- Test: src/test/java/com/metabion/config/DatabaseProfilePropertiesTest.java

**Interfaces:**

- PostgreSQL profile selects metabion.database=postgresql, org.postgresql.Driver, PostgreSQLDialect, and classpath:db/migration/postgresql.
- Oracle profile selects metabion.database=oracle, oracle.jdbc.OracleDriver, and classpath:db/migration/oracle.
- Oracle profile leaves hibernate.dialect unset so Hibernate resolves the Oracle version from JDBC metadata.
- Gradle bootRun accepts -Pprofiles=dev,oracle and defaults to dev,postgresql.

- [ ] **Step 1: Write the failing profile contract test**

Create DatabaseProfilePropertiesTest with two Spring Boot test classes or two nested test configurations. Each test must override the datasource with H2 so no real database is needed:

    spring.flyway.enabled=false
    spring.jpa.hibernate.ddl-auto=none
    spring.datasource.url=jdbc:h2:mem:database_profile_test;DB_CLOSE_DELAY=-1
    spring.datasource.driver-class-name=org.h2.Driver
    spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect
    spring.autoconfigure.exclude=org.springframework.boot.session.jdbc.autoconfigure.JdbcSessionAutoConfiguration

The PostgreSQL test activates postgresql and asserts:

    metabion.database == postgresql
    spring.flyway.locations == classpath:db/migration/postgresql
    spring.datasource.driver-class-name == org.postgresql.Driver
    spring.jpa.properties.hibernate.dialect == org.hibernate.dialect.PostgreSQLDialect

The Oracle test activates oracle and asserts:

    metabion.database == oracle
    spring.flyway.locations == classpath:db/migration/oracle
    spring.datasource.driver-class-name == oracle.jdbc.OracleDriver
    spring.jpa.properties.hibernate.dialect is absent from the Oracle profile property source

Use the Spring Environment and inspect the profile-specific property resource directly for the Oracle dialect assertion, because the test datasource overrides the active driver and URL.

- [ ] **Step 2: Run the profile test and confirm it fails**

Run:

    ./gradlew test --tests com.metabion.config.DatabaseProfilePropertiesTest

Expected: FAIL because the profile files, vendor property, and Oracle dependency do not exist yet.

- [ ] **Step 3: Add the Oracle dependencies**

Add these implementation dependencies beside the existing PostgreSQL and Flyway dependencies:

    implementation 'com.oracle.database.jdbc:ojdbc11:23.26.3.0.0'
    implementation 'org.flywaydb:flyway-database-oracle:11.14.0'

Keep:

    implementation 'org.postgresql:postgresql:42.7.4'
    implementation 'org.flywaydb:flyway-database-postgresql:11.14.0'

Do not add a second JDBC abstraction; Spring JDBC is already supplied by the existing Spring Data JPA stack.

- [ ] **Step 4: Split shared and vendor-specific properties**

Keep shared settings in application.properties, including:

    spring.profiles.default=postgresql
    metabion.database=postgresql
    spring.jpa.hibernate.ddl-auto=validate
    spring.jpa.show-sql=false
    spring.jpa.open-in-view=false

Move the current PostgreSQL URL, username, password, driver, dialect, and Flyway location into application-postgresql.properties:

    spring.datasource.url=${DB_URL:jdbc:postgresql://localhost:5432/metabion}
    spring.datasource.username=${DB_USERNAME:metabion}
    spring.datasource.password=${DB_PASSWORD:changeme}
    spring.datasource.driver-class-name=org.postgresql.Driver
    spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect
    spring.flyway.locations=classpath:db/migration/postgresql

Add application-oracle.properties:

    metabion.database=oracle
    spring.datasource.url=${DB_URL:jdbc:oracle:thin:@//localhost:1521/FREEPDB1}
    spring.datasource.username=${DB_USERNAME:metabion}
    spring.datasource.password=${DB_PASSWORD:changeme}
    spring.datasource.driver-class-name=oracle.jdbc.OracleDriver
    spring.flyway.locations=classpath:db/migration/oracle

Do not set spring.jpa.properties.hibernate.dialect in the Oracle profile. Keep the common JPA validation setting in application.properties.

Update bootRun in build.gradle so the profiles property controls the active profile:

    bootRun {
        def activeProfiles = project.findProperty('profiles') ?: 'dev,postgresql'
        args = ["--spring.profiles.active=${activeProfiles}"]
    }

Leave the dev base URL and production secure-cookie settings in their existing profile files. Oracle can be combined with dev or prod at startup.

- [ ] **Step 5: Run the profile tests and a compile check**

Run:

    ./gradlew test --tests com.metabion.config.DatabaseProfilePropertiesTest
    ./gradlew compileJava

Expected: PASS with no missing JDBC driver or Flyway Oracle class errors.

- [ ] **Step 6: Commit**

    git add build.gradle src/main/resources/application.properties src/main/resources/application-dev.properties src/main/resources/application-prod.properties src/main/resources/application-postgresql.properties src/main/resources/application-oracle.properties src/test/java/com/metabion/config/DatabaseProfilePropertiesTest.java
    git commit -m "Add PostgreSQL and Oracle startup profiles"

## Task 3: Isolate the PostgreSQL Flyway History

**Files:**

- Move: src/main/resources/db/migration/V1__init_users.sql through V21__red_flag_detection_foundation.sql to src/main/resources/db/migration/postgresql/
- Create: src/test/java/com/metabion/config/DatabaseMigrationLayoutTest.java
- Modify: src/main/resources/application-postgresql.properties

**Interfaces:**

- PostgreSQL resources are discoverable only under db/migration/postgresql.
- The PostgreSQL migration inventory remains exactly V1 through V21 with the original descriptions and file contents.
- The root db/migration directory has no vendor-neutral SQL files.

- [ ] **Step 1: Write the failing migration-layout test**

Create DatabaseMigrationLayoutTest that scans classpath resources matching db/migration/postgresql/V*.sql and asserts:

    versions == [1, 2, 3, ..., 21]

Parse each filename with:

    ^V([0-9]+)__([A-Za-z0-9_]+)\\.sql$

Also assert that no SQL migration is directly under db/migration and that the file descriptions match the live names:

    1 init_users
    2 verification_and_reset_tokens
    3 spring_session
    4 rbac_assignment_model
    5 staff_invitations
    6 patient_onboarding_submissions
    7 user_theme_preference
    8 user_language_preference
    9 daily_diet_logs
    10 diet_log_photo_storage
    11 education_content_library
    12 link_diet_log_deviations_to_meals
    13 symptom_tracking
    14 patient_access_tokens
    15 mcp_oauth_authorization
    16 oauth_dynamic_client_registration
    17 oauth_client_capabilities
    18 remove_food_category_from_diet_log_meals
    19 laboratory_biomarker_tracking
    20 cohort_assignment_management
    21 red_flag_detection_foundation

- [ ] **Step 2: Run the layout test and confirm it fails**

Run:

    ./gradlew test --tests com.metabion.config.DatabaseMigrationLayoutTest

Expected: FAIL because the live migrations are still directly under db/migration.

- [ ] **Step 3: Move the PostgreSQL migration files without editing their contents**

Create src/main/resources/db/migration/postgresql and move each existing V1 through V21 file into it. Use git mv for the relocation so Git can preserve the original file identity. Do not edit any SQL statement, seed value, version number, or description during this step.

Verify that the old directory contains no *.sql files and that the new directory contains exactly 21 files.

- [ ] **Step 4: Run the layout test and verify the PostgreSQL migration inventory**

Run:

    ./gradlew test --tests com.metabion.config.DatabaseMigrationLayoutTest

Expected: PASS.

Then run the existing migration-backed PostgreSQL tests:

    ./gradlew test --tests com.metabion.repository.EducationContentRepositoryTest --tests com.metabion.integration.EnumerationIT

Expected: PASS against PostgreSQL Testcontainers, confirming the relocated history is discovered by the PostgreSQL profile.

- [ ] **Step 5: Commit**

    git add src/main/resources/db/migration/postgresql src/test/java/com/metabion/config/DatabaseMigrationLayoutTest.java src/main/resources/application-postgresql.properties
    git commit -m "Isolate the PostgreSQL Flyway history"

## Task 4: Add the Oracle 26ai Flyway History

**Files:**

- Create: src/main/resources/db/migration/oracle/V1__init_users.sql through V21__red_flag_detection_foundation.sql
- Modify: src/test/java/com/metabion/config/DatabaseMigrationLayoutTest.java
- Create: src/test/java/com/metabion/config/OracleMigrationContentTest.java

**Interfaces:**

- Oracle resources contain V1 through V21 with the same logical version and description inventory as PostgreSQL.
- Oracle SQL contains no PostgreSQL-only BIGSERIAL, BYTEA, TIMESTAMPTZ, NOW(), ON CONFLICT, CREATE CONSTRAINT TRIGGER, or partial-index WHERE clause.
- Oracle SQL preserves all table names, columns, foreign keys, unique constraints, seed rows, and business invariants of the PostgreSQL history.
- Oracle V15 and V17 preserve the logical `resource` column name as the lowercase quoted identifier `"resource"`; every DDL and DML reference to that column is quoted.
- Oracle V4 preserves commit-deferred role/profile integrity with two `CREATE ASSERTION` objects declared `DEFERRABLE INITIALLY DEFERRED`; the profile-insert PL/SQL triggers remain statement-level guards.

- [ ] **Step 1: Extend the migration-layout test before adding Oracle SQL**

Add a second inventory assertion to DatabaseMigrationLayoutTest for db/migration/oracle. It must assert the same version/description map as PostgreSQL and must not accept a missing or extra Oracle version.

Create OracleMigrationContentTest with these checks:

    no Oracle migration contains BIGSERIAL
    no Oracle migration contains BYTEA
    no Oracle migration contains TIMESTAMPTZ
    no Oracle migration contains ON CONFLICT
    no Oracle migration contains CREATE CONSTRAINT TRIGGER
    no Oracle migration creates a partial index using a trailing WHERE clause
    V15 and V17 contain no unquoted resource identifier and use "resource" for every resource column reference
    V4 contains the patient-profile and staff-profile assertions with DEFERRABLE INITIALLY DEFERRED
    V4 assertion expressions contain no ANSI JOIN syntax
    V4 does not contain a compound trigger for user_roles role-integrity enforcement

The content test must inspect the actual classpath SQL resources and report the migration filename in any failure.

- [ ] **Step 2: Run the Oracle migration tests and confirm they fail**

Run:

    ./gradlew test --tests com.metabion.config.DatabaseMigrationLayoutTest --tests com.metabion.config.OracleMigrationContentTest

Expected: FAIL because the Oracle migration directory does not exist.

- [ ] **Step 3: Translate V1 through V5**

Create these Oracle migrations:

- V1 users: use NUMBER(19) identity for the user ID, BOOLEAN for enabled and MFA flags, BLOB for the encrypted MFA secret, and TIMESTAMP WITH TIME ZONE for timestamps.
- V2 verification/reset tokens: replace BIGSERIAL and timezone defaults while preserving token hash uniqueness and user foreign keys.
- V3 Spring Session: use NUMBER-compatible timestamp columns and BLOB for SPRING_SESSION_ATTRIBUTES.ATTRIBUTE_BYTES. Preserve uppercase table/index names required by Spring Session JDBC.
- V4 RBAC/assignments: translate all IDs to identity/NUMBER, booleans to BOOLEAN, and use function-based unique indexes for active rows. Keep the Oracle `BEFORE INSERT OR UPDATE OF user_id` PL/SQL triggers that reject patient/staff profiles without a matching current role. Replace the PostgreSQL `protect_profile_role_integrity` function and deferred constraint trigger with these two Oracle 26ai assertions:

      CREATE ASSERTION assert_patient_profile_has_role
      CHECK (
          NOT EXISTS (
              SELECT 1
              FROM patient_profiles pp
              WHERE NOT EXISTS (
                  SELECT 1
                  FROM user_roles ur, roles r
                  WHERE r.code = ur.role
                    AND ur.user_id = pp.user_id
                    AND r.patient_profile = TRUE
              )
          )
      )
      DEFERRABLE INITIALLY DEFERRED;

      CREATE ASSERTION assert_staff_profile_has_role
      CHECK (
          NOT EXISTS (
              SELECT 1
              FROM staff_profiles sp
              WHERE NOT EXISTS (
                  SELECT 1
                  FROM user_roles ur, roles r
                  WHERE r.code = ur.role
                    AND ur.user_id = sp.user_id
                    AND r.clinical_staff = TRUE
              )
          )
      )
      DEFERRABLE INITIALLY DEFERRED;

  These assertions must be created after `roles`, `user_roles`, `patient_profiles`, and `staff_profiles`, and they must cover both role deletion and role replacement within one transaction. Oracle assertion expressions prohibit ANSI join syntax, so keep their table relationships in comma-separated `FROM` clauses with predicates in `WHERE`. Do not create an `AFTER STATEMENT` or `COMPOUND TRIGGER` replacement for this invariant: Oracle 26ai assertions are the required commit-deferred mechanism.
- V5 staff invitations: translate the clinical-role trigger to PL/SQL and replace the pending-email partial unique index with a CASE-based function index.

For trigger translations other than the V4 role/profile assertions, use Oracle trigger syntax and keep the checks transactional. The only permitted quoted identifier is lowercase `"resource"`, required because `RESOURCE` is an Oracle 26ai reserved word; do not quote other identifiers, rename the column, or enable global identifier quoting.

- [ ] **Step 4: Translate V6 through V10**

Create these Oracle migrations:

- V6 onboarding: translate identity, timestamps, indexes, and existing check constraints.
- V7 theme and V8 language preference: preserve enum strings, defaults, and non-null behavior.
- V9 daily diet logs: translate all IDs, timestamps, numeric measurements, foreign keys, and cascade behavior.
- V10 photo storage: translate timestamp defaults, status checks, SHA-256 length checks, and photo ownership constraints.

Use CURRENT_TIMESTAMP for timestamp defaults. Do not use session-dependent implicit timestamp parsing.

- [ ] **Step 5: Translate V11 through V18**

Create these Oracle migrations:

- V11 education content: use CLOB for body markdown and other large text columns; preserve localized seed/content constraints and all unique keys.
- V12 meal-deviation link: preserve the ALTER TABLE and foreign-key behavior.
- V13 symptom tracking: use CLOB only where the PostgreSQL schema uses text, BOOLEAN for required/active flags, and Oracle-compatible seed timestamp expressions.
- V14 patient access tokens: preserve token hash uniqueness, scope rows, and timestamp semantics.
- V15 MCP OAuth authorization: preserve resource/client fields, token hash constraints, and authorization-code expiry/consumption behavior. Declare and reference the reserved column as lowercase `"resource"` in the `ALTER TABLE`, backfill `UPDATE`, `MODIFY`, table definition, and all predicates.
- V16 dynamic client registration: preserve client metadata lengths, redirect URI uniqueness, and identity keys.
- V17 OAuth client capabilities: preserve the grant/scope seed copy operations and refresh-token family relationships. Declare the refresh-token resource column as lowercase `"resource"`.
- V18 food-category removal: translate the existing ALTER TABLE operation without reintroducing the removed column or constraint.

For all ISO seed timestamps, use TO_TIMESTAMP_TZ with an explicit format mask such as:

    TO_TIMESTAMP_TZ('2026-07-29 09:00:00 +00:00', 'YYYY-MM-DD HH24:MI:SS TZH:TZM')

- [ ] **Step 6: Translate V19 through V21**

Create these Oracle migrations:

- V19 laboratory tracking: translate lab identity/numeric/timestamp columns and replace the non-removed partial index with a CASE-based function index.
- V20 cohort/assignment management: translate active assignment conditional uniqueness with CASE-based function indexes and preserve all assignment foreign keys and cascade rules.
- V21 red-flag detection: translate all identity, BOOLEAN, timestamp, CLOB, checks, composite foreign keys, and seed rows. Replace the active-version and current-evaluation partial indexes with CASE-based indexes. Rewrite the append-only transition trigger as an Oracle PL/SQL trigger that rejects updates/deletes of historical transitions.

Preserve the exact seed rule keys, version values, statuses, severity values, and source references from the PostgreSQL V21 migration.

- [ ] **Step 7: Run resource/content tests**

Run:

    ./gradlew test --tests com.metabion.config.DatabaseMigrationLayoutTest --tests com.metabion.config.OracleMigrationContentTest

Expected: PASS with matching PostgreSQL and Oracle version/description inventories, no prohibited PostgreSQL syntax in Oracle files, exact quoting for the reserved `resource` columns, and both deferred V4 assertions present.

- [ ] **Step 8: Commit**

    git add src/main/resources/db/migration/oracle src/test/java/com/metabion/config/DatabaseMigrationLayoutTest.java src/test/java/com/metabion/config/OracleMigrationContentTest.java
    git commit -m "Add Oracle 26ai Flyway migrations"

## Task 5: Make Large Text and Binary JPA Mappings Portable

**Files:**

- Modify: src/main/java/com/metabion/domain/Cohort.java
- Modify: src/main/java/com/metabion/domain/EducationLessonLocalization.java
- Modify: src/main/java/com/metabion/domain/LabResultAuditEvent.java
- Modify: src/main/java/com/metabion/domain/RedFlagTriggerEvent.java
- Modify: src/main/java/com/metabion/domain/User.java
- Modify: src/main/java/com/metabion/domain/PatientAccessToken.java
- Modify: src/main/java/com/metabion/domain/OAuthAuthorizationCode.java
- Modify: src/main/java/com/metabion/domain/OAuthRefreshToken.java
- Create: src/test/java/com/metabion/domain/DatabasePortableMappingTest.java

**Interfaces:**

- Large String fields use Hibernate SqlTypes.LONG32VARCHAR.
- The encrypted MFA byte array uses Hibernate SqlTypes.LONG32VARBINARY.
- PostgreSQL validation continues to see TEXT and BYTEA.
- Oracle validation sees CLOB and BLOB.
- No affected entity retains a PostgreSQL-only columnDefinition value.
- PatientAccessToken.resource, OAuthAuthorizationCode.resource, and OAuthRefreshToken.resource use the quoted JPA column name `"resource"` so Hibernate emits a quoted lowercase identifier on both databases. PostgreSQL migration files remain unchanged because their unquoted `resource` columns are lowercase.
- Global Hibernate identifier quoting is not enabled; all other table and column mappings remain unquoted.

- [ ] **Step 1: Write the failing mapping test**

Create DatabasePortableMappingTest using reflection and assert the exact JdbcTypeCode values:

    Cohort.description -> SqlTypes.LONG32VARCHAR
    EducationLessonLocalization.bodyMarkdown -> SqlTypes.LONG32VARCHAR
    LabResultAuditEvent.beforeSnapshot -> SqlTypes.LONG32VARCHAR
    LabResultAuditEvent.afterSnapshot -> SqlTypes.LONG32VARCHAR
    RedFlagTriggerEvent.matchedInputs -> SqlTypes.LONG32VARCHAR
    User.mfaSecretEncrypted -> SqlTypes.LONG32VARBINARY

Also assert that these fields have no non-blank Column.columnDefinition value. Keep each field's existing nullable, length, name, and updatable settings.

Add exact column-name assertions for the reserved resource mappings:

    PatientAccessToken.resource -> Column.name() == "\"resource\""
    OAuthAuthorizationCode.resource -> Column.name() == "\"resource\""
    OAuthRefreshToken.resource -> Column.name() == "\"resource\""

- [ ] **Step 2: Run the mapping test and confirm it fails**

Run:

    ./gradlew test --tests com.metabion.domain.DatabasePortableMappingTest

Expected: FAIL because the fields currently use PostgreSQL-specific TEXT column definitions or no explicit long-binary mapping, and the three resource mappings are currently unquoted.

- [ ] **Step 3: Implement the portable Hibernate mappings**

Import org.hibernate.annotations.JdbcTypeCode and org.hibernate.type.SqlTypes. Replace each large-text columnDefinition with:

    @JdbcTypeCode(SqlTypes.LONG32VARCHAR)

Keep the surrounding @Column annotation without columnDefinition. Add the equivalent LONG32VARBINARY annotation to User.mfaSecretEncrypted and keep its existing column name.

Do not use @Lob for these fields: Hibernate 7.2's PostgreSQL dialect maps CLOB/BLOB LOB APIs to oid, while LONG32VARCHAR/LONG32VARBINARY map to PostgreSQL text/bytea and Oracle CLOB/BLOB.

For the three reserved resource fields, keep their existing `@Column` attributes and change only the name value to the JPA/Hibernate quoted form:

    @Column(name = "\"resource\"", nullable = false, length = 255)

Apply this to PatientAccessToken.resource, OAuthAuthorizationCode.resource, and OAuthRefreshToken.resource. The Oracle migrations must use the matching lowercase quoted identifier. Do not quote the enclosing tables, do not enable `hibernate.globally_quoted_identifiers`, and do not change the PostgreSQL migration history.

- [ ] **Step 4: Run mapping and PostgreSQL validation tests**

Run:

    ./gradlew test --tests com.metabion.domain.DatabasePortableMappingTest
    ./gradlew test --tests com.metabion.repository.EducationContentRepositoryTest --tests com.metabion.repository.RedFlagRuleRepositoryTest --tests com.metabion.repository.RedFlagEvaluationRepositoryTest

Expected: PASS. The repository tests must still pass Hibernate ddl-auto=validate against PostgreSQL migrations.

- [ ] **Step 5: Commit**

    git add src/main/java/com/metabion/domain/Cohort.java src/main/java/com/metabion/domain/EducationLessonLocalization.java src/main/java/com/metabion/domain/LabResultAuditEvent.java src/main/java/com/metabion/domain/RedFlagTriggerEvent.java src/main/java/com/metabion/domain/User.java src/main/java/com/metabion/domain/PatientAccessToken.java src/main/java/com/metabion/domain/OAuthAuthorizationCode.java src/main/java/com/metabion/domain/OAuthRefreshToken.java src/test/java/com/metabion/domain/DatabasePortableMappingTest.java
    git commit -m "Use portable large text and binary mappings"

## Task 6: Add the Vendor-Specific Education Completion Adapter

**Files:**

- Create: src/main/java/com/metabion/repository/EducationLessonCompletionInsertPort.java
- Create: src/main/java/com/metabion/repository/PostgresqlEducationLessonCompletionInsertAdapter.java
- Create: src/main/java/com/metabion/repository/OracleEducationLessonCompletionInsertAdapter.java
- Create: src/main/java/com/metabion/config/DatabaseConfiguration.java
- Modify: src/main/java/com/metabion/repository/EducationLessonCompletionRepository.java
- Modify: src/main/java/com/metabion/service/EducationContentService.java
- Modify: src/test/java/com/metabion/service/EducationContentServiceReadProgressTest.java
- Create: src/test/java/com/metabion/repository/EducationLessonCompletionInsertAdapterTest.java
- Create: src/test/java/com/metabion/config/DatabaseConfigurationTest.java
- Modify: src/test/java/com/metabion/config/DatabasePropertiesTest.java

**Interfaces:**

    public interface EducationLessonCompletionInsertPort {
        int insertCompletionIfAbsent(
                Long patientProfileId,
                Long moduleVersionId,
                Long lessonVersionId);
    }

DatabaseConfiguration exposes exactly one EducationLessonCompletionInsertPort selected from DatabaseProperties.database().

- [ ] **Step 1: Update the service test to express the new port contract**

In EducationContentServiceReadProgressTest, replace the mocked EducationLessonCompletionRepository used for completion insertion with a mocked EducationLessonCompletionInsertPort. Pass that mock to the service constructor and assert:

    verify(completions).insertCompletionIfAbsent(11L, 60L, 100L);

Keep the repository mock for read-progress queries and uncompletion deletes. The existing test must still prove that completing and uncompleting a lesson delegates to the expected persistence operations.

- [ ] **Step 2: Write the failing adapter tests**

Create EducationLessonCompletionInsertAdapterTest with Mockito only for NamedParameterJdbcTemplate. Capture the SQL and MapSqlParameterSource passed to update. Test:

- PostgreSQL adapter executes an INSERT containing ON CONFLICT and returns the JDBC update count.
- Oracle adapter executes MERGE, references education_lesson_completions, and returns the JDBC update count.
- Both adapters bind patientProfileId, moduleVersionId, and lessonVersionId under the exact named parameters.

Add a DatabaseConfigurationTest case that supplies metabion.database=postgresql and metabion.database=oracle to ApplicationContextRunner and asserts that the port bean is the corresponding adapter class. Supply a NamedParameterJdbcTemplate test bean so the context does not need a live database.

- [ ] **Step 3: Run the tests and confirm they fail**

Run:

    ./gradlew test --tests com.metabion.service.EducationContentServiceReadProgressTest --tests com.metabion.repository.EducationLessonCompletionInsertAdapterTest --tests com.metabion.config.DatabasePropertiesTest

Expected: compilation/test failure because the port, adapters, and configuration bean do not exist and the service still has the old constructor.

- [ ] **Step 4: Implement the port and adapters**

Create the port interface. Implement both adapters with constructor-injected NamedParameterJdbcTemplate and a MapSqlParameterSource containing:

    patientProfileId
    moduleVersionId
    lessonVersionId

Use the PostgreSQL INSERT ... ON CONFLICT statement from the current repository. Use this Oracle statement:

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

Create DatabaseConfiguration with EnableConfigurationProperties(DatabaseProperties.class) and a bean method that switches on DatabaseProperties.database(). Use NamedParameterJdbcTemplate as the shared dependency. An invalid enum value cannot reach the switch because configuration binding rejects it.

Remove insertCompletionIfAbsent from EducationLessonCompletionRepository. Keep its JPQL read query and delete method. Replace the EducationContentService field and constructor parameter with EducationLessonCompletionInsertPort and delegate completion insertion to that port.

- [ ] **Step 5: Run focused tests and the PostgreSQL repository behavior test**

Run:

    ./gradlew test --tests com.metabion.service.EducationContentServiceReadProgressTest --tests com.metabion.repository.EducationLessonCompletionInsertAdapterTest --tests com.metabion.config.DatabasePropertiesTest
    ./gradlew test --tests com.metabion.repository.EducationContentRepositoryTest

Expected: PASS. The PostgreSQL repository test must still observe one inserted row and zero for the duplicate completion operation.

- [ ] **Step 6: Commit**

    git add src/main/java/com/metabion/config/DatabaseConfiguration.java src/main/java/com/metabion/repository/EducationLessonCompletionInsertPort.java src/main/java/com/metabion/repository/PostgresqlEducationLessonCompletionInsertAdapter.java src/main/java/com/metabion/repository/OracleEducationLessonCompletionInsertAdapter.java src/main/java/com/metabion/repository/EducationLessonCompletionRepository.java src/main/java/com/metabion/service/EducationContentService.java src/test/java/com/metabion/service/EducationContentServiceReadProgressTest.java src/test/java/com/metabion/repository/EducationLessonCompletionInsertAdapterTest.java src/test/java/com/metabion/config/DatabaseConfigurationTest.java src/test/java/com/metabion/config/DatabasePropertiesTest.java
    git commit -m "Isolate education completion SQL by database"

## Task 7: Add Opt-In Oracle 26ai Integration Verification

**Files:**

- Create: src/test/java/com/metabion/integration/OracleDatabaseIT.java
- Create: docs/database-configuration.md

**Interfaces:**

- The test runs only when ORACLE_TEST_URL matches jdbc:oracle:thin:.*.
- It accepts ORACLE_TEST_URL, ORACLE_TEST_USERNAME, and ORACLE_TEST_PASSWORD.
- It activates the oracle profile and uses the Oracle Flyway location.
- It does not clean a non-disposable schema automatically.

- [ ] **Step 1: Write the disabled-by-default Oracle test**

Create OracleDatabaseIT with:

    @SpringBootTest(properties = {
        "spring.profiles.active=oracle",
        "spring.jpa.hibernate.ddl-auto=validate"
    })
    @EnabledIfEnvironmentVariable(
        named = "ORACLE_TEST_URL",
        matches = "jdbc:oracle:thin:.*"
    )

Use DynamicPropertySource to bind the URL, username, and password from the three Oracle test environment variables. Set the Flyway locations explicitly to classpath:db/migration/oracle and set metabion.database=oracle so the test cannot accidentally use the PostgreSQL path.

Add a first test that autowires Flyway and asserts:

    flyway.info().current() is not null
    flyway.info().applied() contains version 21

Add a metadata test using JdbcTemplate:

    USER table ENABLED and MFA_ENABLED columns report BOOLEAN
    USER MFA_SECRET_ENCRYPTED reports BLOB
    EDUCATION_LESSON_LOCALIZATIONS BODY_MARKDOWN reports CLOB
    PATIENT_ACCESS_TOKENS has a COLUMN_NAME of lowercase resource
    OAUTH_AUTHORIZATION_CODES has a COLUMN_NAME of lowercase resource
    OAUTH_REFRESH_TOKENS has a COLUMN_NAME of lowercase resource
    USER_ASSERTIONS contains ASSERT_PATIENT_PROFILE_HAS_ROLE and ASSERT_STAFF_PROFILE_HAS_ROLE,
    both with DEFERRABLE = DEFERRABLE and DEFERRED = DEFERRED

Use Oracle's uppercase USER_TAB_COLUMNS and USER_ASSERTIONS views. Table names in USER_TAB_COLUMNS are uppercase because their identifiers are unquoted; the three reserved resource column values must be compared as lowercase `resource` because the DDL intentionally quotes that identifier.

- [ ] **Step 2: Run the Oracle test without an environment and verify it is skipped**

Run:

    ./gradlew test --tests com.metabion.integration.OracleDatabaseIT

Expected: the class is skipped because ORACLE_TEST_URL is not set; no Oracle connection is attempted.

- [ ] **Step 3: Add representative persistence assertions**

When an Oracle 26ai test database is available, extend OracleDatabaseIT to:

- create the same minimal user, patient, education module, published version, and lesson fixture used by EducationContentRepositoryTest
- call EducationLessonCompletionInsertPort twice for the same patient and lesson
- assert results one and zero
- verify one completion row exists
- attempt duplicate active assignment data and assert the Oracle function-based unique index rejects it
- attempt an invalid red-flag transition and assert the PL/SQL trigger rejects it
- within a transaction, remove the only patient or clinical-staff role for a profile and assert the commit fails because the deferred V4 assertion is violated
- within one transaction, replace a user's only clinical-staff role with another clinical-staff role and assert the commit succeeds, proving the assertion is deferred to transaction end rather than enforced after each statement

Keep the test database disposable or use a dedicated schema. Do not call Flyway.clean automatically against a shared environment.

- [ ] **Step 4: Document the Oracle verification command**

Add docs/database-configuration.md with:

    # Default PostgreSQL
    ./gradlew bootRun

    # Oracle development profile
    ./gradlew bootRun -Pprofiles=dev,oracle

    # Optional Oracle integration test
    ORACLE_TEST_URL='jdbc:oracle:thin:@//localhost:1521/FREEPDB1' \
    ORACLE_TEST_USERNAME='metabion' \
    ORACLE_TEST_PASSWORD='change-me' \
    ./gradlew test --tests com.metabion.integration.OracleDatabaseIT

Document the official Oracle AI Database Free 26ai container setup, the service-name URL format, the required disposable schema, and that Oracle Free is for development/testing rather than a patched production edition.

- [ ] **Step 5: Run the test class and commit**

Run:

    ./gradlew test --tests com.metabion.integration.OracleDatabaseIT

Expected: SKIPPED without ORACLE_TEST_URL, PASS with a disposable Oracle 26ai environment.

Commit:

    git add src/test/java/com/metabion/integration/OracleDatabaseIT.java docs/database-configuration.md
    git commit -m "Add opt-in Oracle database verification"

## Task 8: Full Regression, Build Verification, and Handoff

**Files:**

- Modify: docs/database-configuration.md if verification reveals a command or property mismatch
- No unrelated files

- [ ] **Step 1: Run focused configuration and migration tests**

    ./gradlew test --tests com.metabion.config.DatabaseVendorTest --tests com.metabion.config.DatabasePropertiesTest --tests com.metabion.config.DatabaseProfilePropertiesTest --tests com.metabion.config.DatabaseMigrationLayoutTest --tests com.metabion.config.OracleMigrationContentTest --tests com.metabion.domain.DatabasePortableMappingTest

Expected: PASS.

- [ ] **Step 2: Run focused persistence and security regression tests**

    ./gradlew test --tests com.metabion.repository.EducationContentRepositoryTest --tests com.metabion.repository.UserRepositoryTest --tests com.metabion.repository.RbacAssignmentRepositoryTest --tests com.metabion.integration.AuthFlowIT --tests com.metabion.integration.CsrfIT --tests com.metabion.integration.McpOAuthFlowIT

Expected: PASS against PostgreSQL Testcontainers/H2 as configured by each existing test.

- [ ] **Step 3: Run the full backend test suite**

    ./gradlew test

Expected: exit code 0, all tests passing, and Jacoco finalization successful. If Docker or PostgreSQL is unavailable, record the exact failing test and environment error rather than claiming completion.

- [ ] **Step 4: Run the packaging build**

    ./gradlew build

Expected: exit code 0, compiled application and boot jar produced, no dependency-resolution or Flyway classpath errors.

- [ ] **Step 5: Inspect the final diff and working tree**

    git diff --check
    git status --short
    git diff master...HEAD --stat

Confirm that only the feature commits and the approved design/plan files are part of this branch. Confirm that pre-existing .idea, .superpowers, .codex, and var changes remain uncommitted unless they were already committed before this work.

- [ ] **Step 6: Report verification precisely**

Report:

- branch name
- commits created
- PostgreSQL test command and result
- build command and result
- Oracle integration command and whether it passed or was skipped due to missing Oracle environment
- any residual risk, especially if Oracle 26ai integration was not available

Do not claim Oracle runtime support is verified if only resource/content tests ran without an Oracle database.

## Plan Self-Review

Coverage:

- Startup profile selection and Gradle bootRun support: Task 2.
- Vendor enum validation and adapter selection: Tasks 1, 2, and 6.
- PostgreSQL migration preservation and Flyway path isolation: Task 3.
- Oracle V1 through V21 schema support: Task 4.
- Oracle reserved `resource` identifier compatibility and PostgreSQL TEXT/BYTEA plus Oracle CLOB/BLOB ORM compatibility: Tasks 4 and 5.
- Commit-deferred role/profile integrity on Oracle 26ai: Tasks 4 and 7.
- Native ON CONFLICT replacement with Oracle MERGE: Task 6.
- Oracle 26ai startup, Flyway, Hibernate validation, and representative behavior: Task 7.
- Documentation and full regression/build evidence: Tasks 7 and 8.

Consistency:

- The property name is metabion.database throughout.
- The port method is insertCompletionIfAbsent throughout.
- The Oracle Flyway location is classpath:db/migration/oracle throughout.
- The PostgreSQL Flyway location is classpath:db/migration/postgresql throughout.
- The Oracle test environment variables are ORACLE_TEST_URL, ORACLE_TEST_USERNAME, and ORACLE_TEST_PASSWORD throughout.
- The logical resource column remains named `resource`; Oracle V15/V17 and the three JPA mappings use lowercase quoted `"resource"`, while the PostgreSQL migration history remains unchanged.
- Oracle V4 role/profile integrity uses `CREATE ASSERTION ... DEFERRABLE INITIALLY DEFERRED`; no after-statement compound trigger is used for that invariant.
- No task depends on runtime database switching or data transfer.
