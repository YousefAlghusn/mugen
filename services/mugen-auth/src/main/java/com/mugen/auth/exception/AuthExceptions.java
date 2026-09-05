package com.mugen.auth.exception;

import com.mugen.shared.error.ErrorCode;
import com.mugen.web.error.ApiError;
import com.mugen.web.error.BusinessRuleException;
import com.mugen.web.error.ConflictException;
import com.mugen.web.error.ResourceNotFoundException;
import com.mugen.web.error.TokenInvalidException;
import com.mugen.web.error.UnauthorizedException;

import java.util.UUID;

/**
 * mugen-auth's typed failures, on top of the shared hierarchy in mugen-web. Nested so
 * the whole failure vocabulary of the service reads in one screen.
 * <p>
 * Each {@code @ApiError} description is what the API document publishes wherever the
 * failure can occur, so it is written for a caller integrating against the endpoint;
 * the constructor's message is what that one caller is told at the time.
 */
public final class AuthExceptions {

    private AuthExceptions() {
    }

    /** Never says which of the two was wrong — that would be an enumeration oracle. */
    @ApiError(code = ErrorCode.INVALID_CREDENTIALS,
            description = "The email or the password is wrong. Which one is never reported: answering "
                    + "would let anyone test whether an address has an account here.")
    public static class InvalidCredentials extends UnauthorizedException {
        public InvalidCredentials() {
            super("Invalid email or password.");
        }
    }

    @ApiError(code = ErrorCode.EMAIL_ALREADY_REGISTERED,
            description = "That address already has an account. Sign in instead, or register another.")
    public static class EmailAlreadyRegistered extends ConflictException {
        public EmailAlreadyRegistered(String email) {
            super("Email %s is already registered.".formatted(email));
        }
    }

    @ApiError(code = ErrorCode.CONFLICT, description = "That username is taken.")
    public static class UsernameTaken extends ConflictException {
        public UsernameTaken(String username) {
            super("Username %s is already taken.".formatted(username));
        }
    }

    @ApiError(code = ErrorCode.USER_NOT_FOUND,
            description = "The token was valid, but the account it names no longer exists.")
    public static class UserNotFound extends ResourceNotFoundException {
        public UserNotFound(UUID userId) {
            super("No user with id %s.".formatted(userId));
        }
    }

    @ApiError(code = ErrorCode.SESSION_NOT_FOUND,
            description = "The session behind this token has ended — signed out, revoked, or expired. "
                    + "Reported as 401 rather than 404 so a caller cannot probe which session ids exist.")
    public static class SessionNotFound extends UnauthorizedException {
        public SessionNotFound() {
            // 401 rather than 404: whether a given sessionId exists is not something
            // an unauthenticated caller should be able to probe for.
            super("Session is no longer valid.");
        }
    }

    @ApiError(code = ErrorCode.TOKEN_REVOKED,
            description = "The session was revoked — by a sign-out elsewhere, or by replay detection.")
    public static class TokenRevoked extends UnauthorizedException {
        public TokenRevoked() {
            super("Session has been revoked.");
        }
    }

    /**
     * Extends the shared type rather than restating {@code TOKEN_INVALID}, so the code
     * a client branches on has one definition across every service. Only the wording
     * is this service's — it is the one place a refresh token is a distinct thing.
     */
    @ApiError(description = "The token is missing, malformed, expired, or of the wrong kind — an access "
            + "token where a refresh token belongs, or the reverse.")
    public static class TokenInvalid extends TokenInvalidException {
        public TokenInvalid(String reason) {
            super(reason);
        }
    }

    /**
     * Separate from {@link InvalidCredentials}: the caller has already proved they own
     * the account, so there is no enumeration risk left to protect against.
     */
    @ApiError(code = ErrorCode.ACCOUNT_DISABLED,
            description = "The account exists and the credentials were right, but it has been disabled.")
    public static class AccountDisabled extends UnauthorizedException {
        public AccountDisabled() {
            super("This account has been disabled.");
        }
    }

    /** Known to the code but with no credentials configured — see the {@code sso} profile. */
    @ApiError(code = ErrorCode.SSO_PROVIDER_NOT_CONFIGURED,
            description = "No such SSO provider, or one this deployment has no credentials for. The two "
                    + "are answered identically, so the response never maps which providers exist.")
    public static class SsoProviderNotConfigured extends ResourceNotFoundException {
        public SsoProviderNotConfigured(String provider) {
            super("SSO provider %s is not available.".formatted(provider));
        }
    }

    /**
     * The callback's {@code state} matched no pending authorization. A stale bookmark
     * and a forged callback look identical here, and are refused identically.
     */
    @ApiError(code = ErrorCode.SSO_STATE_INVALID,
            description = "The callback's `state` matched no pending sign-in: expired, already spent, or "
                    + "never issued. A stale bookmark and a forged callback are refused identically.")
    public static class SsoStateInvalid extends UnauthorizedException {
        public SsoStateInvalid() {
            super("This sign-in link has expired. Please start again.");
        }
    }

    /** The provider refused the code-for-token exchange, or could not be reached. */
    @ApiError(code = ErrorCode.SSO_EXCHANGE_FAILED,
            description = "The provider refused to exchange the authorization code, or could not be "
                    + "reached. Its own reason is logged and never echoed back.")
    public static class SsoExchangeFailed extends UnauthorizedException {
        public SsoExchangeFailed(String provider, Throwable cause) {
            super("Could not complete sign-in with %s.".formatted(provider), cause);
        }
    }

    /**
     * No usable email — a provider can withhold one when the account keeps its
     * addresses private. mugen-user keys off it, so an account needs one.
     */
    @ApiError(code = ErrorCode.SSO_EMAIL_UNAVAILABLE,
            description = "The provider shared no email address, which an account cannot be created "
                    + "without. Usually a privacy setting on the provider account.")
    public static class SsoEmailUnavailable extends BusinessRuleException {
        public SsoEmailUnavailable(String provider) {
            super("%s did not share a verified email address for this account.".formatted(provider));
        }
    }

    /**
     * Refused, not trusted: an unverified address is only a claim, and honouring one
     * would let anyone take over or pre-emptively squat the matching mugen account.
     */
    @ApiError(code = ErrorCode.SSO_EMAIL_NOT_VERIFIED,
            description = "The provider shared an address it has not verified. Trusting one would let "
                    + "anyone claim the mugen account belonging to an address they do not own.")
    public static class SsoEmailNotVerified extends BusinessRuleException {
        public SsoEmailNotVerified(String provider) {
            super("Verify your email address with %s, then try again.".formatted(provider));
        }
    }

    /**
     * Two first-ever sign-ins for one provider account, at once. Transient by nature:
     * the retry finds the link the winner wrote and signs in normally.
     */
    @ApiError(code = ErrorCode.SSO_SIGN_IN_CONFLICT,
            description = "Two sign-ins for the same provider account arrived at once and this one lost the "
                    + "race. Nothing was created; signing in again resolves it.")
    public static class SsoSignInConflict extends ConflictException {
        public SsoSignInConflict(String provider) {
            super("Another sign-in with the same %s account is already in progress. Please try again."
                    .formatted(provider));
        }
    }

    /** One provider per user — {@code uq_oauth_links_user_provider}. */
    @ApiError(code = ErrorCode.SSO_PROVIDER_ALREADY_LINKED,
            description = "This mugen account is already linked to a different account at that provider. "
                    + "One link per provider per user.")
    public static class SsoProviderAlreadyLinked extends ConflictException {
        public SsoProviderAlreadyLinked(String provider) {
            super("This account is already linked to a different %s account.".formatted(provider));
        }
    }

    /**
     * Without this check the SSO entry point is an open redirect, bouncing a victim
     * from a genuine mugen URL to a look-alike site with a freshly minted session.
     */
    @ApiError(code = ErrorCode.SSO_REDIRECT_NOT_ALLOWED,
            description = "`redirect_uri` is not on the allowlist. Unchecked, this endpoint would be an "
                    + "open redirect that hands a look-alike site a freshly minted session.")
    public static class SsoRedirectNotAllowed extends BusinessRuleException {
        public SsoRedirectNotAllowed(String redirectUri) {
            super("Redirect target %s is not allowed.".formatted(redirectUri));
        }
    }

    /**
     * A refresh token arrived at an already-rotated version. The message stays generic
     * on purpose: confirming detection tells an attacker their captured token was spent.
     */
    @ApiError(code = ErrorCode.SESSION_REPLAY_DETECTED,
            description = "A refresh token was presented at a version already spent, so two holders have "
                    + "the same token. The whole session is revoked, including the legitimate holder's.")
    public static class SessionReplayDetected extends UnauthorizedException {
        public SessionReplayDetected() {
            super("Session is no longer valid.");
        }
    }
}
