package com.mugen.auth.unit;

import com.mugen.auth.config.JwtProperties;
import com.mugen.auth.entity.Role;
import com.mugen.auth.entity.User;
import com.mugen.auth.exception.AuthExceptions;
import com.mugen.auth.dto.RefreshTokenClaims;
import com.mugen.auth.service.JwtService;
import com.mugen.auth.token.TokenType;
import com.mugen.auth.token.TokenTypeValidator;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The keypair is generated in-process rather than read from
 * {@code resources/keys/private.pem}, because that file is gitignored — a test
 * depending on it would pass locally and fail on any fresh clone or CI runner.
 */
class JwtServiceTest {

    private static final String ISSUER = "https://mugen.dev/auth";

    private static RSAPublicKey publicKey;
    private static JwtEncoder encoder;
    private static JwtService jwtService;
    private static JwtProperties properties;

    @BeforeAll
    static void generateKeypair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();

        publicKey = (RSAPublicKey) pair.getPublic();
        RSAPrivateKey privateKey = (RSAPrivateKey) pair.getPrivate();

        RSAKey jwk = new RSAKey.Builder(publicKey).privateKey(privateKey).build();
        encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(jwk)));

        properties = new JwtProperties(
                new ByteArrayResource(new byte[0]),
                new ByteArrayResource(new byte[0]),
                ISSUER,
                Duration.ofMinutes(15),
                Duration.ofDays(30),
                Duration.ofMinutes(15));

        jwtService = new JwtService(encoder, decoderFor(TokenType.REFRESH), properties);
    }

    private static JwtDecoder decoderFor(String tokenType) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(publicKey).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                new JwtTimestampValidator(),
                new JwtIssuerValidator(ISSUER),
                new TokenTypeValidator(tokenType)));
        return decoder;
    }

    private static User userWithId(UUID id) {
        User user = User.withPassword("kaneki", "kaneki@mugen.dev", "{bcrypt}hash");
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    @Test
    @DisplayName("an access token carries userId, sessionId and roles")
    void accessTokenCarriesExpectedClaims() {
        UUID userId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();

        String token = jwtService.generateAccessToken(userWithId(userId), sessionId);
        Jwt decoded = decoderFor(TokenType.ACCESS).decode(token);

        assertThat(decoded.getSubject()).isEqualTo(userId.toString());
        assertThat(decoded.getClaimAsString("sessionId")).isEqualTo(sessionId.toString());
        assertThat(decoded.getClaimAsStringList("roles")).containsExactly(Role.USER);
        assertThat(decoded.getIssuer()).hasToString(ISSUER);
    }

    @Test
    @DisplayName("a refresh token carries only sessionId and version")
    void refreshTokenIsMinimal() {
        UUID sessionId = UUID.randomUUID();

        String token = jwtService.generateRefreshToken(sessionId, 7);
        RefreshTokenClaims claims = jwtService.parseRefreshToken(token);

        assertThat(claims.sessionId()).isEqualTo(sessionId);
        assertThat(claims.version()).isEqualTo(7);
        // No roles: they are re-read on rotation so a revoked role cannot be frozen
        // into a 30-day token. Bound to Object because Jwt.getClaim is generic and
        // would otherwise resolve to AssertJ's Predicate overload.
        Object roles = decoderFor(TokenType.REFRESH).decode(token).getClaim("roles");
        assertThat(roles).isNull();
    }

    @Test
    @DisplayName("a refresh token is rejected when presented as an access token")
    void refreshTokenIsNotUsableAsAccessToken() {
        String refreshToken = jwtService.generateRefreshToken(UUID.randomUUID(), 0);

        // Without the type claim this would verify: same key, same issuer, unexpired.
        // It would authenticate a caller off a 30-day cookie credential.
        assertThatThrownBy(() -> decoderFor(TokenType.ACCESS).decode(refreshToken))
                .hasMessageContaining("Token is not valid for this endpoint");
    }

    @Test
    @DisplayName("an access token is rejected at the refresh endpoint")
    void accessTokenIsNotUsableAsRefreshToken() {
        String accessToken = jwtService.generateAccessToken(userWithId(UUID.randomUUID()), UUID.randomUUID());

        assertThatThrownBy(() -> jwtService.parseRefreshToken(accessToken))
                .isInstanceOf(AuthExceptions.TokenInvalid.class);
    }

    @Test
    @DisplayName("a tampered payload fails signature verification")
    void tamperedTokenIsRejected() {
        String token = jwtService.generateRefreshToken(UUID.randomUUID(), 0);

        String[] parts = token.split("\\.");
        // Flip a character in the payload, leaving the original signature attached.
        String tamperedPayload = parts[1].substring(0, parts[1].length() - 2)
                + (parts[1].endsWith("A") ? "B" : "A");
        String tampered = parts[0] + "." + tamperedPayload + "." + parts[2];

        assertThatThrownBy(() -> jwtService.parseRefreshToken(tampered))
                .isInstanceOf(AuthExceptions.TokenInvalid.class);
    }

    @Test
    @DisplayName("a token signed by a different key is rejected")
    void foreignKeyTokenIsRejected() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair attackerPair = generator.generateKeyPair();

        RSAKey attackerJwk = new RSAKey.Builder((RSAPublicKey) attackerPair.getPublic())
                .privateKey((RSAPrivateKey) attackerPair.getPrivate())
                .build();
        JwtService attackerService = new JwtService(
                new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(attackerJwk))),
                decoderFor(TokenType.REFRESH),
                properties);

        String forged = attackerService.generateRefreshToken(UUID.randomUUID(), 0);

        assertThatThrownBy(() -> jwtService.parseRefreshToken(forged))
                .isInstanceOf(AuthExceptions.TokenInvalid.class);
    }

    @Test
    @DisplayName("an expired token is rejected")
    void expiredTokenIsRejected() {
        // Encoded directly rather than through JwtService, because JwtClaimsSet
        // refuses to build a token whose expiry precedes its issue time — the only
        // way to get one is to backdate both.
        Instant issued = Instant.now().minus(Duration.ofHours(2));
        JwtClaimsSet expired = JwtClaimsSet.builder()
                .issuer(ISSUER)
                .issuedAt(issued)
                .expiresAt(issued.plus(Duration.ofMinutes(1)))
                .subject(UUID.randomUUID().toString())
                .claim(TokenType.CLAIM, TokenType.REFRESH)
                .claim("sessionId", UUID.randomUUID().toString())
                .claim("version", 0)
                .build();

        String token = encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(SignatureAlgorithm.RS256).build(), expired)).getTokenValue();

        assertThatThrownBy(() -> jwtService.parseRefreshToken(token))
                .isInstanceOf(AuthExceptions.TokenInvalid.class);
    }

    @Test
    @DisplayName("the rejection message never explains why the token failed")
    void rejectionMessageIsOpaque() {
        String accessToken = jwtService.generateAccessToken(userWithId(UUID.randomUUID()), UUID.randomUUID());

        assertThatThrownBy(() -> jwtService.parseRefreshToken(accessToken))
                .hasMessage("Refresh token is not valid.");
    }
}
