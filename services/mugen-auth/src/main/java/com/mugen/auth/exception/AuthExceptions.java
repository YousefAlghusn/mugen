package com.mugen.auth.exception;

import com.mugen.shared.error.ErrorCode;
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
