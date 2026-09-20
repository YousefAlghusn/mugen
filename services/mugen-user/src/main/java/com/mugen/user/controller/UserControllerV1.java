package com.mugen.user.controller;

import com.mugen.shared.response.CursorPage;
import com.mugen.user.dto.FollowSummary;
import com.mugen.user.dto.ProfileResponse;
import com.mugen.user.dto.UpdateProfileRequest;
import com.mugen.user.entity.UserProfile;
import com.mugen.user.exception.UserExceptions;
import com.mugen.user.pagination.Cursor;
import com.mugen.user.service.AvatarService;
import com.mugen.user.service.FollowService;
import com.mugen.user.service.UserService;
import com.mugen.web.openapi.Throws;
import com.mugen.web.security.CurrentUser;
import com.mugen.web.security.PublicEndpoint;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
@Tag(name = "Users", description = """
        Profiles and follows. Reading is public; anything that changes state is scoped to \
        the caller's own account, taken from the access token — there is no "as user X" \
        anywhere here, deliberately.""")
public class UserControllerV1 {

    private final UserService userService;
    private final FollowService followService;
    private final AvatarService avatarService;

    /**
     * Your own profile.
     *
     * <p>Answers 404 for an account whose profile has not arrived yet: the profile is
     * created from mugen-auth's registration event, moments after the account.
     */
    @GetMapping("/me")
    @Throws(UserExceptions.ProfileNotFound.class)
    public ProfileResponse me(@AuthenticationPrincipal Jwt token) {
        return toResponse(userService.getProfile(CurrentUser.userId(token)));
    }

    /**
     * Change your display name and bio. The username is mugen-auth's and does not change here.
     */
    @PatchMapping("/me")
    @Throws(UserExceptions.ProfileNotFound.class)
    public ProfileResponse updateMe(@AuthenticationPrincipal Jwt token, @Valid @RequestBody UpdateProfileRequest request) {
        return toResponse(userService.updateProfile(CurrentUser.userId(token), request.displayName(), request.bio()));
    }

    /** A profile by user id. */
    @GetMapping("/{userId}")
    @PublicEndpoint
    @Throws(UserExceptions.ProfileNotFound.class)
    public ProfileResponse byId(@PathVariable UUID userId) {
        return toResponse(userService.getProfile(userId));
    }

    /** A profile by username. */
    @GetMapping("/by-username/{username}")
    @PublicEndpoint
    @Throws(UserExceptions.ProfileNotFound.class)
    public ProfileResponse byUsername(@PathVariable String username) {
        return toResponse(userService.getProfile(username));
    }

    /**
     * Who follows this user, newest first.
     *
     * @param cursor from the previous page's {@code nextCursor}; omit for the first page
     * @param limit  page size, at most {@value FollowService#MAX_PAGE_SIZE}
     */
    @GetMapping("/{userId}/followers")
    @PublicEndpoint
    @Throws({UserExceptions.ProfileNotFound.class, Cursor.InvalidCursor.class})
    public CursorPage<FollowSummary> followers(@PathVariable UUID userId,
                                               @RequestParam(required = false) String cursor,
                                               @RequestParam(required = false) Integer limit) {
        return followService.followers(userId, cursor, limit);
    }

    /** Who this user follows, newest first. Paginated like {@code followers}. */
    @GetMapping("/{userId}/following")
    @PublicEndpoint
    @Throws({UserExceptions.ProfileNotFound.class, Cursor.InvalidCursor.class})
    public CursorPage<FollowSummary> following(@PathVariable UUID userId,
                                               @RequestParam(required = false) String cursor,
                                               @RequestParam(required = false) Integer limit) {
        return followService.following(userId, cursor, limit);
    }

    /** Follow a user. Following someone you already follow is a 409, not a second follow. */
    @PostMapping("/{userId}/follow")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Throws({UserExceptions.ProfileNotFound.class, UserExceptions.AlreadyFollowing.class,
            UserExceptions.CannotFollowSelf.class})
    public void follow(@AuthenticationPrincipal Jwt token, @PathVariable UUID userId) {
        followService.follow(CurrentUser.userId(token), userId);
    }

    /** Stop following a user. */
    @DeleteMapping("/{userId}/follow")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Throws(UserExceptions.FollowNotFound.class)
    public void unfollow(@AuthenticationPrincipal Jwt token, @PathVariable UUID userId) {
        followService.unfollow(CurrentUser.userId(token), userId);
    }

    private ProfileResponse toResponse(UserProfile profile) {
        return new ProfileResponse(
                profile.getId(),
                profile.getUsername(),
                profile.getDisplayName(),
                profile.getBio(),
                avatarService.urlFor(profile),
                profile.getCreatedAt());
    }
}
