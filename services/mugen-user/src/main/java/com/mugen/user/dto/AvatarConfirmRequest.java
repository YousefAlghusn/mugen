package com.mugen.user.dto;

import jakarta.validation.constraints.NotBlank;

/** @param objectKey the key from the upload response, now holding the file */
public record AvatarConfirmRequest(@NotBlank String objectKey) {
}
