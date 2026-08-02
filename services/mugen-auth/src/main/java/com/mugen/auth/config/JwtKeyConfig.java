package com.mugen.auth.config;

import com.mugen.auth.token.TokenType;
import com.mugen.auth.token.TokenTypeValidator;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.security.converter.RsaKeyConverters;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import java.io.IOException;
import java.io.InputStream;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;

/**
 * Loads the RS256 keypair once at startup.
 * <p>
 * Asymmetric on purpose. With HS256 every service that needs to <em>verify</em> a
 * token would also hold the secret needed to <em>mint</em> one, so a read-only
 * service being compromised would let an attacker forge tokens for anybody. Here
 * only mugen-auth holds the private key; the gateway gets public.pem and can do
 * nothing but verify.
 * <p>
 * Parsing is delegated to Spring Security's {@link RsaKeyConverters} rather than
 * hand-rolled base64 stripping and {@code KeyFactory} calls.
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
public class JwtKeyConfig {

    @Bean
    RSAPrivateKey jwtSigningKey(JwtProperties jwtProperties) throws IOException {
        try (InputStream pem = jwtProperties.privateKey().getInputStream()) {
            RSAPrivateKey key = RsaKeyConverters.pkcs8().convert(pem);
            log.info("Loaded RS256 signing key from {}", jwtProperties.privateKey().getDescription());
            return key;
        }
    }

    @Bean
    RSAPublicKey jwtVerificationKey(JwtProperties jwtProperties) throws IOException {
        try (InputStream pem = jwtProperties.publicKey().getInputStream()) {
            return RsaKeyConverters.x509().convert(pem);
        }
    }

    /**
     * Signs both token types.
     * <p>
     * The {@code kid} is set to the key's RFC 7638 thumbprint, which is derived
     * from the key material itself rather than invented. When the keypair is
     * rotated the new key gets a different thumbprint automatically, so tokens
     * signed either side of a rotation are distinguishable and a verifier holding
     * both keys can select the right one.
     */
    @Bean
    JwtEncoder jwtEncoder(RSAPublicKey publicKey, RSAPrivateKey privateKey) throws Exception {
        RSAKey jwk = new RSAKey.Builder(publicKey)
                .privateKey(privateKey)
                .build();
        jwk = new RSAKey.Builder(jwk).keyID(jwk.computeThumbprint().toString()).build();

        return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(jwk)));
    }

    /**
     * Verifies access tokens presented to this service's protected endpoints
     * ({@code /me}, {@code /validate}, session management).
     * <p>
     * {@code @Primary} because the resource server resolves its decoder by type and
     * there are two here. Validation beyond the default timestamp check:
     * <ul>
     *   <li>issuer — without it any RS256 token this key happens to verify would be
     *       accepted, including one minted by whoever else was handed the public key;</li>
     *   <li>token type — without it a 30-day refresh token would authenticate as a
     *       bearer credential.</li>
     * </ul>
     */
    @Bean
    @Primary
    JwtDecoder accessTokenDecoder(RSAPublicKey publicKey, JwtProperties jwtProperties) {
        return decoderFor(publicKey, jwtProperties, TokenType.ACCESS);
    }

    /** Used only by the refresh endpoint; rejects access tokens. */
    @Bean
    JwtDecoder refreshTokenDecoder(RSAPublicKey publicKey, JwtProperties jwtProperties) {
        return decoderFor(publicKey, jwtProperties, TokenType.REFRESH);
    }

    private static JwtDecoder decoderFor(RSAPublicKey publicKey, JwtProperties jwtProperties, String tokenType) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(publicKey).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                new JwtTimestampValidator(),
                new JwtIssuerValidator(jwtProperties.issuer()),
                new TokenTypeValidator(tokenType)));
        return decoder;
    }
}
