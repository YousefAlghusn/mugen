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
 * Closes the unavoidable gap in stateless auth: an access token is self-validating, so
 * revoking a session in SQL Server does nothing for tokens already in the wild — the
 * gateway never reads that database. The entry's TTL equals the access-token lifetime,
 * after which the token has expired anyway; {@link JwtProperties} enforces that at
 * startup.
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
