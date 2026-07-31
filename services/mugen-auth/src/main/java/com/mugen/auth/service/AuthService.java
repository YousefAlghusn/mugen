package com.mugen.auth.service;

import com.mugen.auth.config.JwtProperties;
import com.mugen.auth.dto.RefreshTokenClaims;
import com.mugen.auth.dto.RequestContext;
import com.mugen.auth.dto.TokenPair;
import com.mugen.auth.entity.Session;
import com.mugen.auth.entity.User;
import com.mugen.auth.exception.AuthExceptions;
import com.mugen.auth.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;

/**
 * Registration, login and refresh. Orchestrates {@link UserRepository},
 * {@link SessionService} and {@link JwtService} — holds no token or session logic
 * of its own.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository users;
    private final SessionService sessions;
    private final JwtService jwt;
    private final PasswordEncoder passwordEncoder;
    private final JwtProperties jwtProperties;

    /**
     * Creates an account and logs it straight in.
     * <p>
     * The uniqueness pre-checks are for a decent error message, not for correctness:
     * two concurrent registrations can both pass them. The unique indexes from V1
     * are the actual guarantee, which is why the insert is wrapped — losing that
     * race must surface as a 409, not a 500.
     */
    @Transactional
    public TokenPair register(String username, String email, String rawPassword, RequestContext context) {
        String normalisedEmail = normalise(email);

        if (users.existsByEmail(normalisedEmail)) {
            throw new AuthExceptions.EmailAlreadyRegistered(normalisedEmail);
        }
        if (users.existsByUsername(username)) {
            throw new AuthExceptions.UsernameTaken(username);
        }

        User user = User.withPassword(username, normalisedEmail, passwordEncoder.encode(rawPassword));
        try {
            user = users.saveAndFlush(user);
        } catch (DataIntegrityViolationException ex) {
            log.debug("Registration lost the uniqueness race for {}", normalisedEmail);
            throw new AuthExceptions.EmailAlreadyRegistered(normalisedEmail);
        }

        log.info("Registered user {}", user.getId());
        return issueTokens(user, context);
    }

    /**
     * Verifies credentials and opens a session.
     * <p>
     * Every failure path throws the same {@link AuthExceptions.InvalidCredentials}.
     * Distinguishing "no such email" from "wrong password" would let anyone test
     * whether an address has an account here.
     */
    @Transactional
    public TokenPair login(String email, String rawPassword, RequestContext context) {
        User user = users.findByEmail(normalise(email))
                .orElseThrow(AuthExceptions.InvalidCredentials::new);

        // An SSO-only account has no hash. Comparing against null would throw, and
        // more importantly there is nothing valid to compare against.
        if (!user.hasPassword() || !passwordEncoder.matches(rawPassword, user.getPasswordHash())) {
            throw new AuthExceptions.InvalidCredentials();
        }
        if (!user.isEnabled()) {
            throw new AuthExceptions.InvalidCredentials();
        }

        return issueTokens(user, context);
    }

    /**
     * Exchanges a refresh token for a new pair, rotating the session.
     * <p>
     * Roles are re-read from the user row rather than carried over from the old
     * token, so a role granted or revoked mid-session takes effect here instead of
     * being frozen in for the token's remaining lifetime.
     */
    // noRollbackFor must be repeated here, not only on SessionService.rotate.
    // rotate() joins this transaction rather than starting its own, so when
    // SessionReplayDetected propagates out, this outer boundary applies its own
    // rollback rules — and the default would discard the revocation that rotate()
    // just wrote. Both levels have to agree for the security side effect to commit.
    @Transactional(noRollbackFor = AuthExceptions.SessionReplayDetected.class)
    public TokenPair refresh(String refreshToken) {
        RefreshTokenClaims claims = jwt.parseRefreshToken(refreshToken);

        Session session = sessions.rotate(claims.sessionId(), claims.version());
        User user = users.findWithRolesById(session.getUser().getId())
                .orElseThrow(() -> new AuthExceptions.UserNotFound(session.getUser().getId()));

        return new TokenPair(
                jwt.generateAccessToken(user, session.getId()),
                jwt.generateRefreshToken(session.getId(), session.getTokenVersion()),
                jwtProperties.accessTokenTtl());
    }

    /**
     * Ends one session. The gateway stops honouring its access token via Redis.
     * <p>
     * Does not rotate: possession of a verifiable refresh token for the session is
     * enough to end it, and revocation is idempotent. Routing logout through
     * rotation would mean a user who clicks logout twice, or whose retry arrives
     * late, trips replay detection on a session they were closing anyway.
     */
    @Transactional
    public void logout(String refreshToken) {
        RefreshTokenClaims claims = jwt.parseRefreshToken(refreshToken);
        sessions.revokeById(claims.sessionId());
    }

    /**
     * Opens a session for an already-authenticated user and mints its first token
     * pair.
     * <p>
     * Public because {@link OAuthService} ends its flow here too. Whoever calls this
     * has taken on the job of proving the user is who they say they are — a password
     * check, or a completed provider handshake. Everything after that point must be
     * identical for both, or the two sign-in routes would drift into producing
     * differently-shaped sessions.
     */
    @Transactional
    public TokenPair issueTokens(User user, RequestContext context) {
        Session session = sessions.open(user, context.userAgent(), context.ipAddress());
        return new TokenPair(
                jwt.generateAccessToken(user, session.getId()),
                jwt.generateRefreshToken(session.getId(), session.getTokenVersion()),
                jwtProperties.accessTokenTtl());
    }

    /** Emails are matched case-insensitively; store one canonical form. */
    private static String normalise(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }
}
