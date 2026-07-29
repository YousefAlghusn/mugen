package com.mugen.shared.auth;

import java.util.List;
import java.util.UUID;

/**
 * Access token claims (RS256, 15 min TTL). The refresh token carries only
 * {sessionId, version} and is not modeled here since it's never parsed
 * outside mugen-auth's rotation logic.
 */
public record JwtClaims(UUID userId, UUID sessionId, List<String> roles) {
}
