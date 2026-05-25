# Phase 02 — Domain & Persistence

**Goal:** All JPA entities and their repositories exist, Hibernate validates them against the Flyway schema, and slice tests pass.

**Exit criteria**
- `./gradlew bootRun` still boots (Hibernate `validate` mode confirms the entities match V1–V3).
- `@DataJpaTest` slice tests in `UserRepositoryTest` pass.

---

## 1. `com.metabion.domain.User`

```java
@Entity
@Table(name = "users")
public class User {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String email;                                  // store lowercased + trimmed

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(nullable = false)
    private boolean enabled = false;

    @Column(name = "failed_login_attempts", nullable = false)
    private int failedLoginAttempts = 0;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    @Column(name = "mfa_enabled", nullable = false)
    private boolean mfaEnabled = false;

    @Column(name = "mfa_secret_encrypted")
    private byte[] mfaSecretEncrypted;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @OneToMany(mappedBy = "user", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    private Set<UserRole> roles = new HashSet<>();

    @PreUpdate void touch() { this.updatedAt = Instant.now(); }

    public boolean isLocked() {
        return lockedUntil != null && lockedUntil.isAfter(Instant.now());
    }

    public void addRole(String role) {
        this.roles.add(new UserRole(this, role));
    }

    public List<String> roleNames() {
        return roles.stream().map(UserRole::getRole).sorted().toList();
    }

    // getters / setters omitted
}
```

`roles` is fetched eager because the login flow always needs them, and the set is small (≤ 3 roles per user).

## 2. `com.metabion.domain.UserRole` + embedded key

The composite key uses `@EmbeddedId` with **both fields in the key**, and the entity exposes a relationship back to `User` mapped via `@MapsId("userId")`. The `role` column is a basic mapping — *not* `@MapsId`.

```java
@Embeddable
public class UserRoleKey implements Serializable {
    @Column(name = "user_id") private Long userId;
    @Column(name = "role")    private String role;

    public UserRoleKey() {}
    public UserRoleKey(Long userId, String role) {
        this.userId = userId;
        this.role = role;
    }

    @Override public boolean equals(Object o) { /* userId + role */ }
    @Override public int hashCode() { return Objects.hash(userId, role); }
}

@Entity
@Table(name = "user_roles")
public class UserRole {

    @EmbeddedId
    private UserRoleKey id;

    @MapsId("userId")
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    public UserRole() {}
    public UserRole(User user, String role) {
        this.user = user;
        this.id = new UserRoleKey(user.getId(), role);   // user.getId() may be null pre-persist;
                                                         //   @MapsId will populate it on flush
    }

    public String getRole() { return id.getRole(); }
    public User getUser()   { return user; }
}
```

If the parent `User` is transient when `addRole` is called, defer constructing the key with `new UserRoleKey(null, role)` and let `@MapsId("userId")` set it during flush. Alternatively persist the user first, then `user.addRole(...)`; phase 04 does the latter.

## 3. `com.metabion.domain.AccountVerification` and `PasswordReset`

Identical shape; two classes for clarity. Both extend a small `@MappedSuperclass` to share fields.

```java
@MappedSuperclass
abstract class HashedToken {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "token_hash", nullable = false, length = 64, unique = true)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public boolean isExpired() { return Instant.now().isAfter(expiresAt); }
    public boolean isConsumed() { return consumedAt != null; }
    public void consume() { this.consumedAt = Instant.now(); }
    // getters / setters
}

@Entity @Table(name = "account_verification_tokens")
public class AccountVerification extends HashedToken {}

@Entity @Table(name = "password_reset_tokens")
public class PasswordReset extends HashedToken {}
```

## 4. Repositories

```java
public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByEmail(String email);
    boolean existsByEmail(String email);
}

public interface VerificationTokenRepository extends JpaRepository<AccountVerification, Long> {
    Optional<AccountVerification> findByTokenHash(String tokenHash);

    @Modifying
    @Query("update AccountVerification t set t.consumedAt = :now " +
           "where t.user.id = :userId and t.consumedAt is null")
    int markAllConsumedForUser(@Param("userId") Long userId, @Param("now") Instant now);
}

public interface PasswordResetRepository extends JpaRepository<PasswordReset, Long> {
    Optional<PasswordReset> findByTokenHash(String tokenHash);

    @Modifying
    @Query("update PasswordReset t set t.consumedAt = :now " +
           "where t.user.id = :userId and t.consumedAt is null")
    int markAllConsumedForUser(@Param("userId") Long userId, @Param("now") Instant now);
}
```

`markAllConsumedForUser` replaces the original "delete and re-insert" approach (which was both racy and audit-hostile). New tokens are always inserted after the old ones are consumed in a transaction.

No `UserRoleRepository` is needed — roles are managed through `User`'s `@OneToMany`.

---

## 5. Slice test — `UserRepositoryTest`

```java
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class UserRepositoryTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", postgres::getJdbcUrl);
        r.add("spring.datasource.username", postgres::getUsername);
        r.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired UserRepository users;

    @Test void unique_email_constraint() {
        users.save(buildUser("a@x.com"));
        assertThatThrownBy(() -> users.saveAndFlush(buildUser("a@x.com")))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test void findByEmail_returns_user_with_roles() {
        var u = buildUser("b@x.com");
        u.addRole("PATIENT");
        users.save(u);

        var loaded = users.findByEmail("b@x.com").orElseThrow();
        assertThat(loaded.roleNames()).containsExactly("PATIENT");
    }
}
```

The Testcontainers Postgres uses **the real Flyway migrations**. H2 is reserved for fast slice tests that don't exercise Postgres-specific features; for these repositories, the schema is the contract.

---

## 6. Tasks in order

1. Create `domain/` and `repository/` packages under `src/main/java/com/metabion/`.
2. Add `User`, `UserRoleKey`, `UserRole`, `HashedToken`, `AccountVerification`, `PasswordReset`.
3. Add the four repositories.
4. Run `./gradlew bootRun` — Hibernate should not log any validation errors.
5. Write `UserRepositoryTest` and confirm green.

Out of scope: services, controllers, security config.
