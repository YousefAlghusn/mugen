package com.mugen.web.security;

import com.mugen.shared.auth.TokenType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.security.converter.RsaKeyConverters;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;

import java.io.IOException;
import java.io.InputStream;
import java.security.interfaces.RSAPublicKey;
import java.util.Collection;
import java.util.List;

/**
 * Makes any servlet service a resource server over mugen-auth's tokens by setting
 * {@code mugen.jwt.public-key} and {@code mugen.jwt.issuer}: the key, the decoder with
 * the three checks, the authorities mapping, and {@link ResourceServerSecurity}.
 * <p>
 * Every bean is {@code @ConditionalOnMissingBean}, which is how mugen-auth keeps its
 * own two decoders (access and refresh) while taking the rest.
 */
@Slf4j
@AutoConfiguration(after = MugenSecurityAutoConfiguration.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass({JwtDecoder.class, JwtAuthenticationConverter.class})
@ConditionalOnProperty("mugen.jwt.public-key")
@EnableConfigurationProperties(VerificationKeyProperties.class)
public class MugenResourceServerAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    RSAPublicKey jwtVerificationKey(VerificationKeyProperties verificationKeyProperties) throws IOException {
        try (InputStream pem = verificationKeyProperties.publicKey().getInputStream()) {
            RSAPublicKey key = RsaKeyConverters.x509().convert(pem);
            log.info("Loaded RS256 verification key from {}", verificationKeyProperties.publicKey().getDescription());
            return key;
        }
    }

    /**
     * Timestamps, issuer, and the {@code type} claim — without the last, a 30-day
     * refresh token would pass as a bearer credential. The same three checks the
     * gateway makes, so a token refused there is refused here and vice versa.
     */
    @Bean
    @ConditionalOnMissingBean
    JwtDecoder accessTokenDecoder(RSAPublicKey jwtVerificationKey, VerificationKeyProperties verificationKeyProperties) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(jwtVerificationKey).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                new JwtTimestampValidator(),
                new JwtIssuerValidator(verificationKeyProperties.issuer()),
                new TokenTypeValidator(TokenType.ACCESS)));
        return decoder;
    }

    /**
     * Maps the token's {@code roles} claim onto authorities. Spring's default reads
     * {@code scope} and prefixes {@code SCOPE_}, so without this every {@code hasRole}
     * check would silently fail against an otherwise valid token.
     */
    @Bean
    @ConditionalOnMissingBean
    JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(MugenResourceServerAutoConfiguration::authoritiesFrom);
        return converter;
    }

    @Bean
    @ConditionalOnMissingBean
    ResourceServerSecurity resourceServerSecurity(JwtAuthenticationConverter jwtAuthenticationConverter,
                                                  PublicEndpointMatcher publicEndpointMatcher,
                                                  ProblemAuthenticationEntryPoint problemAuthenticationEntryPoint,
                                                  ProblemAccessDeniedHandler problemAccessDeniedHandler) {
        return new ResourceServerSecurity(
                jwtAuthenticationConverter, publicEndpointMatcher, problemAuthenticationEntryPoint, problemAccessDeniedHandler);
    }

    private static Collection<GrantedAuthority> authoritiesFrom(Jwt jwt) {
        List<String> roles = jwt.getClaimAsStringList("roles");
        if (roles == null) {
            return List.of();
        }
        return roles.stream().map(role -> (GrantedAuthority) new SimpleGrantedAuthority(role)).toList();
    }
}
