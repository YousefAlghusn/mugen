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

/** Registration, login and refresh. Holds no token or session logic of its own. */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository users;
    private final SessionService sessionService;
    private final JwtService jwtService;
    private final PasswordEncoder passwordEncoder;
    private final JwtProperties jwtProperties;
    private final UserEventPublisher userEventPublisher;

    /**
     * Creates an account and logs it straight in.
     * <p>
     * The pre-checks buy a decent error message, not correctness — two concurrent
     * registrations can both pass them. V1's unique indexes are the real guarantee,
     * so losing that race must surface as a 409 rather than a 500.
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
            // No identifier to log: there is no user row, and the email is PII.
            log.debug("Registration lost the uniqueness race on the email or username index");
            throw new AuthExceptions.EmailAlreadyRegistered(normalisedEmail);
        }

        // In this transaction, so the account and the announcement of it commit
        // together. mugen-user builds the profile from this event.
        userEventPublisher.userRegistered(user);

        log.info("Registered account userId={}", user.getId());
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

        // An SSO-only account has no hash to compare against.
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
    // noRollbackFor must be repeated here, not only on SessionService.rotate: rotate()
    // joins this transaction, so this outer boundary decides whether the revocation
    // it wrote commits. Both levels have to agree.
    @Transactional(noRollbackFor = AuthExceptions.SessionReplayDetected.class)
    public TokenPair refresh(String refreshToken) {
        RefreshTokenClaims claims = jwtService.parseRefreshToken(refreshToken);

        Session session = sessionService.rotate(claims.sessionId(), claims.version());
        User user = users.findWithRolesById(session.getUser().getId())
                .orElseThrow(() -> new AuthExceptions.UserNotFound(session.getUser().getId()));

        return new TokenPair(
                jwtService.generateAccessToken(user, session.getId()),
                jwtService.generateRefreshToken(session.getId(), session.getTokenVersion()),
                jwtProperties.accessTokenTtl());
    }

    /**
     * Ends one session. The gateway stops honouring its access token via Redis.
     * <p>
     * Deliberately does not rotate: a double-clicked logout would otherwise trip
     * replay detection on a session that was being closed anyway.
     */
    @Transactional
    public void logout(String refreshToken) {
        RefreshTokenClaims claims = jwtService.parseRefreshToken(refreshToken);
        sessionService.revokeById(claims.sessionId());
    }

    /**
     * Opens a session for an already-authenticated user and mints its first tokens.
     * <p>
     * Public because {@link OAuthService} ends its flow here too — the caller has
     * already proved identity, and both sign-in routes must converge here or they
     * drift into producing differently-shaped sessions.
     */
    @Transactional
    public TokenPair issueTokens(User user, RequestContext context) {
        Session session = sessionService.open(user, context.userAgent(), context.ipAddress());
        return new TokenPair(
                jwtService.generateAccessToken(user, session.getId()),
                jwtService.generateRefreshToken(session.getId(), session.getTokenVersion()),
                jwtProperties.accessTokenTtl());
    }

    /** Emails are matched case-insensitively; store one canonical form. */
    private static String normalise(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }
}
