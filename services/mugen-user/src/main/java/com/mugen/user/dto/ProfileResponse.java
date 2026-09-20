package com.mugen.user.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * A profile as v1 clients see it.
 *
 * @param avatarUrl a presigned GET URL, valid for about an hour, or null when no avatar
 *                  has been uploaded. Never store it: fetch the profile again instead
 */
public record ProfileResponse(
        UUID userId,
        String username,
        String displayName,
        String bio,
        String avatarUrl,
        Instant createdAt
) {
}
