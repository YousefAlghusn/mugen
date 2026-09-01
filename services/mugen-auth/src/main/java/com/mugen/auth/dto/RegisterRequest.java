package com.mugen.auth.dto;

import com.mugen.auth.validation.MaxUtf8Bytes;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * @param username 3-50 characters; letters, digits, underscore, dot and hyphen
 * @param email must be unique across accounts, and is matched case-insensitively
 * @param password at least 8 characters and at most 72 bytes. The upper bound is bytes,
 * not characters, because BCrypt hashes only the first 72 bytes and ignores the rest — a
 * char-count cap would let a multi-byte password past it, and two passwords sharing a
 * 72-byte prefix would both authenticate. The lower bound follows NIST guidance: length
 * matters, forced character-composition rules do not.
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
        @Size(min = 8, message = "must be at least 8 characters")
        @MaxUtf8Bytes(72)
        String password
) {
}
