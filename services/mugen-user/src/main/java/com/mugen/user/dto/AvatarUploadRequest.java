package com.mugen.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** @param contentType the image type being uploaded; decides the extension and is pinned on the URL */
public record AvatarUploadRequest(
        @NotBlank @Pattern(regexp = "image/(png|jpeg|webp)", message = "must be image/png, image/jpeg or image/webp")
        String contentType
) {
}
