package com.mugen.test;

import com.mugen.shared.auth.TokenType;
import com.mugen.web.security.VerificationKeyProperties;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Stands in for mugen-auth: mints tokens the way that service does, from a key pair
 * generated for this run.
 * <p>
 * The service under test verifies against the {@code @Primary} public key below rather
 * than the committed one, because the matching private key must exist somewhere and a
 * committed private key — even a test one — is the habit this project refuses to form.
 * A second pair exists only to sign tokens that must be rejected.
 * <p>
 * Abstract, and opted into: a resource server declares {@code @Fixture class Tokens
 * extends TokenSigner} in its {@code support/} package and the scan finds it. Not wired
 * into {@link IntegrationTest}, because mugen-auth holds the real private key and a
 * substituted public key there would break the very thing it tests.
 */

public abstract class TokenSigner {

    private final KeyPair keys = rsa();
    private final KeyPair otherKeys = rsa();
    private final String issuer;

    protected TokenSigner(VerificationKeyProperties verificationKeyProperties) {
        this.issuer = verificationKeyProperties.issuer();
    }

    @Bean
    @Primary
    public RSAPublicKey testVerificationKey() {
        return (RSAPublicKey) keys.getPublic();
    }

    public Token accessToken() {
        return new Token(UUID.randomUUID(), UUID.randomUUID());
    }

    /** One token's ingredients, mutable in the one way each test needs. */
    public final class Token {

        private final UUID userId;
        private final UUID sessionId;
        private String type = TokenType.ACCESS;
        private String issuer = TokenSigner.this.issuer;
        private Duration ttl = Duration.ofMinutes(15);
        private Duration age = Duration.ZERO;
        private boolean withSession = true;
        private KeyPair signedWith = keys;

        private Token(UUID userId, UUID sessionId) {
            this.userId = userId;
            this.sessionId = sessionId;
        }

        public UUID userId() {
            return userId;
        }

        public UUID sessionId() {
            return sessionId;
        }

        /** Issued an hour ago with the normal lifetime, so it ran out well past any clock skew. */
        public Token expired() {
            this.age = Duration.ofHours(1);
            return this;
        }

        public Token asRefreshToken() {
            this.type = TokenType.REFRESH;
            return this;
        }

        public Token fromAnotherIssuer() {
            this.issuer = "https://not-mugen.example/auth";
            return this;
        }

        public Token withoutSession() {
            this.withSession = false;
            return this;
        }

        /** A valid signature — just not by mugen-auth's key. */
        public Token signedByAStranger() {
            this.signedWith = otherKeys;
            return this;
        }

        private String encoded;

        /** Encoded once: a second encoding would carry a new kid and a new iat, and no longer equal the first. */
        public String value() {
            if (encoded == null) {
                encoded = encode();
            }
            return encoded;
        }

        private String encode() {
            Instant issuedAt = Instant.now().minus(age);
            JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                    .issuer(issuer)
                    .issuedAt(issuedAt)
                    .expiresAt(issuedAt.plus(ttl))
                    .subject(userId.toString())
                    .claim(TokenType.CLAIM, type)
                    .claim("roles", List.of("USER"));
            if (withSession) {
                claims.claim("sessionId", sessionId.toString());
            }
            return encoder(signedWith).encode(JwtEncoderParameters.from(
                    JwsHeader.with(SignatureAlgorithm.RS256).build(), claims.build())).getTokenValue();
        }
    }

    private static JwtEncoder encoder(KeyPair pair) {
        RSAKey jwk = new RSAKey.Builder((RSAPublicKey) pair.getPublic())
                .privateKey((RSAPrivateKey) pair.getPrivate())
                .keyID(UUID.randomUUID().toString())
                .build();
        return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(jwk)));
    }

    private static KeyPair rsa() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
