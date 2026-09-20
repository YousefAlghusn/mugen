package com.mugen.user.exception;

import com.mugen.shared.error.ErrorCode;
import com.mugen.web.error.ApiError;
import com.mugen.web.error.BusinessRuleException;
import com.mugen.web.error.ConflictException;
import com.mugen.web.error.ForbiddenException;
import com.mugen.web.error.ResourceNotFoundException;

import java.util.UUID;

/**
 * mugen-user's typed failures, on top of the shared hierarchy in mugen-web. Nested so
 * the whole failure vocabulary of the service reads in one screen.
 */
public final class UserExceptions {

    private UserExceptions() {
    }

    @ApiError(code = ErrorCode.USER_NOT_FOUND,
            description = "No profile exists for that id or username. An account registered moments ago "
                    + "may not have its profile yet — it arrives by event, not in the same request.")
    public static class ProfileNotFound extends ResourceNotFoundException {
        public ProfileNotFound(UUID userId) {
            super("No profile for user %s.".formatted(userId));
        }

        public ProfileNotFound(String username) {
            super("No profile with username %s.".formatted(username));
        }
    }

    @ApiError(code = ErrorCode.ALREADY_FOLLOWING, description = "The caller already follows that user.")
    public static class AlreadyFollowing extends ConflictException {
        public AlreadyFollowing(UUID followeeId) {
            super("Already following user %s.".formatted(followeeId));
        }
    }

    @ApiError(code = ErrorCode.FOLLOW_NOT_FOUND, description = "The caller does not follow that user.")
    public static class FollowNotFound extends ResourceNotFoundException {
        public FollowNotFound(UUID followeeId) {
            super("Not following user %s.".formatted(followeeId));
        }
    }

    @ApiError(code = ErrorCode.BUSINESS_RULE_VIOLATION, description = "A user cannot follow themselves.")
    public static class CannotFollowSelf extends BusinessRuleException {
        public CannotFollowSelf() {
            super("You cannot follow yourself.");
        }
    }

    /**
     * The confirm step found nothing at the key the upload URL was issued for: the PUT
     * never happened, went elsewhere, or the URL expired first.
     */
    @ApiError(code = ErrorCode.UPLOAD_NOT_FOUND,
            description = "Nothing has been uploaded to that key. Request a new upload URL and PUT the file "
                    + "to it before confirming.")
    public static class AvatarNotUploaded extends BusinessRuleException {
        public AvatarNotUploaded() {
            super("No upload found at that key.");
        }
    }

    /** The key names an object outside the caller's own avatar prefix — someone else's, or a guess. */
    @ApiError(code = ErrorCode.FORBIDDEN, description = "That upload key does not belong to the caller.")
    public static class AvatarKeyNotOwned extends ForbiddenException {
        public AvatarKeyNotOwned() {
            super("That upload key is not yours.");
        }
    }
}
