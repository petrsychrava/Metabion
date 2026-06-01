package com.metabion.service;

import com.metabion.domain.AccountVerification;
import com.metabion.domain.PasswordReset;
import com.metabion.domain.PatientProfile;
import com.metabion.domain.RoleName;
import com.metabion.domain.User;
import com.metabion.dto.ForgotPasswordRequest;
import com.metabion.dto.RegisterRequest;
import com.metabion.dto.ResetPasswordRequest;
import com.metabion.exception.InvalidTokenException;
import com.metabion.exception.ValidationException;
import com.metabion.repository.PatientProfileRepository;
import com.metabion.repository.PasswordResetRepository;
import com.metabion.repository.UserRepository;
import com.metabion.repository.VerificationTokenRepository;
import jakarta.transaction.Transactional;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

@Service
@Transactional
public class UserService {

    private static final Duration VERIFICATION_TTL = Duration.ofHours(48);
    private static final Duration RESET_TTL = Duration.ofHours(24);
    private static final RoleName DEFAULT_USER_ROLE = RoleName.PATIENT;

    private final UserRepository users;
    private final VerificationTokenRepository verifTokens;
    private final PasswordResetRepository resetTokens;
    private final PatientProfileRepository patientProfiles;
    private final EmailService emailService;
    private final PasswordEncoder passwordEncoder;
    private final FindByIndexNameSessionRepository<? extends Session> sessions;
    private static final SecureRandom RANDOM = new SecureRandom();

    public UserService(UserRepository users,
                       VerificationTokenRepository verifTokens,
                       PasswordResetRepository resetTokens,
                       PatientProfileRepository patientProfiles,
                       EmailService emailService,
                       PasswordEncoder passwordEncoder,
                       FindByIndexNameSessionRepository<? extends Session> sessions) {
        this.users = users;
        this.verifTokens = verifTokens;
        this.resetTokens = resetTokens;
        this.patientProfiles = patientProfiles;
        this.emailService = emailService;
        this.passwordEncoder = passwordEncoder;
        this.sessions = sessions;
    }

    public void register(RegisterRequest req) {
        var email = normalize(req.email());
        if (req.password().getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new ValidationException("password exceeds 72 bytes");
        }

        if (users.existsByEmail(email)) {
            return;
        }

        var user = new User();
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode(req.password()));
        user.addRole(DEFAULT_USER_ROLE);
        users.save(user);
        patientProfiles.save(new PatientProfile(user));

        issueVerificationToken(user);
    }

    public void verify(String tokenPlain) {
        var hash = sha256Hex(tokenPlain);
        var token = verifTokens.findByTokenHash(hash)
                .orElseThrow(InvalidTokenException::new);

        if (token.isExpired() || token.isConsumed()) {
            throw new InvalidTokenException();
        }

        token.consume();
        var user = token.getUser();
        user.setEnabled(true);
    }

    public void requestPasswordReset(ForgotPasswordRequest req) {
        var email = normalize(req.email());
        var user = users.findByEmail(email).orElse(null);
        passwordEncoder.matches(generateToken(), SecurityService.DUMMY_HASH);
        if (user == null) {
            return;
        }

        resetTokens.markAllConsumedForUser(user.getId(), Instant.now());

        var plain = generateToken();
        var reset = new PasswordReset();
        reset.setUser(user);
        reset.setTokenHash(sha256Hex(plain));
        reset.setExpiresAt(Instant.now().plus(RESET_TTL));
        resetTokens.save(reset);

        var recipient = user.getEmail();
        CompletableFuture.runAsync(() -> emailService.sendPasswordReset(recipient, plain));
    }

    public void resetPassword(ResetPasswordRequest req) {
        if (req.newPassword().getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new ValidationException("password exceeds 72 bytes");
        }

        var hash = sha256Hex(req.token());
        var token = resetTokens.findByTokenHash(hash)
                .orElseThrow(InvalidTokenException::new);
        if (token.isExpired() || token.isConsumed()) {
            throw new InvalidTokenException();
        }

        token.consume();
        var user = token.getUser();
        user.setPasswordHash(passwordEncoder.encode(req.newPassword()));
        user.setFailedLoginAttempts(0);
        user.setLockedUntil(null);

        invalidateAllSessionsForUser(user);
    }

    private void issueVerificationToken(User user) {
        verifTokens.markAllConsumedForUser(user.getId(), Instant.now());

        var plain = generateToken();
        var token = new AccountVerification();
        token.setUser(user);
        token.setTokenHash(sha256Hex(plain));
        token.setExpiresAt(Instant.now().plus(VERIFICATION_TTL));
        verifTokens.save(token);

        emailService.sendVerification(user.getEmail(), plain);
    }

    private void invalidateAllSessionsForUser(User user) {
        var byPrincipal = sessions.findByPrincipalName(user.getEmail());
        for (var sessionId : byPrincipal.keySet()) {
            sessions.deleteById(sessionId);
        }
    }

    static String normalize(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }

    private String generateToken() {
        var bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String sha256Hex(String plaintext) {
        try {
            var d = MessageDigest.getInstance("SHA-256");
            byte[] hash = d.digest(plaintext.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
