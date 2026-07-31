package com.mugen.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * No {@code @Email} or length constraints on the way in beyond a sanity cap.
 * Validating the shape of a login attempt tells an attacker which inputs are
 * even worth trying, and a wrong password must look identical to a malformed
 * one from the outside.
 */
public record LoginRequest(

        @NotBlank
        @Size(max = 320)
        String email,

        @NotBlank
        @Size(max = 72)
        String password
) {
}
