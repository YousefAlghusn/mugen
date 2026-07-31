package com.mugen.auth.service;

import com.mugen.auth.config.JwtProperties;
import com.mugen.auth.entity.User;
import com.mugen.auth.exception.AuthExceptions;
import com.mugen.auth.dto.RefreshTokenClaims;
import com.mugen.auth.token.TokenType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Mints and parses tokens. Knows nothing about sessions or users beyond what goes
 * into a claim set — deciding whether a token <em>should</em> be issued is
 * {@link SessionService}'s and {@link AuthService}'s job.
 */
@Slf4j
@Service
public class JwtService {

    private final JwtEncoder encoder;
    private final JwtDecoder refreshTokenDecoder;
    private final JwtProperties properties;

    public JwtService(JwtEncoder encoder,
                      @Qualifier("refreshTokenDecoder") JwtDecoder refreshTokenDecoder,
                      JwtProperties properties) {
        this.encoder = encoder;
        this.refreshTokenDecoder = refreshTokenDecoder;
        this.properties = properties;
    }

    /**
     * Access token: 15 minutes, carries everything the gateway needs to authorise a
     * request without touching a database — {@code sub}, {@code sessionId},
     * {@code roles}.
     */
    public String generateAccessToken(User user, UUID sessionId) {
        Instant now = Instant.now();

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.issuer())
                .issuedAt(now)
                .expiresAt(now.plus(properties.accessTokenTtl()))
                .subject(user.getId().toString())
                .claim(TokenType.CLAIM, TokenType.ACCESS)
                .claim("sessionId", sessionId.toString())
                .claim("roles", List.copyOf(user.getRoles()))
                .build();

        return encode(claims);
    }

    /**
     * Refresh token: 30 days, carries only {@code {sessionId, version}}. The version
     * is what makes replay detectable — see {@link com.mugen.auth.entity.Session#rotate()}.
     */
    public String generateRefreshToken(UUID sessionId, int version) {
        Instant now = Instant.now();

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.issuer())
                .issuedAt(now)
                .expiresAt(now.plus(properties.refreshTokenTtl()))
                .subject(sessionId.toString())
                .claim(TokenType.CLAIM, TokenType.REFRESH)
                .claim("sessionId", sessionId.toString())
                .claim("version", version)
                .build();

        return encode(claims);
    }

    /**
     * Verifies a refresh token's signature, issuer, expiry and type, then extracts
     * its claims. Rejects an access token presented here.
     *
     * @throws AuthExceptions.TokenInvalid if the token fails any of those checks
     */
    public RefreshTokenClaims parseRefreshToken(String token) {
        try {
            Jwt jwt = refreshTokenDecoder.decode(token);
            return new RefreshTokenClaims(
                    UUID.fromString(jwt.getClaimAsString("sessionId")),
                    jwt.getClaim("version") instanceof Number version ? version.intValue() : -1);
        } catch (JwtException | IllegalArgumentException ex) {
            // The reason is logged but not returned: telling a caller whether a token
            // was expired, forged or the wrong type helps them probe.
            log.debug("Refresh token rejected: {}", ex.getMessage());
            throw new AuthExceptions.TokenInvalid("Refresh token is not valid.");
        }
    }

    private String encode(JwtClaimsSet claims) {
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }
}
