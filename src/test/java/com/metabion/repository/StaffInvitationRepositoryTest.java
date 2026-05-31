package com.metabion.repository;

import com.metabion.domain.RoleName;
import com.metabion.domain.StaffInvitation;
import com.metabion.domain.User;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest(properties = {"spring.jpa.hibernate.ddl-auto=validate"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class StaffInvitationRepositoryTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    StaffInvitationRepository staffInvitations;

    @Autowired
    UserRepository users;

    @Autowired
    EntityManager entityManager;

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Test
    void savesInvitationWithPhysicianAndCoordinatorRoles() {
        var invitedBy = createUser("admin@example.com", RoleName.ADMIN);
        var invitation = new StaffInvitation(
                "  Staff@Example.COM  ",
                hash(1),
                invitedBy,
                Instant.parse("2026-06-01T12:00:00Z"));
        invitation.addRole(RoleName.PHYSICIAN);
        invitation.addRole(RoleName.COORDINATOR);

        staffInvitations.saveAndFlush(invitation);
        entityManager.clear();

        var loaded = staffInvitations.findByTokenHash(hash(1)).orElseThrow();
        assertThat(loaded.getEmail()).isEqualTo("staff@example.com");
        assertThat(loaded.getInvitedBy().getId()).isEqualTo(invitedBy.getId());
        assertThat(loaded.roles()).containsExactlyInAnyOrder(RoleName.PHYSICIAN, RoleName.COORDINATOR);
        assertThat(loaded.isActive(Instant.parse("2026-06-01T11:00:00Z"))).isTrue();
    }

    @Test
    void rejectsUnsupportedInvitationRoleAtDomainBoundary() {
        var invitation = new StaffInvitation(
                "patient@example.com",
                hash(2),
                null,
                Instant.parse("2026-06-01T12:00:00Z"));

        assertThatThrownBy(() -> invitation.addRole(RoleName.PATIENT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Staff invitation role must be a clinical staff role");
    }

    @Test
    void findsInvitationByTokenHash() {
        var invitation = buildInvitation("lookup@example.com", hash(3));
        invitation.addRole(RoleName.NUTRITION_SPECIALIST);
        staffInvitations.saveAndFlush(invitation);

        assertThat(staffInvitations.findByTokenHash(hash(3)))
                .hasValueSatisfying(found -> assertThat(found.roles())
                        .containsExactly(RoleName.NUTRITION_SPECIALIST));
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void preventsTwoPendingInvitationsForSameNormalizedEmail() {
        var first = buildInvitation("duplicate@example.com", hash(4));
        first.addRole(RoleName.PHYSICIAN);
        staffInvitations.saveAndFlush(first);

        var second = buildInvitation("  DUPLICATE@example.com  ", hash(5));
        second.addRole(RoleName.COORDINATOR);

        assertThatThrownBy(() -> staffInvitations.saveAndFlush(second))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void revokedInvitationAllowsReissueForSameEmail() {
        var revokedAt = Instant.parse("2026-05-31T10:00:00Z");
        var first = buildInvitation("reissue@example.com", hash(6));
        first.addRole(RoleName.PHYSICIAN);
        first.revoke(revokedAt);
        staffInvitations.saveAndFlush(first);

        var second = buildInvitation("reissue@example.com", hash(7));
        second.addRole(RoleName.COORDINATOR);

        assertThat(staffInvitations.saveAndFlush(second).isActive(revokedAt)).isTrue();
    }

    @Test
    void repositoryRevokesActiveInvitationsByEmailAndTimestamp() {
        var revokedAt = Instant.parse("2026-05-31T10:00:00Z");
        var active = buildInvitation("revoke@example.com", hash(8));
        active.addRole(RoleName.PHYSICIAN);
        staffInvitations.saveAndFlush(active);

        var alreadyRevoked = buildInvitation("revoked-reissue@example.com", hash(9));
        alreadyRevoked.addRole(RoleName.COORDINATOR);
        alreadyRevoked.revoke(revokedAt.minusSeconds(60));
        staffInvitations.saveAndFlush(alreadyRevoked);
        var reissued = buildInvitation("revoked-reissue@example.com", hash(10));
        reissued.addRole(RoleName.COORDINATOR);
        staffInvitations.saveAndFlush(reissued);

        assertThat(staffInvitations.revokeActiveForEmail("revoke@example.com", revokedAt)).isOne();
        assertThat(staffInvitations.revokeActiveForEmail("revoked-reissue@example.com", revokedAt)).isOne();

        entityManager.clear();
        assertThat(staffInvitations.findByTokenHash(hash(8)).orElseThrow().getRevokedAt())
                .isEqualTo(revokedAt);
        assertThat(staffInvitations.findByTokenHash(hash(9)).orElseThrow().getRevokedAt())
                .isEqualTo(revokedAt.minusSeconds(60));
        assertThat(staffInvitations.findByTokenHash(hash(10)).orElseThrow().getRevokedAt())
                .isEqualTo(revokedAt);
    }

    private StaffInvitation buildInvitation(String email, String tokenHash) {
        return new StaffInvitation(email, tokenHash, null, Instant.parse("2026-06-01T12:00:00Z"));
    }

    private User createUser(String email, RoleName role) {
        var user = users.saveAndFlush(new User(email, "hash"));
        user.addRole(role);
        return users.saveAndFlush(user);
    }

    private static String hash(int seed) {
        return String.format("%064d", seed);
    }
}
