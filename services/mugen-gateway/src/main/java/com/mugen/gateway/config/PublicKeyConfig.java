package com.mugen.gateway.config;

import com.mugen.shared.auth.TokenType;
import com.mugen.web.security.TokenTypeValidator;
import com.mugen.web.security.VerificationKeyProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
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
// Bound here because mugen-web's resource-server auto-configuration, which normally
// does it, is servlet-only; the record itself is stack-neutral.
@EnableConfigurationProperties(VerificationKeyProperties.class)
public class PublicKeyConfig {

    @Bean
    RSAPublicKey jwtVerificationKey(VerificationKeyProperties verificationKeyProperties) throws IOException {
        try (InputStream pem = verificationKeyProperties.publicKey().getInputStream()) {
            RSAPublicKey key = RsaKeyConverters.x509().convert(pem);
            log.info("Loaded RS256 verification key from {}", verificationKeyProperties.publicKey().getDescription());
            return key;
        }
    }

    /**
     * The same three checks mugen-auth's own resource server makes, so a token refused
     * there is refused here and vice versa: timestamps, issuer, and the {@code type}
     * claim — without which a 30-day refresh token would pass as a bearer credential.
     */
    @Bean
    ReactiveJwtDecoder accessTokenDecoder(RSAPublicKey jwtVerificationKey, VerificationKeyProperties verificationKeyProperties) {
        NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder.withPublicKey(jwtVerificationKey).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                new JwtTimestampValidator(),
                new JwtIssuerValidator(verificationKeyProperties.issuer()),
                new TokenTypeValidator(TokenType.ACCESS)));
        return decoder;
    }
}
