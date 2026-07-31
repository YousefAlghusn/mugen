package com.mugen.auth.exception;

import com.mugen.shared.error.ErrorCode;
import com.mugen.web.error.BusinessRuleException;
import com.mugen.web.error.ConflictException;
import com.mugen.web.error.ResourceNotFoundException;
import com.mugen.web.error.UnauthorizedException;

import java.util.UUID;

/**
 * mugen-auth's typed failures, on top of the shared hierarchy in mugen-web.
 * <p>
 * Grouped as nested classes because they are small, closely related, and always
 * read together — a reader gets the whole failure vocabulary of this service in one
 * screen rather than eight near-empty files.
 */
public final class AuthExceptions {

    private AuthExceptions() {
    }

    /**
     * Deliberately says "email or password", never which one was wrong. Telling the
     * caller that the email exists but the password failed turns login into an
     * account-enumeration oracle.
     */
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
     * The account exists and the credential was right, but the account is switched
     * off. Separate from {@link InvalidCredentials} because there is no enumeration
     * risk left to protect against — the caller has already proved they own it.
     */
    public static class AccountDisabled extends UnauthorizedException {
        public AccountDisabled() {
            super(ErrorCode.ACCOUNT_DISABLED, "This account has been disabled.");
        }
    }

    /**
     * The provider is known to the code but has no client credentials configured, so
     * no {@code ClientRegistration} exists for it — see the {@code sso} profile.
     */
    public static class SsoProviderNotConfigured extends ResourceNotFoundException {
        public SsoProviderNotConfigured(String provider) {
            super(ErrorCode.SSO_PROVIDER_NOT_CONFIGURED,
                    "SSO provider %s is not available.".formatted(provider));
        }
    }

    /**
     * The {@code state} on a callback did not match a pending authorization.
     * <p>
     * Expected for a stale bookmark or a back-button replay, since state is
     * single-use — but it is also exactly what a forged callback looks like, which
     * is the reason state exists. Both are refused identically.
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
     * The provider returned no usable email address — GitHub does this when every
     * address on the account is private and the {@code user:email} scope was denied.
     * An account cannot be created without one: mugen-user keys off it.
     */
    public static class SsoEmailUnavailable extends BusinessRuleException {
        public SsoEmailUnavailable(String provider) {
            super(ErrorCode.SSO_EMAIL_UNAVAILABLE,
                    "%s did not share a verified email address for this account.".formatted(provider));
        }
    }

    /**
     * The provider shared an email it has not verified.
     * <p>
     * Refused rather than trusted, because an unverified address is only a claim.
     * If it were honoured, anyone could put someone else's address on a throwaway
     * provider account and either take over the matching mugen account or squat the
     * address before its real owner registers.
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
     * Post-login redirect target was not on the allowlist. Without this check the
     * SSO entry point is an open redirect: an attacker sends a victim to a genuine
     * mugen URL and has us bounce them to a look-alike site — carrying, worse, a
     * freshly minted session.
     */
    public static class SsoRedirectNotAllowed extends BusinessRuleException {
        public SsoRedirectNotAllowed(String redirectUri) {
            super(ErrorCode.SSO_REDIRECT_NOT_ALLOWED, "Redirect target %s is not allowed.".formatted(redirectUri));
        }
    }

    /**
     * Raised when a refresh token arrives carrying an already-rotated version.
     * <p>
     * The message stays generic on purpose. Confirming to the caller that replay was
     * detected tells an attacker their captured token was spent and that they should
     * move faster next time; the useful detail belongs in the log and the audit
     * trail, not the response.
     */
    public static class SessionReplayDetected extends UnauthorizedException {
        public SessionReplayDetected() {
            super(ErrorCode.SESSION_REPLAY_DETECTED, "Session is no longer valid.");
        }
    }
}
