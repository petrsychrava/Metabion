# PostgreSQL/Oracle final fix report

## Scope

- Branch: `codex/oracle-database-support`
- Base before fix wave: `e60b10a3f53bcee3500db20b9d7a6e6a07d2a0c8`
- PostgreSQL migrations, authentication/security behavior, and unrelated worktree changes were not modified.

## Changed files

- `docs/superpowers/plans/2026-08-08-postgresql-oracle-database-support.md`
  - Replaced ANSI joins in the Oracle V4 assertion examples with comma joins and `WHERE` predicates.
  - Added an explicit instruction and test expectation prohibiting ANSI joins inside assertion expressions.
- `src/main/resources/db/migration/oracle/V4__rbac_assignment_model.sql`
  - Rewrote both assertion subqueries to use comma joins and `WHERE` predicates.
- `src/test/java/com/metabion/config/OracleMigrationContentTest.java`
  - Added focused coverage that extracts both V4 assertions and rejects ANSI `JOIN` syntax within them.
- `src/test/java/com/metabion/integration/OracleDatabaseIT.java`
  - Corrected `USER_ASSERTIONS` selection, filtering, and row mapping to use `ASSERTION_NAME`.
  - Added a bounded two-thread education-completion insert-or-zero assertion using UUID-isolated fixture data.
- `src/main/java/com/metabion/repository/OracleEducationLessonCompletionInsertAdapter.java`
  - Converts `DuplicateKeyException` from the Oracle `MERGE` into a zero update result while allowing unrelated exceptions to propagate.
- `src/test/java/com/metabion/repository/EducationLessonCompletionInsertAdapterTest.java`
  - Added focused duplicate-key and unrelated-data-access exception coverage.
- `.superpowers/sdd/2026-08-08-postgresql-oracle-database-support/final-fix-report.md`
  - Records this fix wave and its verification evidence.

## Test-first failures

1. Oracle V4 assertion syntax:
   - Command: `./gradlew test --tests com.metabion.config.OracleMigrationContentTest.rbacAssertionsDoNotUseAnsiJoinSyntax`
   - Result before SQL change: exit 1; one test failed at `OracleMigrationContentTest.java:123` because each assertion still contained ANSI `JOIN`.
   - Result after SQL change: `BUILD SUCCESSFUL` in 1s.
2. Concurrent Oracle `MERGE` duplicate-key behavior:
   - Command: `./gradlew test --tests com.metabion.repository.EducationLessonCompletionInsertAdapterTest.oracleDuplicateKeyDuringConcurrentMergeReturnsZero`
   - Result before adapter change: exit 1; the test received the configured `DuplicateKeyException` instead of zero.
   - Result after adapter change: covered by the full adapter test class and consolidated suite, both successful.
3. `USER_ASSERTIONS` metadata correction:
   - `OracleDatabaseIT` is itself the environment-gated integration coverage. No Oracle runtime was available to produce a database-backed red result; the corrected query was compile-checked and the test class was confirmed skipped locally.

## Verification commands and output

- Baseline covering tests:
  - `./gradlew test --tests com.metabion.config.OracleMigrationContentTest --tests com.metabion.repository.EducationLessonCompletionInsertAdapterTest --tests com.metabion.integration.OracleDatabaseIT`
  - `BUILD SUCCESSFUL` in 2s before edits.
- Focused migration green:
  - `./gradlew test --tests com.metabion.config.OracleMigrationContentTest.rbacAssertionsDoNotUseAnsiJoinSyntax`
  - `BUILD SUCCESSFUL` in 1s.
- Focused adapter green:
  - `./gradlew test --tests com.metabion.repository.EducationLessonCompletionInsertAdapterTest`
  - `BUILD SUCCESSFUL` in 2s; four tests, zero failures.
- Oracle integration gate:
  - `./gradlew test --tests com.metabion.integration.OracleDatabaseIT`
  - `BUILD SUCCESSFUL` in 1s; XML result recorded eight tests skipped, zero failures/errors because `ORACLE_TEST_URL` was unavailable.
- Requested configuration/migration/mapping plus adapter/configuration suite:
  - `./gradlew test --tests com.metabion.config.DatabaseVendorTest --tests com.metabion.config.DatabasePropertiesTest --tests com.metabion.config.DatabaseProfilePropertiesTest --tests com.metabion.config.DatabaseMigrationLayoutTest --tests com.metabion.config.OracleMigrationContentTest --tests com.metabion.domain.DatabasePortableMappingTest --tests com.metabion.repository.EducationLessonCompletionInsertAdapterTest --tests com.metabion.config.DatabaseConfigurationTest`
  - `BUILD SUCCESSFUL` in 7s; 28 tests, zero skipped/failures/errors.
- IntelliJ changed-file compilation:
  - Successful; zero reported compilation problems.
- Full backend suite:
  - `./gradlew test`
  - Exit 1 after 33s: 1,134 tests completed, 29 failed, 8 skipped. All 29 failures were Testcontainers initialization errors from `DockerClientProviderStrategy` because Docker was unavailable; no test assertion failure was reported.

Gradle emitted its existing Java native-access and CDS warnings; there were no test failures or compilation errors in the successful runs.

## Residual concerns

- Oracle AI Database 26ai was not available in this environment. Oracle migration execution, `USER_ASSERTIONS` runtime metadata, and the bounded concurrency assertion remain runtime-unverified and were not claimed as passed.
- Docker was unavailable, so the complete PostgreSQL Testcontainers portion of `./gradlew test` could not run. The requested non-container configuration/migration/mapping/adapter suite passed independently.
- The duplicate-key recovery path is directly unit-tested with Spring's `DuplicateKeyException`; actual Oracle exception translation depends on the configured Oracle JDBC/Spring translator and remains part of the opt-in Oracle integration verification.
