# Database configuration

Metabion runs on PostgreSQL by default. The Oracle profile uses the Oracle-specific
Flyway migration set and the Oracle JDBC driver; keep application credentials in
environment variables rather than committing them.

## Default PostgreSQL

```bash
./gradlew bootRun
```

## Oracle development profile

```bash
./gradlew bootRun -Pprofiles=dev,oracle
```

Set `DB_URL`, `DB_USERNAME`, and `DB_PASSWORD` for an Oracle profile when the
defaults are not appropriate. Oracle JDBC Easy Connect URLs use a service name,
for example `jdbc:oracle:thin:@//localhost:1521/FREEPDB1`.

## Oracle AI Database Free 26ai

For disposable local development/testing, use Oracle's official Oracle AI Database
Free container image. After accepting the Oracle Container Registry terms, start a
local container and wait for `DATABASE IS READY TO USE!`:

```bash
export ORACLE_PWD='a-local-development-password'
docker pull container-registry.oracle.com/database/free:latest-lite
docker volume create OracleDBData
docker run -d \
  --name oracle-free-lite \
  -p 1521:1521 \
  -e ORACLE_PWD="$ORACLE_PWD" \
  -v OracleDBData:/opt/oracle/oradata \
  container-registry.oracle.com/database/free:latest-lite
docker logs -f oracle-free-lite
```

Oracle AI Database Free creates the `FREEPDB1` pluggable-database service on the
default listener port. Connect to that PDB, not the `FREE` root service. The Oracle
documentation describes the supported Easy Connect form as `host[:port]/service_name`.

Oracle Free is for local development and testing, not a patched production edition.
Use a supported, patched Oracle production edition and apply its maintenance policy
for production deployments.

## Optional Oracle integration test

Run this only against a disposable schema or a dedicated disposable database. The
test applies/validates migrations and creates fixture rows, but it never calls
`Flyway.clean()` and must not be pointed at a shared schema.

```bash
ORACLE_TEST_URL='jdbc:oracle:thin:@//localhost:1521/FREEPDB1' \
ORACLE_TEST_USERNAME='metabion' \
ORACLE_TEST_PASSWORD='change-me' \
./gradlew test --tests com.metabion.integration.OracleDatabaseIT
```

Create the dedicated test schema manually in `FREEPDB1` (or provision a fresh PDB)
before running the test, and discard it manually after the run. For example, while
connected as an administrator to `FREEPDB1`, create a separately named account and
grant only the schema-creation privileges required by the migrations and a quota on
its tablespace. Do not reuse the Oracle development schema or any shared team schema.
The integration test is disabled unless `ORACLE_TEST_URL` matches
`jdbc:oracle:thin:.*`; no Oracle connection is attempted otherwise.

For Oracle's installation and connection details, see [Installing Oracle AI Database
Free](https://docs.oracle.com/en/database/oracle/oracle-database/26/xeinl/installing-oracle-database-free.html)
and [Connecting to Oracle AI Database
Free](https://docs.oracle.com/en/database/oracle/oracle-database/26/xeinl/connecting-oracle-database-free.html).
