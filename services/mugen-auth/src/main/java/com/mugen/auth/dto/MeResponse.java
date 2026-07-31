package com.mugen.auth.dto;

import java.util.List;
import java.util.UUID;

/**
 * Identity as the token asserts it. Answered from the token alone, with no
 * database read — the point of {@code /me} is for a client to learn who it is
 * without the auth service becoming a dependency of every page load.
 */
public record MeResponse(UUID userId, UUID sessionId, List<String> roles) {
}
