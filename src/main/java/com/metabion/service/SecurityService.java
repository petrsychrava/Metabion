package com.metabion.service;

import com.metabion.domain.User;
import com.metabion.dto.LoginRequest;
import com.metabion.dto.LoginResponse;
import com.metabion.repository.UserRepository;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Service
public class SecurityService {

    private static final int MAX_FAILED_ATTEMPTS = 5;
    private static final Duration LOCKOUT_DURATION = Duration.ofMinutes(15);

    /**
     * Dummy BCrypt hash for timing equalization.
     * When the email is not found, we still run BCrypt against this hash so that
     * the response time is the same whether the account exists or not.
     */
    public static final String DUMMY_HASH =
        "$2a$12$WApznUPhDubN0eFkT2PMeOlxBk2M3PqL8RKT3NlbWgSgY8w5kIi2y";

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final MfaChallengeService mfa;
    private final HttpSessionSecurityContextRepository contextRepository;
    private final SecurityContextHolderStrategy holderStrategy;

    public SecurityService(UserRepository users,
                           PasswordEncoder encoder,
                           MfaChallengeService mfa) {
        this.users = users;
        this.encoder = encoder;
        this.mfa = mfa;
        this.contextRepository = new HttpSessionSecurityContextRepository();
        this.holderStrategy = SecurityContextHolder.getContextHolderStrategy();
    }

    @Transactional(noRollbackFor = BadCredentialsException.class)
    public LoginResponse login(LoginRequest req,
                               HttpServletRequest httpReq,
                               HttpServletResponse httpResp) {
        var email = UserService.normalize(req.email());
        var userOpt = users.findByEmail(email);

        // Timing equalization: always run BCrypt once, against a dummy hash if user not found.
        var hashToCheck = userOpt.map(User::getPasswordHash).orElse(DUMMY_HASH);
        boolean passwordOk = encoder.matches(req.password(), hashToCheck);

        if (userOpt.isEmpty() || !passwordOk) {
            userOpt.ifPresent(this::recordFailure);
            if (userOpt.isPresent()) {
                users.flush();
            }
            throw new BadCredentialsException("Invalid credentials");
        }

        var user = userOpt.get();

        if (!user.isEnabled()) {
            throw new BadCredentialsException("Invalid credentials");
        }
        if (user.isLocked()) {
            throw new BadCredentialsException("Invalid credentials");
        }

        // Success — clear lockout state.
        user.setFailedLoginAttempts(0);
        user.setLockedUntil(null);
        users.save(user);

        if (mfa.isRequired(user)) {
            var challengeId = UUID.randomUUID().toString();
            return LoginResponse.mfaRequired(user.getEmail(), user.roleNames(),
                                             challengeId, java.util.List.of("totp"));
        }

        establishSession(user, httpReq, httpResp);
        return LoginResponse.authenticated(user.getEmail(), user.roleNames());
    }

    public void logout(HttpServletRequest req, HttpServletResponse resp) {
        var session = req.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        holderStrategy.clearContext();

        var cookie = new Cookie("JSESSIONID", "");
        cookie.setPath("/");
        cookie.setMaxAge(0);
        cookie.setHttpOnly(true);
        resp.addCookie(cookie);
    }

    private void recordFailure(User user) {
        user.setFailedLoginAttempts(user.getFailedLoginAttempts() + 1);
        if (user.getFailedLoginAttempts() >= MAX_FAILED_ATTEMPTS) {
            user.setLockedUntil(Instant.now().plus(LOCKOUT_DURATION));
        }
    }

    private void establishSession(User user,
                                  HttpServletRequest req,
                                  HttpServletResponse resp) {
        var authorities = user.roleNames().stream()
            .map(r -> new SimpleGrantedAuthority("ROLE_" + r))
            .toList();

        var auth = UsernamePasswordAuthenticationToken.authenticated(
            user.getEmail(), null, authorities);

        var context = holderStrategy.createEmptyContext();
        context.setAuthentication(auth);
        holderStrategy.setContext(context);

        // Persist the SecurityContext into HttpSession so it survives across requests.
        contextRepository.saveContext(context, req, resp);

        // Also store directly so the session is immediately available.
        var session = req.getSession(true);
        session.setAttribute(
            HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
    }
}
