package com.mugen.gateway.integration;

import com.mugen.gateway.config.GatewayProperties;
import com.mugen.gateway.filter.IdentityHeadersFilter;
import com.mugen.gateway.filter.TraceIdFilter;
import com.mugen.gateway.support.TestRoutes;
import com.mugen.gateway.support.TokenSigner;
import com.mugen.shared.error.ErrorCode;
import com.mugen.test.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Duration;
import java.util.UUID;

/**
 * The gateway authenticates and never authorizes: a bad token is refused here, a
 * missing one is forwarded with no identity, and a good one is forwarded with the
 * identity this gateway vouches for — and never the one the caller claimed.
 */
@IntegrationTest
class JwtVerificationTest {

    private static final String ECHO = TestRoutes.UPSTREAM + "/echo";

    @Autowired private WebTestClient client;
    @Autowired private TokenSigner tokens;
    @Autowired private ReactiveStringRedisTemplate redis;
    @Autowired private GatewayProperties gatewayProperties;

    @Test
    void validTokenIsForwardedWithTheIdentityItProves() {
        TokenSigner.Token token = tokens.accessToken();

        client.get().uri(ECHO)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token.value())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.headers.authorization").isEqualTo("Bearer " + token.value())
                .jsonPath("$.headers.x-user-id").isEqualTo(token.userId().toString())
                .jsonPath("$.headers.x-user-session-id").isEqualTo(token.sessionId().toString());
    }

    @Test
    void noTokenIsForwardedWithNoIdentity() {
        client.get().uri(ECHO)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.headers.authorization").doesNotExist()
                .jsonPath("$.headers.x-user-id").doesNotExist();
    }

    @Test
    void callerSuppliedIdentityHeadersNeverReachTheService() {
        client.get().uri(ECHO)
                .header(IdentityHeadersFilter.USER_ID_HEADER, UUID.randomUUID().toString())
                .header("X-User-Roles", "ADMIN")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.headers.x-user-id").doesNotExist()
                .jsonPath("$.headers.x-user-roles").doesNotExist();
    }

    @Test
    void callerSuppliedIdentityIsReplacedByTheTokensOwn() {
        TokenSigner.Token token = tokens.accessToken();

        client.get().uri(ECHO)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token.value())
                .header(IdentityHeadersFilter.USER_ID_HEADER, UUID.randomUUID().toString())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.headers.x-user-id").isEqualTo(token.userId().toString());
    }

    @Test
    void expiredTokenIsRefused() {
        assertRefusedAsInvalid(tokens.accessToken().expired().value());
    }

    @Test
    void tokenSignedByAnotherKeyIsRefused() {
        assertRefusedAsInvalid(tokens.accessToken().signedByAStranger().value());
    }

    @Test
    void tokenFromAnotherIssuerIsRefused() {
        assertRefusedAsInvalid(tokens.accessToken().fromAnotherIssuer().value());
    }

    /** The 30-day token, presented where the 15-minute one belongs. */
    @Test
    void refreshTokenIsNotABearerCredential() {
        assertRefusedAsInvalid(tokens.accessToken().asRefreshToken().value());
    }

    @Test
    void tamperedTokenIsRefused() {
        String token = tokens.accessToken().value();
        String[] parts = token.split("\\.");
        // A different payload under the original signature.
        String tampered = parts[0] + "." + parts[1].substring(0, parts[1].length() - 2) + "AA." + parts[2];

        assertRefusedAsInvalid(tampered);
    }

    @Test
    void verifiedTokenWithoutASessionIsNotTrusted() {
        assertRefusedAsInvalid(tokens.accessToken().withoutSession().value());
    }

    @Test
    void revokedSessionIsRefusedHoweverValidTheSignature() {
        TokenSigner.Token token = tokens.accessToken();
        revoke(token.sessionId());

        client.get().uri(ECHO)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token.value())
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.code").isEqualTo(ErrorCode.TOKEN_REVOKED.name())
                .jsonPath("$.traceId").isNotEmpty();
    }

    /** The other direction: a revocation is for one session, not for the user. */
    @Test
    void revokingOneSessionLeavesAnotherUsable() {
        TokenSigner.Token token = tokens.accessToken();
        revoke(UUID.randomUUID());

        client.get().uri(ECHO)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token.value())
                .exchange()
                .expectStatus().isOk();
    }

    private void assertRefusedAsInvalid(String bearer) {
        client.get().uri(ECHO)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer)
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().valueEquals(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectHeader().exists(TraceIdFilter.TRACE_ID_HEADER)
                .expectBody()
                .jsonPath("$.code").isEqualTo(ErrorCode.TOKEN_INVALID.name())
                .jsonPath("$.traceId").isNotEmpty();
    }

    /** Exactly what mugen-auth's RevocationCacheService writes. */
    private void revoke(UUID sessionId) {
        redis.opsForValue()
                .set(gatewayProperties.revocationKeyPrefix() + sessionId, "1", Duration.ofMinutes(1))
                .block();
    }
}
