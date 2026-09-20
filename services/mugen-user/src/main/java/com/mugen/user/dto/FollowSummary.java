package com.mugen.user.dto;

import java.time.Instant;
import java.util.UUID;

/** One entry in a followers or following list. */
public record FollowSummary(UUID userId, String username, String displayName, Instant followedAt) {
}
