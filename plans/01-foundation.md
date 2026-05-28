# Phase 01 — Foundation

**Goal:** App boots against Postgres, Flyway runs cleanly, all required libraries are on the classpath. No business logic yet.

**Exit criteria**
- `./gradlew bootRun` starts without errors against a local Postgres (`docker compose up postgres` or equivalent).
- `flyway info` shows the baseline migration applied.
- `./gradlew test` passes with the existing scaffold.

---

## 1. `build.gradle` — dependencies

```gradle
dependencies {
    // Existing
    implementation 'org.springframework.boot:spring-boot-starter-web'

    // Security & validation
    implementation 'org.springframework.boot:spring-boot-starter-security'
    implementation 'org.springframework.boot:spring-boot-starter-validation'

    // Persistence
    implementation 'org.springframework.boot:spring-boot-starter-data-jpa'
    implementation 'org.postgresql:postgresql:42.7.4'
    implementation 'org.flywaydb:flyway-core:11.14.0'
    implementation 'org.flywaydb:flyway-database-postgresql:11.14.0'

    // Session — JDBC-backed, schema owned by Flyway
    implementation 'org.springframework.session:spring-session-jdbc'

    // Mail
    implementation 'org.springframework.boot:spring-boot-starter-mail'

    // Rate limiting — in-process (no JCache provider needed)
    implementation 'com.bucket4j:bucket4j-core:8.10.1'

    // Test
    testImplementation 'org.springframework.boot:spring-boot-starter-test'
    testImplementation 'org.springframework.security:spring-security-test'
    testImplementation 'com.h2database:h2:2.4.240'
    testImplementation 'org.testcontainers:postgresql:1.21.4'
    testImplementation 'org.testcontainers:junit-jupiter:1.21.4'
    testImplementation 'com.icegreen:greenmail:2.1.11'
    testRuntimeOnly  'org.junit.platform:junit-platform-launcher'
}
```

**Why `bucket4j-core` and not `bucket4j-jcache`:** in-process limiting is enough for a single-node deployment and avoids the extra JCache provider dependency. Switch to `bucket4j-hazelcast` or `bucket4j-redis` if/when we scale horizontally.

---

## 2. `application.properties`

```properties
# --- Datasource ---
spring.datasource.url=${DB_URL:jdbc:postgresql://localhost:5432/metabion}
spring.datasource.username=${DB_USERNAME:metabion}
spring.datasource.password=${DB_PASSWORD:changeme}
spring.datasource.driver-class-name=org.postgresql.Driver

# --- JPA ---
spring.jpa.hibernate.ddl-auto=validate
spring.jpa.show-sql=false
spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect
spring.jpa.open-in-view=false

# --- Flyway ---
spring.flyway.enabled=true
spring.flyway.locations=classpath:db/migration
spring.flyway.validate-on-migrate=true

# --- Spring Session JDBC (schema is Flyway-managed, not auto-initialized) ---
spring.session.store-type=jdbc
spring.session.jdbc.initialize-schema=never
spring.session.jdbc.table-name=SPRING_SESSION
spring.session.timeout=30m

# --- Cookie hardening ---
server.servlet.session.cookie.http-only=true
server.servlet.session.cookie.same-site=Strict
# Set via the prod profile, not here, so tests on http://localhost work:
# server.servlet.session.cookie.secure=true

# --- Mail (env-driven) ---
spring.mail.host=${MAIL_HOST:localhost}
spring.mail.port=${MAIL_PORT:587}
spring.mail.username=${MAIL_USERNAME:}
spring.mail.password=${MAIL_PASSWORD:}
spring.mail.properties.mail.smtp.auth=true
spring.mail.properties.mail.smtp.starttls.enable=true

# --- App ---
app.base-url=${APP_BASE_URL:http://localhost:8080}

# --- Logging ---
logging.level.com.metabion=INFO
logging.level.org.springframework.security=INFO
```

`application-prod.properties` overrides `server.servlet.session.cookie.secure=true`.

---

## 3. Flyway baseline — `V1__init_users.sql`

```sql
CREATE TABLE users (
    id                     BIGSERIAL PRIMARY KEY,
    email                  VARCHAR(255) NOT NULL UNIQUE,
    password_hash          VARCHAR(255) NOT NULL,
    enabled                BOOLEAN NOT NULL DEFAULT FALSE,
    failed_login_attempts  INT NOT NULL DEFAULT 0,
    locked_until           TIMESTAMP WITH TIME ZONE,
    mfa_enabled            BOOLEAN NOT NULL DEFAULT FALSE,
    mfa_secret_encrypted   BYTEA,           -- AES-GCM ciphertext; null when MFA disabled
    created_at             TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at             TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE TABLE user_roles (
    user_id  BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role     VARCHAR(50) NOT NULL,
    PRIMARY KEY (user_id, role)
);
-- Roles: PATIENT, EXPERT, ADMIN.
```

Note: `mfa_secret_encrypted` is `BYTEA` for AES-GCM ciphertext (nonce + tag prepended). It is **encrypted**, not hashed — TOTP requires the secret to be retrievable.

## 4. Flyway — `V2__verification_and_reset_tokens.sql`

```sql
CREATE TABLE account_verification_tokens (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash  CHAR(64) NOT NULL,                     -- SHA-256 hex of the 32-byte token
    expires_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    consumed_at TIMESTAMP WITH TIME ZONE,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_avt_user_id    ON account_verification_tokens(user_id);
CREATE UNIQUE INDEX uq_avt_token_hash ON account_verification_tokens(token_hash);

CREATE TABLE password_reset_tokens (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash  CHAR(64) NOT NULL,
    expires_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    consumed_at TIMESTAMP WITH TIME ZONE,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_prt_user_id    ON password_reset_tokens(user_id);
CREATE UNIQUE INDEX uq_prt_token_hash ON password_reset_tokens(token_hash);
```

`token_hash` has a **unique** index — collision is astronomically unlikely with 32 SecureRandom bytes, and the unique constraint is a defensive belt.

## 5. Flyway — `V3__spring_session.sql`

Adapted from the official Spring Session JDBC schema (Postgres dialect):

```sql
CREATE TABLE SPRING_SESSION (
    PRIMARY_ID            CHAR(36) NOT NULL,
    SESSION_ID            CHAR(36) NOT NULL,
    CREATION_TIME         BIGINT   NOT NULL,
    LAST_ACCESS_TIME      BIGINT   NOT NULL,
    MAX_INACTIVE_INTERVAL INT      NOT NULL,
    EXPIRY_TIME           BIGINT   NOT NULL,
    PRINCIPAL_NAME        VARCHAR(100),
    CONSTRAINT SPRING_SESSION_PK PRIMARY KEY (PRIMARY_ID)
);
CREATE UNIQUE INDEX SPRING_SESSION_IX1 ON SPRING_SESSION (SESSION_ID);
CREATE        INDEX SPRING_SESSION_IX2 ON SPRING_SESSION (EXPIRY_TIME);
CREATE        INDEX SPRING_SESSION_IX3 ON SPRING_SESSION (PRINCIPAL_NAME);

CREATE TABLE SPRING_SESSION_ATTRIBUTES (
    SESSION_PRIMARY_ID CHAR(36)     NOT NULL,
    ATTRIBUTE_NAME     VARCHAR(200) NOT NULL,
    ATTRIBUTE_BYTES    BYTEA        NOT NULL,
    CONSTRAINT SPRING_SESSION_ATTRIBUTES_PK PRIMARY KEY (SESSION_PRIMARY_ID, ATTRIBUTE_NAME),
    CONSTRAINT SPRING_SESSION_ATTRIBUTES_FK
        FOREIGN KEY (SESSION_PRIMARY_ID) REFERENCES SPRING_SESSION(PRIMARY_ID) ON DELETE CASCADE
);
```

This is V3 so the MFA-encrypted column (V1) and the auth tokens (V2) are in place before sessions exist. The PRINCIPAL_NAME index is what phase 06 will use to invalidate a user's sessions on password reset.

---

## 6. Tasks in order

1. Edit `build.gradle`, run `./gradlew build` to refresh dependencies.
2. Write `src/main/resources/application.properties`, plus a stub `application-prod.properties`.
3. Create `src/main/resources/db/migration/V1__init_users.sql`, `V2__verification_and_reset_tokens.sql`, `V3__spring_session.sql`.
4. Run `./gradlew bootRun` against a local Postgres; confirm Flyway logs `Successfully applied 3 migrations`.
5. Stop the app. Phase 01 done.

## 7. Smoke checks

- `psql metabion -c "\dt"` shows `users`, `user_roles`, `account_verification_tokens`, `password_reset_tokens`, `spring_session`, `spring_session_attributes`.
- `psql metabion -c "select * from flyway_schema_history"` shows three applied versions.

Out of scope for this phase: any Java domain code, any controllers, any tests beyond the existing scaffold.
