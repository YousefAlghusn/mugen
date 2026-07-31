package com.mugen.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * @param password bounded at 72 because BCrypt operates on the first 72 <em>bytes</em>
 *                 and silently ignores the rest. Without the cap, two different long
 *                 passwords sharing a 72-byte prefix would both authenticate — and the
 *                 user would never be told their password was effectively shortened.
 *                 The lower bound follows current NIST guidance: length matters,
 *                 forced character-composition rules do not.
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
