package com.mugen.auth.service;

import com.mugen.auth.config.JwtProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Publishes session revocations to Redis so the gateway can honour them.
 * <p>
 * This exists because of an unavoidable gap in stateless auth: an access token is
 * self-validating, so revoking a session in SQL Server has no effect on tokens
 * already in the wild — the gateway never reads that database. Without this, a
 * logged-out user would stay authorised for up to 15 more minutes.
 * <p>
 * The entry's TTL equals the access-token lifetime, and no longer. After that the
 * token has expired on its own and the record would be dead weight; {@link
 * JwtProperties} enforces the relationship at startup.
 * <p>
 * The gateway reads these keys on every request, which is a Redis lookup rather
 * than a database call — see CLAUDE.md, "Gateway validates token with public.pem —
 * zero DB calls".
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RevocationCacheService {

    /** Shared with mugen-gateway. Changing it here breaks revocation silently. */
    private static final String KEY_PREFIX = "mugen:auth:revoked-session:";

    private final StringRedisTemplate redis;
    private final JwtProperties jwtProperties;

    public void revoke(UUID sessionId) {
        redis.opsForValue().set(key(sessionId), "1", jwtProperties.revocationCacheTtl());
        log.debug("Marked session revoked sessionId={} ttl={}", sessionId, jwtProperties.revocationCacheTtl());
    }

    public void revokeAll(Iterable<UUID> sessionIds) {
        sessionIds.forEach(this::revoke);
    }

    public boolean isRevoked(UUID sessionId) {
        return Boolean.TRUE.equals(redis.hasKey(key(sessionId)));
    }

    private static String key(UUID sessionId) {
        return KEY_PREFIX + sessionId;
    }
}
