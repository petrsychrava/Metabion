package com.metabion.service;

import com.metabion.domain.RoleName;
import com.metabion.domain.StaffInvitation;
import com.metabion.domain.StaffProfile;
import com.metabion.domain.User;
import com.metabion.dto.AcceptStaffInvitationRequest;
import com.metabion.dto.CreateStaffInvitationRequest;
import com.metabion.exception.StaffInvitationException;
import com.metabion.exception.ValidationException;
import com.metabion.repository.StaffInvitationRepository;
import com.metabion.repository.StaffProfileRepository;
import com.metabion.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StaffInvitationServiceTest {

    @Mock
    private StaffInvitationRepository invitations;

    @Mock
    private UserRepository users;

    @Mock
    private StaffProfileRepository staffProfiles;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private EmailService emailService;

    private StaffInvitationService service;

    @BeforeEach
    void setUp() {
        service = new StaffInvitationService(invitations, users, staffProfiles, passwordEncoder, emailService);
    }

    @Test
    void adminCreatesInvitationForNewEmail() {
        var admin = user("admin@example.com", true, RoleName.ADMIN);
        when(users.findByEmail("admin@example.com")).thenReturn(Optional.of(admin));
        when(users.findByEmail("expert@example.com")).thenReturn(Optional.empty());

        var response = service.createInvitation(" Admin@Example.COM ", new CreateStaffInvitationRequest(
                " Expert@Example.COM ",
                new LinkedHashSet<>(Arrays.asList("PHYSICIAN", "COORDINATOR", "PHYSICIAN"))));

        assertThat(response.status()).isEqualTo("invitation_created");
        verify(invitations).revokeActiveForEmail(eq("expert@example.com"), any(Instant.class));

        var invitationCaptor = ArgumentCaptor.forClass(StaffInvitation.class);
        verify(invitations).save(invitationCaptor.capture());
        var invitation = invitationCaptor.getValue();
        assertThat(invitation.getEmail()).isEqualTo("expert@example.com");
        assertThat(invitation.getInvitedBy()).isSameAs(admin);
        assertThat(invitation.roles()).containsExactlyInAnyOrder(RoleName.PHYSICIAN, RoleName.COORDINATOR);
        assertThat(invitation.getTokenHash()).hasSize(64);
        assertThat(invitation.getExpiresAt()).isAfter(Instant.now().plusSeconds(6 * 24 * 60 * 60));

        var tokenCaptor = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendStaffInvitation(eq("expert@example.com"), tokenCaptor.capture());
        assertThat(tokenCaptor.getValue()).hasSize(43);
        assertThat(invitation.getTokenHash()).isEqualTo(UserService.sha256Hex(tokenCaptor.getValue()));
        assertThat(invitation.getTokenHash()).isNotEqualTo(tokenCaptor.getValue());
    }

    @Test
    void createInvitationSendsEmailAfterTransactionCommit() {
        TransactionSynchronizationManager.initSynchronization();
        try {
            var admin = user("admin@example.com", true, RoleName.ADMIN);
            when(users.findByEmail("admin@example.com")).thenReturn(Optional.of(admin));
            when(users.findByEmail("expert@example.com")).thenReturn(Optional.empty());

            service.createInvitation("admin@example.com",
                    new CreateStaffInvitationRequest("expert@example.com", Set.of("PHYSICIAN")));

            verify(emailService, never()).sendStaffInvitation(any(), any());

            var synchronizations = TransactionSynchronizationManager.getSynchronizations();
            assertThat(synchronizations).hasSize(1);
            synchronizations.getFirst().afterCommit();

            var tokenCaptor = ArgumentCaptor.forClass(String.class);
            verify(emailService).sendStaffInvitation(eq("expert@example.com"), tokenCaptor.capture());
            assertThat(tokenCaptor.getValue()).hasSize(43);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void createInvitationRejectsEmptyRoles() {
        assertThatThrownBy(() -> service.createInvitation("admin@example.com",
                new CreateStaffInvitationRequest("expert@example.com", Set.of())))
                .isInstanceOf(StaffInvitationException.class)
                .hasMessage("Select at least one staff role.");

        verifyNoInteractions(users, invitations, emailService);
    }

    @Test
    void createInvitationRejectsPatientAndAdminRoles() {
        assertThatThrownBy(() -> service.createInvitation("admin@example.com",
                new CreateStaffInvitationRequest("patient@example.com", Set.of("PATIENT"))))
                .isInstanceOf(StaffInvitationException.class)
                .hasMessage("Only nutrition specialist, physician, and coordinator roles can be invited.");

        assertThatThrownBy(() -> service.createInvitation("admin@example.com",
                new CreateStaffInvitationRequest("admin2@example.com", Set.of("ADMIN"))))
                .isInstanceOf(StaffInvitationException.class)
                .hasMessage("Only nutrition specialist, physician, and coordinator roles can be invited.");
    }

    @Test
    void createInvitationRejectsNullRoleEntry() {
        assertThatThrownBy(() -> service.createInvitation("admin@example.com",
                new CreateStaffInvitationRequest("expert@example.com", new LinkedHashSet<>(Arrays.asList("PHYSICIAN", null)))))
                .isInstanceOf(StaffInvitationException.class)
                .hasMessage("Only nutrition specialist, physician, and coordinator roles can be invited.");
    }

    @Test
    void createInvitationRejectsBlankRoleEntry() {
        assertThatThrownBy(() -> service.createInvitation("admin@example.com",
                new CreateStaffInvitationRequest("expert@example.com", Set.of(" "))))
                .isInstanceOf(StaffInvitationException.class)
                .hasMessage("Only nutrition specialist, physician, and coordinator roles can be invited.");
    }

    @Test
    void createInvitationRejectsUnsupportedRoles() {
        assertThatThrownBy(() -> service.createInvitation("admin@example.com",
                new CreateStaffInvitationRequest("expert@example.com", Set.of("UNKNOWN"))))
                .isInstanceOf(StaffInvitationException.class)
                .hasMessage("Only nutrition specialist, physician, and coordinator roles can be invited.");
    }

    @Test
    void createInvitationRejectsExistingEnabledPatientEmail() {
        when(users.findByEmail("admin@example.com")).thenReturn(Optional.of(user("admin@example.com", true, RoleName.ADMIN)));
        when(users.findByEmail("patient@example.com")).thenReturn(Optional.of(user("patient@example.com", true, RoleName.PATIENT)));

        assertThatThrownBy(() -> service.createInvitation("admin@example.com",
                new CreateStaffInvitationRequest("patient@example.com", Set.of("PHYSICIAN"))))
                .isInstanceOf(StaffInvitationException.class)
                .hasMessage("This email is already registered as a patient. Staff access requires a separate account.");

        verify(invitations, never()).save(any());
    }

    @Test
    void createInvitationRejectsExistingEnabledStaffOrAdminEmail() {
        when(users.findByEmail("admin@example.com")).thenReturn(Optional.of(user("admin@example.com", true, RoleName.ADMIN)));
        when(users.findByEmail("staff@example.com")).thenReturn(Optional.of(user("staff@example.com", true, RoleName.NUTRITION_SPECIALIST)));

        assertThatThrownBy(() -> service.createInvitation("admin@example.com",
                new CreateStaffInvitationRequest("staff@example.com", Set.of("PHYSICIAN"))))
                .isInstanceOf(StaffInvitationException.class)
                .hasMessage("This email already has staff access.");

        when(users.findByEmail("admin2@example.com")).thenReturn(Optional.of(user("admin2@example.com", true, RoleName.ADMIN)));

        assertThatThrownBy(() -> service.createInvitation("admin@example.com",
                new CreateStaffInvitationRequest("admin2@example.com", Set.of("PHYSICIAN"))))
                .isInstanceOf(StaffInvitationException.class)
                .hasMessage("This email already has staff access.");
    }

    @Test
    void createInvitationRejectsDisabledExistingUser() {
        when(users.findByEmail("admin@example.com")).thenReturn(Optional.of(user("admin@example.com", true, RoleName.ADMIN)));
        when(users.findByEmail("disabled@example.com")).thenReturn(Optional.of(user("disabled@example.com", false, RoleName.PATIENT)));

        assertThatThrownBy(() -> service.createInvitation("admin@example.com",
                new CreateStaffInvitationRequest("disabled@example.com", Set.of("PHYSICIAN"))))
                .isInstanceOf(StaffInvitationException.class)
                .hasMessage("This email belongs to an inactive account and requires manual resolution.");
    }

    @Test
    void createInvitationReissuesByRevokingPreviousActiveInvitation() {
        when(users.findByEmail("admin@example.com")).thenReturn(Optional.of(user("admin@example.com", true, RoleName.ADMIN)));
        when(users.findByEmail("expert@example.com")).thenReturn(Optional.empty());

        service.createInvitation("admin@example.com",
                new CreateStaffInvitationRequest("expert@example.com", Set.of("PHYSICIAN")));
        service.createInvitation("admin@example.com",
                new CreateStaffInvitationRequest("expert@example.com", Set.of("COORDINATOR")));

        verify(invitations, org.mockito.Mockito.times(2)).revokeActiveForEmail(eq("expert@example.com"), any(Instant.class));
        verify(invitations, org.mockito.Mockito.times(2)).save(any(StaffInvitation.class));
    }

    @Test
    void acceptInvitationCreatesStaffUserAndProfile() {
        var invitation = invitation("expert@example.com", "valid-token", Instant.now().plusSeconds(3600),
                RoleName.PHYSICIAN, RoleName.COORDINATOR);
        when(invitations.findByTokenHash(UserService.sha256Hex("valid-token"))).thenReturn(Optional.of(invitation));
        when(users.findByEmail("expert@example.com")).thenReturn(Optional.empty());
        when(passwordEncoder.encode("SecurePass123")).thenReturn("encoded-password");
        when(users.saveAndFlush(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0, User.class));

        var response = service.acceptInvitation(new AcceptStaffInvitationRequest("valid-token", "SecurePass123"));

        assertThat(response.status()).isEqualTo("invitation_accepted");

        var userCaptor = ArgumentCaptor.forClass(User.class);
        verify(users).saveAndFlush(userCaptor.capture());
        var savedUser = userCaptor.getValue();
        assertThat(savedUser.getEmail()).isEqualTo("expert@example.com");
        assertThat(savedUser.isEnabled()).isTrue();
        assertThat(savedUser.getPasswordHash()).isEqualTo("encoded-password");
        assertThat(savedUser.roleNames()).containsExactlyInAnyOrder("PHYSICIAN", "COORDINATOR");

        verify(staffProfiles).saveAndFlush(any(StaffProfile.class));
        assertThat(invitation.getAcceptedAt()).isNotNull();
    }

    @Test
    void acceptInvitationTranslatesUserCreationIntegrityViolationToCompletionConflict() {
        var invitation = invitation("expert@example.com", "valid-token", Instant.now().plusSeconds(3600), RoleName.PHYSICIAN);
        when(invitations.findByTokenHash(UserService.sha256Hex("valid-token"))).thenReturn(Optional.of(invitation));
        when(users.findByEmail("expert@example.com")).thenReturn(Optional.empty());
        when(passwordEncoder.encode("SecurePass123")).thenReturn("encoded-password");
        when(users.saveAndFlush(any(User.class))).thenThrow(new DataIntegrityViolationException("duplicate email"));

        assertThatThrownBy(() -> service.acceptInvitation(new AcceptStaffInvitationRequest("valid-token", "SecurePass123")))
                .isInstanceOf(StaffInvitationException.class)
                .hasMessage("This invitation cannot be completed. Contact an administrator.");
    }

    @Test
    void acceptInvitationRejectsUnknownTokenWithPublicMessage() {
        when(invitations.findByTokenHash(UserService.sha256Hex("unknown-token"))).thenReturn(Optional.empty());

        assertInvalidOrExpired("unknown-token");
    }

    @Test
    void acceptInvitationRejectsAcceptedInviteWithPublicMessage() {
        var invitation = invitation("accepted@example.com", "accepted-token", Instant.now().plusSeconds(3600), RoleName.PHYSICIAN);
        invitation.accept(Instant.now());
        when(invitations.findByTokenHash(UserService.sha256Hex("accepted-token"))).thenReturn(Optional.of(invitation));

        assertInvalidOrExpired("accepted-token");
    }

    @Test
    void acceptInvitationRejectsExpiredInviteWithPublicMessage() {
        var invitation = invitation("expired@example.com", "expired-token", Instant.now().minusSeconds(1), RoleName.PHYSICIAN);
        when(invitations.findByTokenHash(UserService.sha256Hex("expired-token"))).thenReturn(Optional.of(invitation));

        assertInvalidOrExpired("expired-token");
    }

    @Test
    void acceptInvitationRejectsRevokedInviteWithPublicMessage() {
        var invitation = invitation("revoked@example.com", "revoked-token", Instant.now().plusSeconds(3600), RoleName.PHYSICIAN);
        invitation.revoke(Instant.now());
        when(invitations.findByTokenHash(UserService.sha256Hex("revoked-token"))).thenReturn(Optional.of(invitation));

        assertInvalidOrExpired("revoked-token");
    }

    @Test
    void acceptInvitationRejectsWhenEmailBecameOccupied() {
        var invitation = invitation("expert@example.com", "valid-token", Instant.now().plusSeconds(3600), RoleName.PHYSICIAN);
        when(invitations.findByTokenHash(UserService.sha256Hex("valid-token"))).thenReturn(Optional.of(invitation));
        when(users.findByEmail("expert@example.com")).thenReturn(Optional.of(user("expert@example.com", true, RoleName.PHYSICIAN)));

        assertThatThrownBy(() -> service.acceptInvitation(new AcceptStaffInvitationRequest("valid-token", "SecurePass123")))
                .isInstanceOf(StaffInvitationException.class)
                .hasMessage("This invitation cannot be completed. Contact an administrator.");

        verify(users, never()).saveAndFlush(any());
    }

    @Test
    void acceptInvitationEnforcesPassword72ByteLimit() {
        var oversized = "é".repeat(37);

        assertThatThrownBy(() -> service.acceptInvitation(new AcceptStaffInvitationRequest("token", oversized)))
                .isInstanceOf(ValidationException.class)
                .hasMessage("password exceeds 72 bytes");

        verifyNoInteractions(invitations);
    }

    private void assertInvalidOrExpired(String token) {
        assertThatThrownBy(() -> service.acceptInvitation(new AcceptStaffInvitationRequest(token, "SecurePass123")))
                .isInstanceOf(StaffInvitationException.class)
                .hasMessage("This invitation link is invalid or expired.");
    }

    private static StaffInvitation invitation(String email, String token, Instant expiresAt, RoleName... roles) {
        var invitation = new StaffInvitation(email, UserService.sha256Hex(token), null, expiresAt);
        for (var role : roles) {
            invitation.addRole(role);
        }
        return invitation;
    }

    private static User user(String email, boolean enabled, RoleName... roles) {
        var user = new User(email, "hash");
        user.setEnabled(enabled);
        for (var role : roles) {
            user.addRole(role);
        }
        return user;
    }
}
