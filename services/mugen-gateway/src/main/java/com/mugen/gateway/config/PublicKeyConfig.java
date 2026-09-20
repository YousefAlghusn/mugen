package com.mugen.gateway.config;

import com.mugen.shared.auth.TokenType;
import com.mugen.web.security.TokenTypeValidator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.converter.RsaKeyConverters;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;

import java.io.IOException;
import java.io.InputStream;
import java.security.interfaces.RSAPublicKey;

/** Loads mugen-auth's public key once at startup and builds the decoder every request goes through. */
@Slf4j
@Configuration(proxyBeanMethods = false)
public class PublicKeyConfig {

    @Bean
    RSAPublicKey jwtVerificationKey(JwtProperties jwtProperties) throws IOException {
        try (InputStream pem = jwtProperties.publicKey().getInputStream()) {
            RSAPublicKey key = RsaKeyConverters.x509().convert(pem);
            log.info("Loaded RS256 verification key from {}", jwtProperties.publicKey().getDescription());
            return key;
        }
    }

    /**
     * The same three checks mugen-auth's own resource server makes, so a token refused
     * there is refused here and vice versa: timestamps, issuer, and the {@code type}
     * claim — without which a 30-day refresh token would pass as a bearer credential.
     */
    @Bean
    ReactiveJwtDecoder accessTokenDecoder(RSAPublicKey jwtVerificationKey, JwtProperties jwtProperties) {
        NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder.withPublicKey(jwtVerificationKey).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                new JwtTimestampValidator(),
                new JwtIssuerValidator(jwtProperties.issuer()),
                new TokenTypeValidator(TokenType.ACCESS)));
        return decoder;
    }
}
