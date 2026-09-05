package com.mugen.auth.support;

import com.mugen.auth.config.JwtProperties;
import com.mugen.auth.config.RefreshCookieProperties;
import com.mugen.auth.config.SsoProperties;
import com.mugen.auth.controller.RefreshTokenCookies;
import com.mugen.auth.controller.SsoStateCookies;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * The two cookie builders, for a controller slice — which scans controllers only, so
 * neither {@code @Component} nor the properties they read are in the context.
 * <p>
 * Real builders rather than mocks: the attributes they emit are the security model of
 * both flows, and a mock would assert nothing. Bound from the service's own
 * {@code application.yml} rather than constructed from literals, so the cookie a slice
 * asserts on is the cookie the service sets — including the name, which
 * {@code @CookieValue} reads back from the same property.
 * <p>
 * {@code @TestConfiguration} is excluded from component scanning, so this never reaches
 * an integration context, where the real beans are.
 */
@TestConfiguration
@EnableConfigurationProperties({RefreshCookieProperties.class, JwtProperties.class, SsoProperties.class})
public class CookieComponents {

    @Bean
    RefreshTokenCookies refreshTokenCookies(RefreshCookieProperties refreshCookieProperties,
                                            JwtProperties jwtProperties) {
        return new RefreshTokenCookies(refreshCookieProperties, jwtProperties);
    }

    @Bean
    SsoStateCookies ssoStateCookies(RefreshCookieProperties refreshCookieProperties,
                                    SsoProperties ssoProperties) {
        return new SsoStateCookies(refreshCookieProperties, ssoProperties);
    }
}
