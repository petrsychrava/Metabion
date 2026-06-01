# Staff Invitation Provisioning Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add admin-created staff invitations that provision nutrition specialist, physician, and coordinator accounts through secure invitation acceptance.

**Architecture:** Keep patient self-registration unchanged. Store pending staff invitations separately from `users`, hash invitation tokens, create the enabled staff `User` and `StaffProfile` only after acceptance, and expose both REST and MVC flows through the same service. Security remains session-based with CSRF enabled for MVC and exact-path CSRF bypass only for public REST acceptance.

**Tech Stack:** Java 25, Spring Boot 4.0.6, Spring Security, Spring MVC, Spring Data JPA, Flyway, PostgreSQL/H2 tests, Thymeleaf, JUnit 5, Spring Security Test.

---

## File Structure

Create:

- `src/main/resources/db/migration/V6__staff_invitations.sql`: Flyway schema for invitation and invitation-role tables.
- `src/main/java/com/metabion/domain/StaffInvitation.java`: JPA aggregate root for pending/accepted/revoked staff invitations.
- `src/main/java/com/metabion/domain/StaffInvitationRole.java`: JPA child entity for invited staff roles.
- `src/main/java/com/metabion/domain/StaffInvitationRoleKey.java`: composite key for invitation roles.
- `src/main/java/com/metabion/repository/StaffInvitationRepository.java`: invitation lookup and active-invite revocation queries.
- `src/main/java/com/metabion/dto/CreateStaffInvitationRequest.java`: REST/MVC request DTO for admin invite creation.
- `src/main/java/com/metabion/dto/AcceptStaffInvitationRequest.java`: REST/MVC request DTO for invite acceptance.
- `src/main/java/com/metabion/dto/StaffInvitationResponse.java`: REST response DTO for invite creation/acceptance status.
- `src/main/java/com/metabion/exception/StaffInvitationException.java`: admin-facing and public-facing invitation failure messages.
- `src/main/java/com/metabion/service/StaffInvitationService.java`: invitation business rules, token handling, user creation.
- `src/main/java/com/metabion/controller/StaffInvitationController.java`: REST endpoints.
- `src/main/java/com/metabion/controller/StaffInvitationWebController.java`: MVC endpoints and form rendering.
- `src/main/resources/templates/admin-staff-invitation.html`: admin invite form.
- `src/main/resources/templates/staff-invitation-accept.html`: public password setup form.
- `src/test/java/com/metabion/repository/StaffInvitationRepositoryTest.java`: persistence coverage.
- `src/test/java/com/metabion/service/StaffInvitationServiceTest.java`: service rule coverage.
- `src/test/java/com/metabion/controller/StaffInvitationControllerTest.java`: REST/security coverage.
- `src/test/java/com/metabion/controller/StaffInvitationWebControllerTest.java`: MVC/security coverage.

Modify:

- `src/main/java/com/metabion/service/EmailService.java`: add `sendStaffInvitation`.
- `src/main/java/com/metabion/service/SmtpEmailService.java`: send real staff invitation link.
- `src/main/java/com/metabion/service/LoggingEmailService.java`: log redacted staff invitation link.
- `src/main/java/com/metabion/config/SecurityConfig.java`: authorize admin/public invitation routes and CSRF behavior.
- `src/test/java/com/metabion/service/SmtpEmailServiceTest.java`: verify email content/link.
- `src/test/java/com/metabion/service/LoggingEmailServiceTest.java`: verify redaction.
- `src/test/java/com/metabion/config/SecurityConfigTest.java`: verify route access and CSRF behavior.
- `src/test/java/com/metabion/controller/WebAuthTemplateTest.java` or `ThymeleafAvailabilityTest.java`: include new templates if current test enumerates template names.

Do not modify:

- Patient registration behavior in `UserService.register`.
- Patient verification/reset behavior.
- Existing RBAC assignment tables or access-control semantics.

## Implementation Tasks

### Task 1: Add Staff Invitation Persistence

**Files:**
- Create: `src/main/resources/db/migration/V6__staff_invitations.sql`
- Create: `src/main/java/com/metabion/domain/StaffInvitationRoleKey.java`
- Create: `src/main/java/com/metabion/domain/StaffInvitationRole.java`
- Create: `src/main/java/com/metabion/domain/StaffInvitation.java`
- Create: `src/main/java/com/metabion/repository/StaffInvitationRepository.java`
- Test: `src/test/java/com/metabion/repository/StaffInvitationRepositoryTest.java`

- [ ] **Step 1: Write the failing repository tests**

Create `src/test/java/com/metabion/repository/StaffInvitationRepositoryTest.java`:

```java
package com.metabion.repository;

import com.metabion.domain.RoleName;
import com.metabion.domain.StaffInvitation;
import com.metabion.domain.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@ActiveProfiles("test")
class StaffInvitationRepositoryTest {

    @Autowired
    StaffInvitationRepository invitations;

    @Autowired
    UserRepository users;

    @Test
    void savesInvitationWithAllowedStaffRoles() {
        var inviter = admin("admin@example.com");
        var invitation = invitation("expert@example.com", "a".repeat(64), inviter);
        invitation.addRole(RoleName.PHYSICIAN);
        invitation.addRole(RoleName.COORDINATOR);

        var saved = invitations.saveAndFlush(invitation);

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.roles()).containsExactlyInAnyOrder(RoleName.PHYSICIAN, RoleName.COORDINATOR);
        assertThat(saved.isActive(Instant.now())).isTrue();
    }

    @Test
    void rejectsUnsupportedInvitationRole() {
        var inviter = admin("admin@example.com");
        var invitation = invitation("expert@example.com", "b".repeat(64), inviter);

        assertThatThrownBy(() -> invitation.addRole(RoleName.PATIENT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Staff invitation role must be a clinical staff role");
    }

    @Test
    void findsInvitationByTokenHash() {
        var inviter = admin("admin@example.com");
        var invitation = invitation("expert@example.com", "c".repeat(64), inviter);
        invitation.addRole(RoleName.NUTRITION_SPECIALIST);
        invitations.saveAndFlush(invitation);

        assertThat(invitations.findByTokenHash("c".repeat(64))).isPresent();
        assertThat(invitations.findByTokenHash("d".repeat(64))).isEmpty();
    }

    @Test
    void preventsTwoUnconsumedInvitationsForSameEmail() {
        var inviter = admin("admin@example.com");
        var first = invitation("expert@example.com", "e".repeat(64), inviter);
        first.addRole(RoleName.PHYSICIAN);
        invitations.saveAndFlush(first);

        var second = invitation("EXPERT@example.com", "f".repeat(64), inviter);
        second.addRole(RoleName.COORDINATOR);

        assertThatThrownBy(() -> invitations.saveAndFlush(second))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void revokedInvitationAllowsReissueForSameEmail() {
        var inviter = admin("admin@example.com");
        var first = invitation("expert@example.com", "g".repeat(64), inviter);
        first.addRole(RoleName.PHYSICIAN);
        first.revoke(Instant.now());
        invitations.saveAndFlush(first);

        var second = invitation("expert@example.com", "h".repeat(64), inviter);
        second.addRole(RoleName.COORDINATOR);

        assertThat(invitations.saveAndFlush(second).getId()).isNotNull();
    }

    @Test
    void repositoryRevokesActiveInvitationsForEmail() {
        var inviter = admin("admin@example.com");
        var first = invitation("expert@example.com", "i".repeat(64), inviter);
        first.addRole(RoleName.PHYSICIAN);
        invitations.saveAndFlush(first);

        var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        assertThat(invitations.revokeActiveForEmail("expert@example.com", now)).isEqualTo(1);

        var reloaded = invitations.findByTokenHash("i".repeat(64)).orElseThrow();
        assertThat(reloaded.getRevokedAt()).isNotNull();
        assertThat(reloaded.isActive(Instant.now())).isFalse();
    }

    private User admin(String email) {
        var user = new User(email, "hash");
        user.setEnabled(true);
        user.addRole(RoleName.ADMIN);
        return users.saveAndFlush(user);
    }

    private StaffInvitation invitation(String email, String tokenHash, User invitedBy) {
        var invitation = new StaffInvitation();
        invitation.setEmail(email);
        invitation.setTokenHash(tokenHash);
        invitation.setInvitedBy(invitedBy);
        invitation.setExpiresAt(Instant.now().plus(7, ChronoUnit.DAYS));
        return invitation;
    }
}
```

- [ ] **Step 2: Run the repository test to verify it fails**

Run:

```bash
./gradlew test --tests com.metabion.repository.StaffInvitationRepositoryTest
```

Expected: compilation fails because `StaffInvitation`, `StaffInvitationRole`, `StaffInvitationRepository`, and the migration do not exist yet.

- [ ] **Step 3: Add the Flyway migration**

Create `src/main/resources/db/migration/V6__staff_invitations.sql`:

```sql
CREATE TABLE staff_invitations (
    id BIGSERIAL PRIMARY KEY,
    email VARCHAR(255) NOT NULL,
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    invited_by_user_id BIGINT REFERENCES users(id) ON DELETE SET NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    accepted_at TIMESTAMPTZ,
    revoked_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_staff_invitations_token_hash_length CHECK (char_length(token_hash) = 64),
    CONSTRAINT chk_staff_invitations_email_lower CHECK (email = lower(trim(email)))
);

CREATE TABLE staff_invitation_roles (
    staff_invitation_id BIGINT NOT NULL REFERENCES staff_invitations(id) ON DELETE CASCADE,
    role VARCHAR(50) NOT NULL,
    PRIMARY KEY (staff_invitation_id, role),
    CONSTRAINT chk_staff_invitation_roles_role CHECK (
        role IN ('NUTRITION_SPECIALIST', 'PHYSICIAN', 'COORDINATOR')
    )
);

CREATE UNIQUE INDEX ux_staff_invitations_pending_email
    ON staff_invitations(email)
    WHERE accepted_at IS NULL AND revoked_at IS NULL;

CREATE INDEX ix_staff_invitations_email ON staff_invitations(email);
CREATE INDEX ix_staff_invitation_roles_role ON staff_invitation_roles(role);
```

Use `expires_at` to represent planned expiration time. Do not add `expired_at`; actual expiration is derived by comparing `expires_at` to the current clock.

- [ ] **Step 4: Add the invitation role key entity**

Create `src/main/java/com/metabion/domain/StaffInvitationRoleKey.java`:

```java
package com.metabion.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.io.Serializable;
import java.util.Objects;

@Embeddable
public class StaffInvitationRoleKey implements Serializable {

    @Column(name = "staff_invitation_id")
    private Long staffInvitationId;

    @Column(name = "role")
    private String role;

    public StaffInvitationRoleKey() {
    }

    public StaffInvitationRoleKey(Long staffInvitationId, RoleName role) {
        this.staffInvitationId = staffInvitationId;
        this.role = role.name();
    }

    public Long getStaffInvitationId() {
        return staffInvitationId;
    }

    public void setStaffInvitationId(Long staffInvitationId) {
        this.staffInvitationId = staffInvitationId;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof StaffInvitationRoleKey that)) {
            return false;
        }
        return Objects.equals(staffInvitationId, that.staffInvitationId)
                && Objects.equals(role, that.role);
    }

    @Override
    public int hashCode() {
        return Objects.hash(staffInvitationId, role);
    }
}
```

- [ ] **Step 5: Add the invitation role entity**

Create `src/main/java/com/metabion/domain/StaffInvitationRole.java`:

```java
package com.metabion.domain;

import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;

@Entity
@Table(name = "staff_invitation_roles")
public class StaffInvitationRole {

    @EmbeddedId
    private StaffInvitationRoleKey id = new StaffInvitationRoleKey();

    @ManyToOne(fetch = FetchType.LAZY)
    @MapsId("staffInvitationId")
    @JoinColumn(name = "staff_invitation_id")
    private StaffInvitation invitation;

    protected StaffInvitationRole() {
    }

    public StaffInvitationRole(StaffInvitation invitation, RoleName role) {
        if (role == null || !role.isClinicalStaff()) {
            throw new IllegalArgumentException("Staff invitation role must be a clinical staff role");
        }
        this.invitation = invitation;
        this.id = new StaffInvitationRoleKey(invitation == null ? null : invitation.getId(), role);
    }

    public StaffInvitationRoleKey getId() {
        return id;
    }

    public StaffInvitation getInvitation() {
        return invitation;
    }

    public String getRole() {
        return id.getRole();
    }
}
```

- [ ] **Step 6: Add the invitation aggregate**

Create `src/main/java/com/metabion/domain/StaffInvitation.java`:

```java
package com.metabion.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.Instant;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

@Entity
@Table(name = "staff_invitations")
public class StaffInvitation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String email;

    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "invited_by_user_id")
    private User invitedBy;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "accepted_at")
    private Instant acceptedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @OneToMany(mappedBy = "invitation", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Set<StaffInvitationRole> roles = new HashSet<>();

    public void addRole(RoleName role) {
        roles.add(new StaffInvitationRole(this, role));
    }

    public Set<RoleName> roles() {
        return roles.stream()
                .map(StaffInvitationRole::getRole)
                .map(RoleName::from)
                .collect(Collectors.toUnmodifiableSet());
    }

    public boolean isExpired(Instant now) {
        return expiresAt != null && !expiresAt.isAfter(now);
    }

    public boolean isAccepted() {
        return acceptedAt != null;
    }

    public boolean isRevoked() {
        return revokedAt != null;
    }

    public boolean isActive(Instant now) {
        return !isAccepted() && !isRevoked() && !isExpired(now);
    }

    public void accept(Instant now) {
        this.acceptedAt = now;
    }

    public void revoke(Instant now) {
        this.revokedAt = now;
    }

    public Long getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public void setTokenHash(String tokenHash) {
        this.tokenHash = tokenHash;
    }

    public User getInvitedBy() {
        return invitedBy;
    }

    public void setInvitedBy(User invitedBy) {
        this.invitedBy = invitedBy;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }

    public Instant getAcceptedAt() {
        return acceptedAt;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
```

- [ ] **Step 7: Add the repository**

Create `src/main/java/com/metabion/repository/StaffInvitationRepository.java`:

```java
package com.metabion.repository;

import com.metabion.domain.StaffInvitation;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface StaffInvitationRepository extends JpaRepository<StaffInvitation, Long> {

    @EntityGraph(attributePaths = "roles")
    Optional<StaffInvitation> findByTokenHash(String tokenHash);

    @EntityGraph(attributePaths = "roles")
    Optional<StaffInvitation> findFirstByEmailAndAcceptedAtIsNullAndRevokedAtIsNull(String email);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update StaffInvitation invitation
               set invitation.revokedAt = :revokedAt
             where invitation.email = :email
               and invitation.acceptedAt is null
               and invitation.revokedAt is null
            """)
    int revokeActiveForEmail(@Param("email") String email, @Param("revokedAt") Instant revokedAt);
}
```

- [ ] **Step 8: Run the repository test**

Run:

```bash
./gradlew test --tests com.metabion.repository.StaffInvitationRepositoryTest
```

Expected: PASS. If H2 rejects the partial unique index syntax, change the repository test to use the project’s existing Testcontainers PostgreSQL pattern instead of weakening the production migration.

- [ ] **Step 9: Commit persistence**

Run:

```bash
git add src/main/resources/db/migration/V6__staff_invitations.sql \
  src/main/java/com/metabion/domain/StaffInvitation.java \
  src/main/java/com/metabion/domain/StaffInvitationRole.java \
  src/main/java/com/metabion/domain/StaffInvitationRoleKey.java \
  src/main/java/com/metabion/repository/StaffInvitationRepository.java \
  src/test/java/com/metabion/repository/StaffInvitationRepositoryTest.java
git commit -m "Add staff invitation persistence"
```

### Task 2: Add Email Support for Staff Invitations

**Files:**
- Modify: `src/main/java/com/metabion/service/EmailService.java`
- Modify: `src/main/java/com/metabion/service/SmtpEmailService.java`
- Modify: `src/main/java/com/metabion/service/LoggingEmailService.java`
- Test: `src/test/java/com/metabion/service/SmtpEmailServiceTest.java`
- Test: `src/test/java/com/metabion/service/LoggingEmailServiceTest.java`

- [ ] **Step 1: Add failing email tests**

In `src/test/java/com/metabion/service/SmtpEmailServiceTest.java`, add a test matching the existing style:

```java
@Test
void sendsStaffInvitationLinkToMvcAcceptRoute() {
    service.sendStaffInvitation("expert@example.com", "plain token");

    var message = sentMessage();
    assertThat(message.getTo()).containsExactly("expert@example.com");
    assertThat(message.getSubject()).isEqualTo("Set up your Metabion staff account");
    assertThat(message.getText())
            .contains("http://localhost:8080/staff-invitations/accept?token=plain+token");
}
```

In `src/test/java/com/metabion/service/LoggingEmailServiceTest.java`, add a test matching the existing log-capture style:

```java
@Test
void staffInvitationLogRedactsToken() {
    service.sendStaffInvitation("expert@example.com", "plain-token");

    assertThat(output)
            .contains("/staff-invitations/accept?token=<redacted>")
            .doesNotContain("plain-token");
}
```

Before adding these assertions, open the existing email test files and use their current helper names. In this repository today, use `sentMessage()` for the SMTP test if that helper already exists; if the existing helper has a different name, rename the assertion call to that existing helper name without changing the assertion values above.

- [ ] **Step 2: Run email tests to verify failure**

Run:

```bash
./gradlew test --tests com.metabion.service.SmtpEmailServiceTest --tests com.metabion.service.LoggingEmailServiceTest
```

Expected: compilation fails because `sendStaffInvitation` is not defined.

- [ ] **Step 3: Extend the email interface**

Modify `src/main/java/com/metabion/service/EmailService.java` to include:

```java
void sendStaffInvitation(String to, String token);
```

- [ ] **Step 4: Implement SMTP staff invitation email**

Add to `src/main/java/com/metabion/service/SmtpEmailService.java`:

```java
@Override
public void sendStaffInvitation(String to, String token) {
    var msg = new SimpleMailMessage();
    msg.setTo(to);
    msg.setSubject("Set up your Metabion staff account");
    msg.setText("Click to set up your staff account (link expires in 7 days):\n\n" +
                baseUrl + "/staff-invitations/accept?token=" +
                URLEncoder.encode(token, StandardCharsets.UTF_8));
    mail.send(msg);
}
```

- [ ] **Step 5: Implement redacted dev logging**

Add to `src/main/java/com/metabion/service/LoggingEmailService.java`:

```java
@Override
public void sendStaffInvitation(String to, String token) {
    log.info("[DEV] Staff invitation email would be sent to {} with link {}", to,
            baseUrl + "/staff-invitations/accept?token=<redacted>");
}
```

- [ ] **Step 6: Run email tests**

Run:

```bash
./gradlew test --tests com.metabion.service.SmtpEmailServiceTest --tests com.metabion.service.LoggingEmailServiceTest
```

Expected: PASS.

- [ ] **Step 7: Commit email support**

Run:

```bash
git add src/main/java/com/metabion/service/EmailService.java \
  src/main/java/com/metabion/service/SmtpEmailService.java \
  src/main/java/com/metabion/service/LoggingEmailService.java \
  src/test/java/com/metabion/service/SmtpEmailServiceTest.java \
  src/test/java/com/metabion/service/LoggingEmailServiceTest.java
git commit -m "Add staff invitation email support"
```

### Task 3: Implement Staff Invitation Service

**Files:**
- Create: `src/main/java/com/metabion/dto/CreateStaffInvitationRequest.java`
- Create: `src/main/java/com/metabion/dto/AcceptStaffInvitationRequest.java`
- Create: `src/main/java/com/metabion/dto/StaffInvitationResponse.java`
- Create: `src/main/java/com/metabion/exception/StaffInvitationException.java`
- Create: `src/main/java/com/metabion/service/StaffInvitationService.java`
- Test: `src/test/java/com/metabion/service/StaffInvitationServiceTest.java`

- [ ] **Step 1: Write failing service tests**

Create `src/test/java/com/metabion/service/StaffInvitationServiceTest.java`:

```java
package com.metabion.service;

import com.metabion.domain.RoleName;
import com.metabion.domain.StaffProfile;
import com.metabion.domain.User;
import com.metabion.dto.AcceptStaffInvitationRequest;
import com.metabion.dto.CreateStaffInvitationRequest;
import com.metabion.exception.StaffInvitationException;
import com.metabion.repository.StaffInvitationRepository;
import com.metabion.repository.StaffProfileRepository;
import com.metabion.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class StaffInvitationServiceTest {

    @Autowired
    StaffInvitationService service;

    @Autowired
    StaffInvitationRepository invitations;

    @Autowired
    UserRepository users;

    @Autowired
    StaffProfileRepository staffProfiles;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    TestEmailService emailService;

    @Test
    void adminCreatesInvitationForNewEmail() {
        admin("admin@example.com");

        service.createInvitation("admin@example.com",
                new CreateStaffInvitationRequest("Expert@Example.com", Set.of("PHYSICIAN", "COORDINATOR")));

        var invitation = invitations.findFirstByEmailAndAcceptedAtIsNullAndRevokedAtIsNull("expert@example.com")
                .orElseThrow();
        assertThat(invitation.roles()).containsExactlyInAnyOrder(RoleName.PHYSICIAN, RoleName.COORDINATOR);
        assertThat(invitation.getTokenHash()).hasSize(64);
        assertThat(emailService.lastStaffInvitationTo()).isEqualTo("expert@example.com");
        assertThat(emailService.lastStaffInvitationToken()).isNotBlank();
        assertThat(invitation.getTokenHash()).isNotEqualTo(emailService.lastStaffInvitationToken());
    }

    @Test
    void rejectsEmptyRoles() {
        admin("admin@example.com");

        assertThatThrownBy(() -> service.createInvitation("admin@example.com",
                new CreateStaffInvitationRequest("expert@example.com", Set.of())))
                .isInstanceOf(StaffInvitationException.class)
                .hasMessage("Select at least one staff role.");
    }

    @Test
    void rejectsPatientAndAdminRoles() {
        admin("admin@example.com");

        assertThatThrownBy(() -> service.createInvitation("admin@example.com",
                new CreateStaffInvitationRequest("expert@example.com", Set.of("PATIENT"))))
                .isInstanceOf(StaffInvitationException.class)
                .hasMessage("Only nutrition specialist, physician, and coordinator roles can be invited.");

        assertThatThrownBy(() -> service.createInvitation("admin@example.com",
                new CreateStaffInvitationRequest("expert@example.com", Set.of("ADMIN"))))
                .isInstanceOf(StaffInvitationException.class)
                .hasMessage("Only nutrition specialist, physician, and coordinator roles can be invited.");
    }

    @Test
    void rejectsExistingPatientEmail() {
        admin("admin@example.com");
        patient("person@example.com", true);

        assertThatThrownBy(() -> service.createInvitation("admin@example.com",
                new CreateStaffInvitationRequest("person@example.com", Set.of("PHYSICIAN"))))
                .isInstanceOf(StaffInvitationException.class)
                .hasMessage("This email is already registered as a patient. Staff access requires a separate account.");
    }

    @Test
    void rejectsExistingStaffOrAdminEmail() {
        admin("admin@example.com");
        staff("staff@example.com", RoleName.PHYSICIAN);

        assertThatThrownBy(() -> service.createInvitation("admin@example.com",
                new CreateStaffInvitationRequest("staff@example.com", Set.of("COORDINATOR"))))
                .isInstanceOf(StaffInvitationException.class)
                .hasMessage("This email already has staff access.");

        assertThatThrownBy(() -> service.createInvitation("admin@example.com",
                new CreateStaffInvitationRequest("admin@example.com", Set.of("COORDINATOR"))))
                .isInstanceOf(StaffInvitationException.class)
                .hasMessage("This email already has staff access.");
    }

    @Test
    void rejectsInactiveExistingUser() {
        admin("admin@example.com");
        patient("inactive@example.com", false);

        assertThatThrownBy(() -> service.createInvitation("admin@example.com",
                new CreateStaffInvitationRequest("inactive@example.com", Set.of("PHYSICIAN"))))
                .isInstanceOf(StaffInvitationException.class)
                .hasMessage("This email belongs to an inactive account and requires manual resolution.");
    }

    @Test
    void reissueRevokesPreviousPendingInvitation() {
        admin("admin@example.com");
        service.createInvitation("admin@example.com",
                new CreateStaffInvitationRequest("expert@example.com", Set.of("PHYSICIAN")));
        var firstToken = emailService.lastStaffInvitationToken();

        service.createInvitation("admin@example.com",
                new CreateStaffInvitationRequest("expert@example.com", Set.of("COORDINATOR")));

        var first = invitations.findByTokenHash(UserService.sha256Hex(firstToken)).orElseThrow();
        var active = invitations.findFirstByEmailAndAcceptedAtIsNullAndRevokedAtIsNull("expert@example.com")
                .orElseThrow();
        assertThat(first.getRevokedAt()).isNotNull();
        assertThat(active.roles()).containsExactly(RoleName.COORDINATOR);
    }

    @Test
    void acceptValidInvitationCreatesEnabledStaffUser() {
        admin("admin@example.com");
        service.createInvitation("admin@example.com",
                new CreateStaffInvitationRequest("expert@example.com", Set.of("PHYSICIAN", "COORDINATOR")));
        var token = emailService.lastStaffInvitationToken();

        service.acceptInvitation(new AcceptStaffInvitationRequest(token, "correct horse battery staple"));

        var user = users.findByEmail("expert@example.com").orElseThrow();
        assertThat(user.isEnabled()).isTrue();
        assertThat(user.roleNames()).containsExactlyInAnyOrder("PHYSICIAN", "COORDINATOR");
        assertThat(passwordEncoder.matches("correct horse battery staple", user.getPasswordHash())).isTrue();
        assertThat(staffProfiles.findByUserId(user.getId())).isPresent();
        assertThat(invitations.findByTokenHash(UserService.sha256Hex(token)).orElseThrow().getAcceptedAt()).isNotNull();
    }

    @Test
    void invalidOrConsumedInvitationAcceptanceFailsWithPublicMessage() {
        assertThatThrownBy(() -> service.acceptInvitation(new AcceptStaffInvitationRequest("missing", "password123")))
                .isInstanceOf(StaffInvitationException.class)
                .hasMessage("This invitation link is invalid or expired.");

        admin("admin@example.com");
        service.createInvitation("admin@example.com",
                new CreateStaffInvitationRequest("expert@example.com", Set.of("PHYSICIAN")));
        var token = emailService.lastStaffInvitationToken();
        service.acceptInvitation(new AcceptStaffInvitationRequest(token, "password123"));

        assertThatThrownBy(() -> service.acceptInvitation(new AcceptStaffInvitationRequest(token, "password123")))
                .isInstanceOf(StaffInvitationException.class)
                .hasMessage("This invitation link is invalid or expired.");
    }

    @Test
    void rejectsPasswordLongerThanBcryptLimit() {
        admin("admin@example.com");
        service.createInvitation("admin@example.com",
                new CreateStaffInvitationRequest("expert@example.com", Set.of("PHYSICIAN")));

        assertThatThrownBy(() -> service.acceptInvitation(new AcceptStaffInvitationRequest(
                emailService.lastStaffInvitationToken(), "a".repeat(73))))
                .isInstanceOf(StaffInvitationException.class)
                .hasMessage("password exceeds 72 bytes");
    }

    private User admin(String email) {
        var user = new User(email, passwordEncoder.encode("password"));
        user.setEnabled(true);
        user.addRole(RoleName.ADMIN);
        return users.saveAndFlush(user);
    }

    private User patient(String email, boolean enabled) {
        var user = new User(email, passwordEncoder.encode("password"));
        user.setEnabled(enabled);
        user.addRole(RoleName.PATIENT);
        return users.saveAndFlush(user);
    }

    private User staff(String email, RoleName role) {
        var user = new User(email, passwordEncoder.encode("password"));
        user.setEnabled(true);
        user.addRole(role);
        var saved = users.saveAndFlush(user);
        staffProfiles.saveAndFlush(new StaffProfile(saved));
        return saved;
    }
}
```

If `TestEmailService` does not exist, add a test-profile bean in this test file using `@TestConfiguration`:

```java
@TestConfiguration
static class EmailConfig {
    @Bean
    TestEmailService testEmailService() {
        return new TestEmailService();
    }
}

static class TestEmailService implements EmailService {
    private String staffInvitationTo;
    private String staffInvitationToken;

    @Override
    public void sendVerification(String to, String token) {
    }

    @Override
    public void sendPasswordReset(String to, String token) {
    }

    @Override
    public void sendStaffInvitation(String to, String token) {
        this.staffInvitationTo = to;
        this.staffInvitationToken = token;
    }

    String lastStaffInvitationTo() {
        return staffInvitationTo;
    }

    String lastStaffInvitationToken() {
        return staffInvitationToken;
    }
}
```

- [ ] **Step 2: Run service test to verify failure**

Run:

```bash
./gradlew test --tests com.metabion.service.StaffInvitationServiceTest
```

Expected: compilation fails because DTOs, exception, and service do not exist.

- [ ] **Step 3: Add DTOs**

Create `src/main/java/com/metabion/dto/CreateStaffInvitationRequest.java`:

```java
package com.metabion.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.Set;

public record CreateStaffInvitationRequest(
        @NotBlank @Email String email,
        @NotEmpty Set<@NotBlank String> roles
) {
}
```

Create `src/main/java/com/metabion/dto/AcceptStaffInvitationRequest.java`:

```java
package com.metabion.dto;

import jakarta.validation.constraints.NotBlank;

public record AcceptStaffInvitationRequest(
        @NotBlank String token,
        @NotBlank String password
) {
}
```

Create `src/main/java/com/metabion/dto/StaffInvitationResponse.java`:

```java
package com.metabion.dto;

public record StaffInvitationResponse(String status) {
}
```

- [ ] **Step 4: Add invitation exception**

Create `src/main/java/com/metabion/exception/StaffInvitationException.java`:

```java
package com.metabion.exception;

public class StaffInvitationException extends RuntimeException {

    public StaffInvitationException(String message) {
        super(message);
    }

    public static StaffInvitationException invalidOrExpired() {
        return new StaffInvitationException("This invitation link is invalid or expired.");
    }

    public static StaffInvitationException completionConflict() {
        return new StaffInvitationException("This invitation cannot be completed. Contact an administrator.");
    }
}
```

- [ ] **Step 5: Implement the service**

Create `src/main/java/com/metabion/service/StaffInvitationService.java`:

```java
package com.metabion.service;

import com.metabion.domain.RoleName;
import com.metabion.domain.StaffInvitation;
import com.metabion.domain.StaffProfile;
import com.metabion.domain.User;
import com.metabion.dto.AcceptStaffInvitationRequest;
import com.metabion.dto.CreateStaffInvitationRequest;
import com.metabion.exception.StaffInvitationException;
import com.metabion.repository.StaffInvitationRepository;
import com.metabion.repository.StaffProfileRepository;
import com.metabion.repository.UserRepository;
import jakarta.transaction.Transactional;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.Set;

@Service
@Transactional
public class StaffInvitationService {

    private static final Duration INVITATION_TTL = Duration.ofDays(7);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String STAFF_ROLE_ERROR =
            "Only nutrition specialist, physician, and coordinator roles can be invited.";

    private final StaffInvitationRepository invitations;
    private final UserRepository users;
    private final StaffProfileRepository staffProfiles;
    private final PasswordEncoder passwordEncoder;
    private final EmailService emailService;

    public StaffInvitationService(StaffInvitationRepository invitations,
                                  UserRepository users,
                                  StaffProfileRepository staffProfiles,
                                  PasswordEncoder passwordEncoder,
                                  EmailService emailService) {
        this.invitations = invitations;
        this.users = users;
        this.staffProfiles = staffProfiles;
        this.passwordEncoder = passwordEncoder;
        this.emailService = emailService;
    }

    public void createInvitation(String invitedByEmail, CreateStaffInvitationRequest request) {
        var email = UserService.normalize(request.email());
        var roles = parseRoles(request.roles());
        var invitedBy = users.findByEmail(UserService.normalize(invitedByEmail))
                .orElseThrow(() -> new StaffInvitationException("Inviting administrator was not found."));

        validateEmailAvailableForStaff(email);

        var now = Instant.now();
        invitations.revokeActiveForEmail(email, now);

        var plainToken = generateToken();
        var invitation = new StaffInvitation();
        invitation.setEmail(email);
        invitation.setTokenHash(UserService.sha256Hex(plainToken));
        invitation.setInvitedBy(invitedBy);
        invitation.setExpiresAt(now.plus(INVITATION_TTL));
        roles.forEach(invitation::addRole);
        invitations.save(invitation);

        emailService.sendStaffInvitation(email, plainToken);
    }

    public void acceptInvitation(AcceptStaffInvitationRequest request) {
        if (request.password().getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new StaffInvitationException("password exceeds 72 bytes");
        }

        var invitation = invitations.findByTokenHash(UserService.sha256Hex(request.token()))
                .orElseThrow(StaffInvitationException::invalidOrExpired);
        var now = Instant.now();
        if (!invitation.isActive(now)) {
            throw StaffInvitationException.invalidOrExpired();
        }
        if (users.findByEmail(invitation.getEmail()).isPresent()) {
            throw StaffInvitationException.completionConflict();
        }

        var user = new User();
        user.setEmail(invitation.getEmail());
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setEnabled(true);
        invitation.roles().forEach(user::addRole);
        users.save(user);
        staffProfiles.save(new StaffProfile(user));
        invitation.accept(now);
    }

    private Set<RoleName> parseRoles(Set<String> rawRoles) {
        if (rawRoles == null || rawRoles.isEmpty()) {
            throw new StaffInvitationException("Select at least one staff role.");
        }
        var roles = new LinkedHashSet<RoleName>();
        for (var rawRole : rawRoles) {
            var role = parseRole(rawRole);
            if (!role.isClinicalStaff()) {
                throw new StaffInvitationException(STAFF_ROLE_ERROR);
            }
            roles.add(role);
        }
        return roles;
    }

    private RoleName parseRole(String rawRole) {
        try {
            return RoleName.from(rawRole);
        } catch (IllegalArgumentException ex) {
            throw new StaffInvitationException(STAFF_ROLE_ERROR);
        }
    }

    private void validateEmailAvailableForStaff(String email) {
        var existing = users.findByEmail(email).orElse(null);
        if (existing == null) {
            return;
        }
        if (!existing.isEnabled()) {
            throw new StaffInvitationException("This email belongs to an inactive account and requires manual resolution.");
        }
        if (existing.hasRole(RoleName.PATIENT)) {
            throw new StaffInvitationException("This email is already registered as a patient. Staff access requires a separate account.");
        }
        if (existing.hasAnyRole(RoleName.NUTRITION_SPECIALIST, RoleName.PHYSICIAN, RoleName.COORDINATOR, RoleName.ADMIN)) {
            throw new StaffInvitationException("This email already has staff access.");
        }
        throw new StaffInvitationException("This email belongs to an inactive account and requires manual resolution.");
    }

    private String generateToken() {
        var bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
```

- [ ] **Step 6: Run service tests**

Run:

```bash
./gradlew test --tests com.metabion.service.StaffInvitationServiceTest
```

Expected: PASS.

- [ ] **Step 7: Commit service**

Run:

```bash
git add src/main/java/com/metabion/dto/CreateStaffInvitationRequest.java \
  src/main/java/com/metabion/dto/AcceptStaffInvitationRequest.java \
  src/main/java/com/metabion/dto/StaffInvitationResponse.java \
  src/main/java/com/metabion/exception/StaffInvitationException.java \
  src/main/java/com/metabion/service/StaffInvitationService.java \
  src/test/java/com/metabion/service/StaffInvitationServiceTest.java
git commit -m "Add staff invitation service"
```

### Task 4: Add REST Endpoints and Security Rules

**Files:**
- Create: `src/main/java/com/metabion/controller/StaffInvitationController.java`
- Modify: `src/main/java/com/metabion/config/SecurityConfig.java`
- Test: `src/test/java/com/metabion/controller/StaffInvitationControllerTest.java`
- Test: `src/test/java/com/metabion/config/SecurityConfigTest.java`

- [ ] **Step 1: Write failing REST/security tests**

Create `src/test/java/com/metabion/controller/StaffInvitationControllerTest.java` using the existing controller-test style:

```java
package com.metabion.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metabion.dto.CreateStaffInvitationRequest;
import com.metabion.service.StaffInvitationService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(StaffInvitationController.class)
class StaffInvitationControllerTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper objectMapper;

    @MockBean
    StaffInvitationService staffInvitationService;

    @Test
    @WithMockUser(username = "admin@example.com", roles = "ADMIN")
    void adminCreatesInvitation() throws Exception {
        mvc.perform(post("/api/admin/staff-invitations")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CreateStaffInvitationRequest("expert@example.com", Set.of("PHYSICIAN")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"));

        var request = ArgumentCaptor.forClass(CreateStaffInvitationRequest.class);
        verify(staffInvitationService).createInvitation(org.mockito.Mockito.eq("admin@example.com"), request.capture());
        assertThat(request.getValue().roles()).containsExactly("PHYSICIAN");
    }

    @Test
    @WithMockUser(username = "staff@example.com", roles = "PHYSICIAN")
    void nonAdminCannotCreateInvitation() throws Exception {
        mvc.perform(post("/api/admin/staff-invitations")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CreateStaffInvitationRequest("expert@example.com", Set.of("PHYSICIAN")))))
                .andExpect(status().isForbidden());
    }

    @Test
    void publicAcceptDoesNotRequireCsrfOrAuthentication() throws Exception {
        mvc.perform(post("/api/staff-invitations/accept")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"token":"plain-token","password":"password123"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("accepted"));
    }
}
```

- [ ] **Step 2: Run REST/security tests to verify failure**

Run:

```bash
./gradlew test --tests com.metabion.controller.StaffInvitationControllerTest
```

Expected: compilation fails because `StaffInvitationController` does not exist.

- [ ] **Step 3: Add REST controller**

Create `src/main/java/com/metabion/controller/StaffInvitationController.java`:

```java
package com.metabion.controller;

import com.metabion.dto.AcceptStaffInvitationRequest;
import com.metabion.dto.CreateStaffInvitationRequest;
import com.metabion.dto.StaffInvitationResponse;
import com.metabion.service.StaffInvitationService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class StaffInvitationController {

    private final StaffInvitationService staffInvitationService;

    public StaffInvitationController(StaffInvitationService staffInvitationService) {
        this.staffInvitationService = staffInvitationService;
    }

    @PostMapping("/api/admin/staff-invitations")
    @PreAuthorize("hasRole('ADMIN')")
    public StaffInvitationResponse create(@Valid @RequestBody CreateStaffInvitationRequest request,
                                          Authentication authentication) {
        staffInvitationService.createInvitation(authentication.getName(), request);
        return new StaffInvitationResponse("ok");
    }

    @PostMapping("/api/staff-invitations/accept")
    public StaffInvitationResponse accept(@Valid @RequestBody AcceptStaffInvitationRequest request) {
        staffInvitationService.acceptInvitation(request);
        return new StaffInvitationResponse("accepted");
    }
}
```

If method security is not enabled in the project, rely on `SecurityConfig` request matchers rather than adding `@PreAuthorize`. Do not add method security only for this endpoint.

- [ ] **Step 4: Update security route rules**

Modify `src/main/java/com/metabion/config/SecurityConfig.java`:

```java
private static final String[] PUBLIC_AUTH_POSTS = {
        "/api/auth/register",
        "/api/auth/login",
        "/api/auth/forgot-password",
        "/api/auth/reset-password",
        "/api/staff-invitations/accept"
};
```

Add authorization rules before the generic `/api/**` rule:

```java
.requestMatchers(HttpMethod.POST, "/api/staff-invitations/accept").permitAll()
.requestMatchers("/api/admin/staff-invitations").hasRole("ADMIN")
.requestMatchers("/admin/staff-invitations/**").hasRole("ADMIN")
```

Keep CSRF ignored for public REST accept only through the exact `PUBLIC_AUTH_POSTS` path. Do not ignore CSRF for `/admin/staff-invitations`.

- [ ] **Step 5: Map invitation exceptions to HTTP responses**

If `GlobalExceptionHandler` currently maps only existing exceptions, add:

```java
@ExceptionHandler(StaffInvitationException.class)
ResponseEntity<Map<String, String>> staffInvitation(StaffInvitationException ex) {
    return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
}
```

Use the exact response shape already used by the existing handler if it differs from `{"error": "..."}`.

- [ ] **Step 6: Run REST/security tests**

Run:

```bash
./gradlew test --tests com.metabion.controller.StaffInvitationControllerTest --tests com.metabion.config.SecurityConfigTest
```

Expected: PASS.

- [ ] **Step 7: Commit REST/security**

Run:

```bash
git add src/main/java/com/metabion/controller/StaffInvitationController.java \
  src/main/java/com/metabion/config/SecurityConfig.java \
  src/main/java/com/metabion/controller/GlobalExceptionHandler.java \
  src/test/java/com/metabion/controller/StaffInvitationControllerTest.java \
  src/test/java/com/metabion/config/SecurityConfigTest.java
git commit -m "Add staff invitation REST endpoints"
```

### Task 5: Add MVC Invitation Forms

**Files:**
- Create: `src/main/java/com/metabion/controller/StaffInvitationWebController.java`
- Create: `src/main/resources/templates/admin-staff-invitation.html`
- Create: `src/main/resources/templates/staff-invitation-accept.html`
- Modify: `src/main/java/com/metabion/config/SecurityConfig.java`
- Test: `src/test/java/com/metabion/controller/StaffInvitationWebControllerTest.java`
- Test: `src/test/java/com/metabion/controller/WebAuthTemplateTest.java`

- [ ] **Step 1: Write failing MVC tests**

Create `src/test/java/com/metabion/controller/StaffInvitationWebControllerTest.java`:

```java
package com.metabion.controller;

import com.metabion.service.StaffInvitationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@WebMvcTest(StaffInvitationWebController.class)
class StaffInvitationWebControllerTest {

    @Autowired
    MockMvc mvc;

    @MockBean
    StaffInvitationService staffInvitationService;

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminInviteFormRenders() throws Exception {
        mvc.perform(get("/admin/staff-invitations/new"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin-staff-invitation"))
                .andExpect(model().attributeExists("form"))
                .andExpect(model().attributeExists("staffRoles"));
    }

    @Test
    @WithMockUser(username = "admin@example.com", roles = "ADMIN")
    void adminSubmitCreatesInvitation() throws Exception {
        mvc.perform(post("/admin/staff-invitations")
                        .with(csrf())
                        .param("email", "expert@example.com")
                        .param("roles", "PHYSICIAN"))
                .andExpect(status().isOk())
                .andExpect(view().name("result"));

        verify(staffInvitationService).createInvitation(any(), any());
    }

    @Test
    void publicAcceptFormRenders() throws Exception {
        mvc.perform(get("/staff-invitations/accept").param("token", "plain-token"))
                .andExpect(status().isOk())
                .andExpect(view().name("staff-invitation-accept"))
                .andExpect(model().attribute("token", "plain-token"));
    }

    @Test
    void publicAcceptSubmitAcceptsInvitation() throws Exception {
        mvc.perform(post("/staff-invitations/accept")
                        .with(csrf())
                        .param("token", "plain-token")
                        .param("password", "password123"))
                .andExpect(status().isOk())
                .andExpect(view().name("result"));

        verify(staffInvitationService).acceptInvitation(any());
    }

    @Test
    @WithMockUser(roles = "PHYSICIAN")
    void nonAdminCannotOpenAdminInviteForm() throws Exception {
        mvc.perform(get("/admin/staff-invitations/new"))
                .andExpect(status().isForbidden());
    }
}
```

- [ ] **Step 2: Run MVC tests to verify failure**

Run:

```bash
./gradlew test --tests com.metabion.controller.StaffInvitationWebControllerTest
```

Expected: compilation fails because controller and templates do not exist.

- [ ] **Step 3: Add MVC controller**

Create `src/main/java/com/metabion/controller/StaffInvitationWebController.java`:

```java
package com.metabion.controller;

import com.metabion.domain.RoleName;
import com.metabion.dto.AcceptStaffInvitationRequest;
import com.metabion.dto.CreateStaffInvitationRequest;
import com.metabion.exception.StaffInvitationException;
import com.metabion.service.StaffInvitationService;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import java.util.Set;

@Controller
public class StaffInvitationWebController {

    private final StaffInvitationService staffInvitationService;

    public StaffInvitationWebController(StaffInvitationService staffInvitationService) {
        this.staffInvitationService = staffInvitationService;
    }

    @GetMapping("/admin/staff-invitations/new")
    public String newInvitation(Model model) {
        addInvitationModel(model, new CreateStaffInvitationRequest("", Set.of()));
        return "admin-staff-invitation";
    }

    @PostMapping("/admin/staff-invitations")
    public String createInvitation(@Valid @ModelAttribute("form") CreateStaffInvitationRequest form,
                                   BindingResult bindingResult,
                                   Authentication authentication,
                                   Model model) {
        if (bindingResult.hasErrors()) {
            addInvitationModel(model, form);
            return "admin-staff-invitation";
        }
        try {
            staffInvitationService.createInvitation(authentication.getName(), form);
            result(model, "Invitation sent", "The staff setup link has been sent.", "/admin/staff-invitations/new", "Invite another");
            return "result";
        } catch (StaffInvitationException ex) {
            model.addAttribute("error", ex.getMessage());
            addInvitationModel(model, form);
            return "admin-staff-invitation";
        }
    }

    @GetMapping("/staff-invitations/accept")
    public String acceptForm(@RequestParam String token, Model model) {
        model.addAttribute("token", token);
        model.addAttribute("form", new AcceptStaffInvitationRequest(token, ""));
        return "staff-invitation-accept";
    }

    @PostMapping("/staff-invitations/accept")
    public String accept(@Valid @ModelAttribute("form") AcceptStaffInvitationRequest form,
                         BindingResult bindingResult,
                         Model model) {
        if (bindingResult.hasErrors()) {
            model.addAttribute("token", form.token());
            return "staff-invitation-accept";
        }
        try {
            staffInvitationService.acceptInvitation(form);
            result(model, "Account ready", "Your staff account is ready. You can now sign in.", "/login", "Sign in");
            return "result";
        } catch (StaffInvitationException ex) {
            result(model, "Invitation link invalid", ex.getMessage(), "/login", "Back to sign in");
            return "result";
        }
    }

    private void addInvitationModel(Model model, CreateStaffInvitationRequest form) {
        model.addAttribute("form", form);
        model.addAttribute("staffRoles", List.of(RoleName.NUTRITION_SPECIALIST, RoleName.PHYSICIAN, RoleName.COORDINATOR));
    }

    private void result(Model model, String title, String message, String href, String action) {
        model.addAttribute("title", title);
        model.addAttribute("message", message);
        model.addAttribute("href", href);
        model.addAttribute("action", action);
    }
}
```

- [ ] **Step 4: Add admin invitation template**

Create `src/main/resources/templates/admin-staff-invitation.html`:

```html
<!DOCTYPE html>
<html lang="en" xmlns:th="http://www.thymeleaf.org">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title>Invite staff</title>
    <link rel="stylesheet" th:href="@{/css/auth.css}">
</head>
<body>
<main class="auth-shell">
    <section class="auth-card">
        <h1>Invite staff</h1>
        <p th:if="${error}" class="error" th:text="${error}"></p>
        <form th:action="@{/admin/staff-invitations}" th:object="${form}" method="post">
            <label for="email">Email</label>
            <input id="email" type="email" th:field="*{email}" autocomplete="email" required>
            <p class="error" th:if="${#fields.hasErrors('email')}" th:errors="*{email}"></p>

            <fieldset>
                <legend>Roles</legend>
                <label th:each="role : ${staffRoles}">
                    <input type="checkbox" name="roles" th:value="${role.name()}">
                    <span th:text="${role.name()}">PHYSICIAN</span>
                </label>
            </fieldset>
            <p class="error" th:if="${#fields.hasErrors('roles')}" th:errors="*{roles}"></p>

            <button type="submit">Send invitation</button>
        </form>
    </section>
</main>
</body>
</html>
```

- [ ] **Step 5: Add public acceptance template**

Create `src/main/resources/templates/staff-invitation-accept.html`:

```html
<!DOCTYPE html>
<html lang="en" xmlns:th="http://www.thymeleaf.org">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title>Set up staff account</title>
    <link rel="stylesheet" th:href="@{/css/auth.css}">
</head>
<body>
<main class="auth-shell">
    <section class="auth-card">
        <h1>Set up staff account</h1>
        <form th:action="@{/staff-invitations/accept}" th:object="${form}" method="post">
            <input type="hidden" th:field="*{token}">
            <label for="password">Password</label>
            <input id="password" type="password" th:field="*{password}" autocomplete="new-password" required>
            <p class="error" th:if="${#fields.hasErrors('password')}" th:errors="*{password}"></p>
            <button type="submit">Create account</button>
        </form>
    </section>
</main>
</body>
</html>
```

- [ ] **Step 6: Update MVC security routes**

Modify `src/main/java/com/metabion/config/SecurityConfig.java`:

```java
private static final String[] PUBLIC_MVC_GETS = {
        "/",
        "/login",
        "/register",
        "/verify",
        "/forgot-password",
        "/reset-password",
        "/staff-invitations/accept",
        "/error"
};

private static final String[] PUBLIC_MVC_POSTS = {
        "/login",
        "/register",
        "/forgot-password",
        "/reset-password",
        "/staff-invitations/accept"
};
```

Keep the admin route restricted:

```java
.requestMatchers("/admin/staff-invitations/**").hasRole("ADMIN")
```

- [ ] **Step 7: Run MVC/template tests**

Run:

```bash
./gradlew test --tests com.metabion.controller.StaffInvitationWebControllerTest --tests com.metabion.controller.WebAuthTemplateTest --tests com.metabion.controller.ThymeleafAvailabilityTest
```

Expected: PASS.

- [ ] **Step 8: Commit MVC flow**

Run:

```bash
git add src/main/java/com/metabion/controller/StaffInvitationWebController.java \
  src/main/java/com/metabion/config/SecurityConfig.java \
  src/main/resources/templates/admin-staff-invitation.html \
  src/main/resources/templates/staff-invitation-accept.html \
  src/test/java/com/metabion/controller/StaffInvitationWebControllerTest.java \
  src/test/java/com/metabion/controller/WebAuthTemplateTest.java \
  src/test/java/com/metabion/controller/ThymeleafAvailabilityTest.java
git commit -m "Add staff invitation MVC flow"
```

### Task 6: Final Integration Verification

**Files:**
- Review: all files changed in Tasks 1-5.
- Modify only if verification finds a concrete defect.

- [ ] **Step 1: Run focused invitation tests**

Run:

```bash
./gradlew test \
  --tests com.metabion.repository.StaffInvitationRepositoryTest \
  --tests com.metabion.service.StaffInvitationServiceTest \
  --tests com.metabion.controller.StaffInvitationControllerTest \
  --tests com.metabion.controller.StaffInvitationWebControllerTest
```

Expected: PASS.

- [ ] **Step 2: Run full test suite**

Run:

```bash
./gradlew test
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Review the diff for security-sensitive mistakes**

Run:

```bash
git diff HEAD~4..HEAD
```

Check these concrete conditions:

- No plaintext invitation token is persisted.
- No plaintext invitation token is logged by `LoggingEmailService`.
- `/api/admin/staff-invitations` requires `ROLE_ADMIN`.
- `/admin/staff-invitations/**` requires `ROLE_ADMIN`.
- `/api/staff-invitations/accept` is public and CSRF-ignored by exact path.
- `/staff-invitations/accept` MVC GET/POST is public, with CSRF still required for POST.
- Invitation roles exclude `PATIENT` and `ADMIN`.
- Existing patient email is rejected during admin invite creation.
- Patient registration code in `UserService.register` is unchanged.

- [ ] **Step 4: Commit verification fixes if any were needed**

If Step 3 required code changes, run the relevant focused tests again, then:

```bash
git add <changed-files>
git commit -m "Fix staff invitation verification issues"
```

If Step 3 required no changes, do not create an empty commit.

## Self-Review

Spec coverage:

- Admin-only invitation creation: Task 4 and Task 5 security rules.
- Separate invitation table with role child table: Task 1.
- No `updated_at` on invitation: Task 1 migration and entity.
- `expires_at` planned expiration semantics: Task 1 entity and Task 3 service.
- Only staff roles invited; `PATIENT` and `ADMIN` rejected: Task 1 domain guard and Task 3 service validation.
- Patient/staff mutual exclusivity: Task 3 service tests and validation.
- Existing patient/staff/admin/disabled emails rejected in admin flow: Task 3.
- Reissue revokes previous pending invite: Task 3.
- Token hashing and redacted logging: Task 2 and Task 3.
- REST endpoints: Task 4.
- MVC pages: Task 5.
- Security/CSRF: Task 4 and Task 5.
- Full verification: Task 6.

Placeholder scan:

- The plan does not use `TBD`, `TODO`, `implement later`, or unbounded "handle edge cases" language.
- The only helper-name caveat is limited to existing test helper names in email tests; implementation code remains concrete.

Type consistency:

- `CreateStaffInvitationRequest`, `AcceptStaffInvitationRequest`, and `StaffInvitationResponse` are defined before controllers use them.
- `StaffInvitationService.createInvitation(String, CreateStaffInvitationRequest)` and `acceptInvitation(AcceptStaffInvitationRequest)` are used consistently in REST and MVC controllers.
- `StaffInvitationRepository.findByTokenHash`, `findFirstByEmailAndAcceptedAtIsNullAndRevokedAtIsNull`, and `revokeActiveForEmail` are used consistently by tests and service.
