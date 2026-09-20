package com.mugen.user.dto;

import com.mugen.user.entity.UserProfile;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * @param displayName what other people see; the username stays what mugen-auth issued
 * @param bio         optional; blank clears it
 */
public record UpdateProfileRequest(
        @NotBlank @Size(max = UserProfile.DISPLAY_NAME_MAX) String displayName,
        @Size(max = UserProfile.BIO_MAX) String bio
) {
}
