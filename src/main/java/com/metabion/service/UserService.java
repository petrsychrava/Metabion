package com.metabion.service;

import com.metabion.domain.AccountVerification;
import com.metabion.domain.User;
import com.metabion.dto.RegisterRequest;
import com.metabion.exception.InvalidTokenException;
import com.metabion.exception.ValidationException;
import com.metabion.repository.UserRepository;
import com.metabion.repository.VerificationTokenRepository;
import jakarta.transaction.Transactional;
import org.springframework.security.crypto.password.PasswordEncoder;
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

@Service
@Transactional
public class UserService {

    private static final Duration VERIFICATION_TTL = Duration.ofHours(48);
    private static final String DEFAULT_USER_ROLE = "PATIENT";

    private final UserRepository users;
    private final VerificationTokenRepository verifTokens;
    private final EmailService emailService;
    private final PasswordEncoder passwordEncoder;
    private final SecureRandom random = new SecureRandom();

    public UserService(UserRepository users,
                       VerificationTokenRepository verifTokens,
                       EmailService emailService,
                       PasswordEncoder passwordEncoder) {
        this.users = users;
        this.verifTokens = verifTokens;
        this.emailService = emailService;
        this.passwordEncoder = passwordEncoder;
    }

    public void register(RegisterRequest req) {
        var email = normalize(req.email());
        // BCrypt has a limit of 72 bytes for the password
        if (req.password().getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new ValidationException("password exceeds 72 bytes");
        }

        // If the address is already taken, do nothing and return — caller sees the same generic 200.
        if (users.existsByEmail(email)) {
            return;
        }

        var user = new User();
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode(req.password()));
        user.addRole(DEFAULT_USER_ROLE);
        users.save(user);

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
        // user and token saved via dirty checking
    }

    private void issueVerificationToken(User user) {
        // mark any earlier unconsumed tokens as consumed first
        verifTokens.markAllConsumedForUser(user.getId(), Instant.now());

        var plain = generateToken();
        var token = new AccountVerification();
        token.setUser(user);
        token.setTokenHash(sha256Hex(plain));
        token.setExpiresAt(Instant.now().plus(VERIFICATION_TTL));
        verifTokens.save(token);

        emailService.sendVerification(user.getEmail(), plain);
    }

    static String normalize(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }

    private String generateToken() {
        var bytes = new byte[32];
        random.nextBytes(bytes);
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
