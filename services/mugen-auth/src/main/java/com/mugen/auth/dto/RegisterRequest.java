package com.mugen.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * @param username 3-50 characters; letters, digits, underscore, dot and hyphen
 * @param email must be unique across accounts, and is matched case-insensitively
 * @param password 8-72 characters. Capped at 72 because BCrypt hashes only the first 72
 * bytes and silently ignores the rest, so without the cap two long passwords sharing a
 * prefix would both authenticate. The lower bound follows NIST guidance: length matters,
 * forced character-composition rules do not.
 */
public record RegisterRequest(

        @NotBlank
        @Size(min = 3, max = 50)
        @Pattern(regexp = "^[a-zA-Z0-9_.-]+$",
                message = "may contain only letters, digits, underscore, dot and hyphen")
        String username,

        @NotBlank
        @Email
        @Size(max = 320)
        String email,

        @NotBlank
        @Size(min = 8, max = 72, message = "must be between 8 and 72 characters")
        String password
) {
}
