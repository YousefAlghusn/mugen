package com.mugen.auth.exception;

import com.mugen.shared.error.ErrorCode;
import com.mugen.web.error.BusinessRuleException;
import com.mugen.web.error.ConflictException;
import com.mugen.web.error.ResourceNotFoundException;
import com.mugen.web.error.UnauthorizedException;

import java.util.UUID;

/**
 * mugen-auth's typed failures, on top of the shared hierarchy in mugen-web. Nested so
 * the whole failure vocabulary of the service reads in one screen.
 */
public final class AuthExceptions {

    private AuthExceptions() {
    }

    /** Never says which of the two was wrong — that would be an enumeration oracle. */
    public static class InvalidCredentials extends UnauthorizedException {
        public InvalidCredentials() {
            super(ErrorCode.INVALID_CREDENTIALS, "Invalid email or password.");
        }
    }

    public static class EmailAlreadyRegistered extends ConflictException {
        public EmailAlreadyRegistered(String email) {
            super(ErrorCode.EMAIL_ALREADY_REGISTERED, "Email %s is already registered.".formatted(email));
        }
    }

    public static class UsernameTaken extends ConflictException {
        public UsernameTaken(String username) {
            super(ErrorCode.CONFLICT, "Username %s is already taken.".formatted(username));
        }
    }

    public static class UserNotFound extends ResourceNotFoundException {
        public UserNotFound(UUID userId) {
            super(ErrorCode.USER_NOT_FOUND, "No user with id %s.".formatted(userId));
        }
    }

    public static class SessionNotFound extends UnauthorizedException {
        public SessionNotFound() {
            // 401 rather than 404: whether a given sessionId exists is not something
            // an unauthenticated caller should be able to probe for.
            super(ErrorCode.SESSION_NOT_FOUND, "Session is no longer valid.");
        }
    }

    public static class TokenRevoked extends UnauthorizedException {
        public TokenRevoked() {
            super(ErrorCode.TOKEN_REVOKED, "Session has been revoked.");
        }
    }

    public static class TokenInvalid extends UnauthorizedException {
        public TokenInvalid(String reason) {
            super(ErrorCode.TOKEN_INVALID, reason);
        }
    }

    /**
     * Separate from {@link InvalidCredentials}: the caller has already proved they own
     * the account, so there is no enumeration risk left to protect against.
     */
    public static class AccountDisabled extends UnauthorizedException {
        public AccountDisabled() {
            super(ErrorCode.ACCOUNT_DISABLED, "This account has been disabled.");
        }
    }

    /** Known to the code but with no credentials configured — see the {@code sso} profile. */
    public static class SsoProviderNotConfigured extends ResourceNotFoundException {
        public SsoProviderNotConfigured(String provider) {
            super(ErrorCode.SSO_PROVIDER_NOT_CONFIGURED,
                    "SSO provider %s is not available.".formatted(provider));
        }
    }

    /**
     * The callback's {@code state} matched no pending authorization. A stale bookmark
     * and a forged callback look identical here, and are refused identically.
     */
    public static class SsoStateInvalid extends UnauthorizedException {
        public SsoStateInvalid() {
            super(ErrorCode.SSO_STATE_INVALID, "This sign-in link has expired. Please start again.");
        }
    }

    /** The provider refused the code-for-token exchange, or could not be reached. */
    public static class SsoExchangeFailed extends UnauthorizedException {
        public SsoExchangeFailed(String provider, Throwable cause) {
            super(ErrorCode.SSO_EXCHANGE_FAILED, "Could not complete sign-in with %s.".formatted(provider), cause);
        }
    }

    /**
     * No usable email — a provider can withhold one when the account keeps its
     * addresses private. mugen-user keys off it, so an account needs one.
     */
    public static class SsoEmailUnavailable extends BusinessRuleException {
        public SsoEmailUnavailable(String provider) {
            super(ErrorCode.SSO_EMAIL_UNAVAILABLE,
                    "%s did not share a verified email address for this account.".formatted(provider));
        }
    }

    /**
     * Refused, not trusted: an unverified address is only a claim, and honouring one
     * would let anyone take over or pre-emptively squat the matching mugen account.
     */
    public static class SsoEmailNotVerified extends BusinessRuleException {
        public SsoEmailNotVerified(String provider) {
            super(ErrorCode.SSO_EMAIL_NOT_VERIFIED,
                    "Verify your email address with %s, then try again.".formatted(provider));
        }
    }

    /** One provider per user — {@code uq_oauth_links_user_provider}. */
    public static class SsoProviderAlreadyLinked extends ConflictException {
        public SsoProviderAlreadyLinked(String provider) {
            super(ErrorCode.SSO_PROVIDER_ALREADY_LINKED,
                    "This account is already linked to a different %s account.".formatted(provider));
        }
    }

    /**
     * Without this check the SSO entry point is an open redirect, bouncing a victim
     * from a genuine mugen URL to a look-alike site with a freshly minted session.
     */
    public static class SsoRedirectNotAllowed extends BusinessRuleException {
        public SsoRedirectNotAllowed(String redirectUri) {
            super(ErrorCode.SSO_REDIRECT_NOT_ALLOWED, "Redirect target %s is not allowed.".formatted(redirectUri));
        }
    }

    /**
     * A refresh token arrived at an already-rotated version. The message stays generic
     * on purpose: confirming detection tells an attacker their captured token was spent.
     */
    public static class SessionReplayDetected extends UnauthorizedException {
        public SessionReplayDetected() {
            super(ErrorCode.SESSION_REPLAY_DETECTED, "Session is no longer valid.");
        }
    }
}
