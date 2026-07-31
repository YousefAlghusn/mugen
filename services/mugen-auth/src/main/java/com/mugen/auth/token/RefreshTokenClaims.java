package com.mugen.auth.token;

import java.util.UUID;

/**
 * The whole payload of a refresh token.
 * <p>
 * Deliberately minimal — no userId, no roles. The refresh token is long-lived and
 * sits in a cookie, so it carries only what is needed to look the session up. Roles
 * are re-read from the database on every rotation, which means a role revoked
 * mid-session takes effect at the next refresh instead of being frozen into a
 * 30-day token.
 */
public record RefreshTokenClaims(UUID sessionId, int version) {
}
