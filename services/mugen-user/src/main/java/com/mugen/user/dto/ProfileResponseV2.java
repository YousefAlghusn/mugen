package com.mugen.user.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * The v2 profile: v1 plus the follow counts. A new version rather than new fields on
 * v1 because the counts change what a client renders, and a v1 client that ignores
 * them is not the same as one that was never told. Both are served from one entity.
 */
public record ProfileResponseV2(
        UUID userId,
        String username,
        String displayName,
        String bio,
        String avatarUrl,
        int followerCount,
        int followingCount,
        Instant createdAt
) {
}
