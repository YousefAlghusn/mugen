package com.mugen.user.controller;

import com.mugen.user.dto.ProfileResponseV2;
import com.mugen.user.entity.UserProfile;
import com.mugen.user.exception.UserExceptions;
import com.mugen.user.service.AvatarService;
import com.mugen.user.service.UserService;
import com.mugen.web.openapi.Throws;
import com.mugen.web.security.CurrentUser;
import com.mugen.web.security.PublicEndpoint;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * The v2 profile reads: the same service and rows as v1, a richer document. Versioned
 * at the URL because the response shape is the contract; v1 keeps answering unchanged
 * for clients built against it, and everything that writes stays on v1.
 */
@RestController
@RequestMapping("/api/v2/users")
@RequiredArgsConstructor
@Tag(name = "Users v2", description = "Profiles with follow counts. Same data as v1, more of it.")
public class UserControllerV2 {

    private final UserService userService;
    private final AvatarService avatarService;

    /** Your own profile, with counts. */
    @GetMapping("/me")
    @Throws(UserExceptions.ProfileNotFound.class)
    public ProfileResponseV2 me(@AuthenticationPrincipal Jwt token) {
        return toResponse(userService.getProfile(CurrentUser.userId(token)));
    }

    /** A profile by user id, with counts. */
    @GetMapping("/{userId}")
    @PublicEndpoint
    @Throws(UserExceptions.ProfileNotFound.class)
    public ProfileResponseV2 byId(@PathVariable UUID userId) {
        return toResponse(userService.getProfile(userId));
    }

    private ProfileResponseV2 toResponse(UserProfile profile) {
        return new ProfileResponseV2(
                profile.getId(),
                profile.getUsername(),
                profile.getDisplayName(),
                profile.getBio(),
                avatarService.urlFor(profile),
                profile.getFollowerCount(),
                profile.getFollowingCount(),
                profile.getCreatedAt());
    }
}
