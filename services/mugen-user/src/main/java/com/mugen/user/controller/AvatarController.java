package com.mugen.user.controller;

import com.mugen.user.dto.AvatarConfirmRequest;
import com.mugen.user.dto.AvatarUploadRequest;
import com.mugen.user.dto.AvatarUploadResponse;
import com.mugen.user.exception.UserExceptions;
import com.mugen.user.service.AvatarService;
import com.mugen.web.openapi.Throws;
import com.mugen.web.security.CurrentUser;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/users/me/avatar")
@RequiredArgsConstructor
@Tag(name = "Avatar", description = """
        Two steps, and the file never passes through this service: ask for an upload URL, \
        PUT the image straight to it, then confirm. The URL is presigned for one object, \
        one content type and a few minutes.""")
public class AvatarController {

    private final AvatarService avatarService;

    /**
     * Get a URL to upload your avatar to.
     *
     * <p>PUT the file to {@code uploadUrl} with exactly the {@code Content-Type} you asked
     * for, then call {@code confirm} with {@code objectKey}. Nothing changes until you do.
     */
    @PostMapping
    @Throws(UserExceptions.ProfileNotFound.class)
    public AvatarUploadResponse requestUpload(@AuthenticationPrincipal Jwt token,
                                              @Valid @RequestBody AvatarUploadRequest request) {
        return avatarService.requestUpload(CurrentUser.userId(token), request.contentType());
    }

    /**
     * Make the uploaded object your avatar.
     *
     * <p>Checks the object is really there before recording it, and deletes the one it
     * replaces. Profile responses carry the new URL from the next request on.
     */
    @PostMapping("/confirm")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Throws({UserExceptions.ProfileNotFound.class, UserExceptions.AvatarNotUploaded.class,
            UserExceptions.AvatarKeyNotOwned.class})
    public void confirm(@AuthenticationPrincipal Jwt token, @Valid @RequestBody AvatarConfirmRequest request) {
        avatarService.confirmUpload(CurrentUser.userId(token), request.objectKey());
    }
}
