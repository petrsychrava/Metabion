package com.metabion.service;

import com.metabion.domain.RoleName;
import com.metabion.domain.StaffInvitation;
import com.metabion.domain.StaffProfile;
import com.metabion.domain.User;
import com.metabion.dto.AcceptStaffInvitationRequest;
import com.metabion.dto.CreateStaffInvitationRequest;
import com.metabion.dto.StaffInvitationResponse;
import com.metabion.exception.StaffInvitationException;
import com.metabion.exception.ValidationException;
import com.metabion.repository.StaffInvitationRepository;
import com.metabion.repository.StaffProfileRepository;
import com.metabion.repository.UserRepository;
import jakarta.transaction.Transactional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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
    private static final String EMPTY_ROLES_MESSAGE = "Select at least one staff role.";
    private static final String UNSUPPORTED_ROLES_MESSAGE =
            "Only nutrition specialist, physician, and coordinator roles can be invited.";
    private static final SecureRandom RANDOM = new SecureRandom();

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

    public StaffInvitationResponse createInvitation(String invitedByEmail, CreateStaffInvitationRequest request) {
        var email = UserService.normalize(request.email());
        var roles = parseRoles(request.roles());
        var invitedBy = users.findByEmail(UserService.normalize(invitedByEmail))
                .orElseThrow(() -> new StaffInvitationException("Inviting administrator was not found."));

        users.findByEmail(email).ifPresent(StaffInvitationService::rejectExistingUser);

        var now = Instant.now();
        invitations.revokeActiveForEmail(email, now);

        var token = generateToken();
        var invitation = new StaffInvitation(email, UserService.sha256Hex(token), invitedBy, now.plus(INVITATION_TTL));
        for (var role : roles) {
            invitation.addRole(role);
        }
        invitations.save(invitation);
        sendInvitationEmailAfterCommit(email, token);

        return new StaffInvitationResponse("invitation_created");
    }

    public StaffInvitationResponse acceptInvitation(AcceptStaffInvitationRequest request) {
        if (request.password().getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new ValidationException("password exceeds 72 bytes");
        }

        var now = Instant.now();
        var invitation = invitations.findByTokenHash(UserService.sha256Hex(request.token()))
                .orElseThrow(StaffInvitationException::invalidOrExpired);
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
        for (var role : invitation.roles()) {
            user.addRole(role);
        }
        try {
            users.saveAndFlush(user);
            staffProfiles.saveAndFlush(new StaffProfile(user));
        } catch (DataIntegrityViolationException ex) {
            throw StaffInvitationException.completionConflict();
        }
        invitation.accept(now);

        return new StaffInvitationResponse("invitation_accepted");
    }

    private void sendInvitationEmailAfterCommit(String email, String token) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            emailService.sendStaffInvitation(email, token);
            return;
        }

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                emailService.sendStaffInvitation(email, token);
            }
        });
    }

    private static Set<RoleName> parseRoles(Set<String> requestedRoles) {
        if (requestedRoles == null || requestedRoles.isEmpty()) {
            throw new StaffInvitationException(EMPTY_ROLES_MESSAGE);
        }

        var roles = new LinkedHashSet<RoleName>();
        for (var requestedRole : requestedRoles) {
            RoleName role;
            try {
                role = RoleName.fromName(requestedRole == null ? null : requestedRole.trim());
            } catch (IllegalArgumentException ex) {
                throw new StaffInvitationException(UNSUPPORTED_ROLES_MESSAGE);
            }
            if (!role.isClinicalStaff()) {
                throw new StaffInvitationException(UNSUPPORTED_ROLES_MESSAGE);
            }
            roles.add(role);
        }
        return roles;
    }

    private static void rejectExistingUser(User user) {
        if (!user.isEnabled()) {
            throw new StaffInvitationException(
                    "This email belongs to an inactive account and requires manual resolution.");
        }
        if (user.hasRole(RoleName.PATIENT)) {
            throw new StaffInvitationException(
                    "This email is already registered as a patient. Staff access requires a separate account.");
        }
        if (user.hasAnyRole(
                RoleName.NUTRITION_SPECIALIST,
                RoleName.PHYSICIAN,
                RoleName.COORDINATOR,
                RoleName.ADMIN)) {
            throw new StaffInvitationException("This email already has staff access.");
        }
    }

    private static String generateToken() {
        var bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
