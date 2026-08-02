package com.mugen.auth.oauth;

import com.mugen.auth.config.SsoProperties;
import com.mugen.auth.entity.OAuthProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Holds in-flight SSO authorizations, keyed by their {@code state} value.
 * <p>
 * Redis rather than the HTTP session for the reason mugen-auth has no HTTP session
 * at all (see {@code SecurityConfig}: {@code SessionCreationPolicy.STATELESS}), and
 * because any instance behind the gateway may receive the callback for a flow a
 * different instance started.
 * <p>
 * Entries are <em>single-use</em>. That is what makes the {@code state} parameter do
 * its job: an attacker who captures a callback URL — from a browser history, a
 * referrer header, a proxy log — cannot replay it, because the first legitimate use
 * already consumed the entry. They also expire on their own, so an abandoned
 * sign-in leaves nothing behind.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuthorizationRequestStore {

    private static final String KEY_PREFIX = "mugen:auth:sso-state:";
    private static final String FIELD_PROVIDER = "provider";
    private static final String FIELD_CODE_VERIFIER = "codeVerifier";
    private static final String FIELD_REDIRECT_URI = "redirectUri";
    private static final String FIELD_BROWSER_NONCE = "browserNonce";

    private final StringRedisTemplate redis;
    private final SsoProperties ssoProperties;

    public void save(String state, PendingAuthorization pending) {
        Map<String, String> fields = new HashMap<>();
        fields.put(FIELD_PROVIDER, pending.provider().name());
        fields.put(FIELD_CODE_VERIFIER, pending.codeVerifier());
        fields.put(FIELD_REDIRECT_URI, pending.redirectUri());
        fields.put(FIELD_BROWSER_NONCE, pending.browserNonce());

        String key = key(state);
        redis.opsForHash().putAll(key, fields);
        redis.expire(key, ssoProperties.stateTtl());
    }

    /**
     * Reads and destroys the entry for {@code state}.
     *
     * @return the pending authorization, or empty if there was none — expired,
     *         forged, or already used
     */
    public Optional<PendingAuthorization> consume(String state) {
        String key = key(state);
        Map<Object, Object> stored = redis.opsForHash().entries(key);
        if (stored.isEmpty()) {
            return Optional.empty();
        }

        // DELETE is the point of consistency, not the read above. Redis answers true
        // to exactly one caller, so if two callbacks race on the same state — the
        // real one and a replay — only one of them proceeds. Deciding on the read
        // instead would let both through.
        if (!Boolean.TRUE.equals(redis.delete(key))) {
            // The state itself is a secret and never logged; traceId correlates.
            log.debug("SSO state was consumed by a concurrent callback");
            return Optional.empty();
        }

        return Optional.of(new PendingAuthorization(
                OAuthProvider.valueOf(field(stored, FIELD_PROVIDER)),
                field(stored, FIELD_CODE_VERIFIER),
                field(stored, FIELD_REDIRECT_URI),
                field(stored, FIELD_BROWSER_NONCE)));
    }

    private static String field(Map<Object, Object> stored, String name) {
        Object value = stored.get(name);
        return value == null ? null : value.toString();
    }

    private static String key(String state) {
        return KEY_PREFIX + state;
    }
}
