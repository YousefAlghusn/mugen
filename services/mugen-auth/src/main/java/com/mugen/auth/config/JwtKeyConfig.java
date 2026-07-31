package com.mugen.auth.config;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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
    RSAPrivateKey jwtSigningKey(JwtProperties properties) throws IOException {
        try (InputStream pem = properties.privateKey().getInputStream()) {
            RSAPrivateKey key = RsaKeyConverters.pkcs8().convert(pem);
            log.info("Loaded RS256 signing key from {}", properties.privateKey().getDescription());
            return key;
        }
    }

    @Bean
    RSAPublicKey jwtVerificationKey(JwtProperties properties) throws IOException {
        try (InputStream pem = properties.publicKey().getInputStream()) {
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
     * Verifies tokens presented to this service's own protected endpoints
     * ({@code /me}, {@code /validate}).
     * <p>
     * The default validator only checks timestamps. Issuer validation is added
     * explicitly: without it, any RS256 token this key happens to verify would be
     * accepted, including one minted by a different system that was handed the
     * same public key.
     */
    @Bean
    JwtDecoder jwtDecoder(RSAPublicKey publicKey, JwtProperties properties) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(publicKey).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                new JwtTimestampValidator(),
                new JwtIssuerValidator(properties.issuer())));
        return decoder;
    }
}
