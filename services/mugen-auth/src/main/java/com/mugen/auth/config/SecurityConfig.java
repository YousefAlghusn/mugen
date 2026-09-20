package com.mugen.auth.config;

import com.mugen.auth.security.RevokedSessionFilter;
import com.mugen.auth.service.RevocationCacheService;
import com.mugen.web.security.ResourceServerSecurity;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.servlet.HandlerExceptionResolver;

/** The standard mugen chain, plus the one filter only this service adds. */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http,
                                    ResourceServerSecurity resourceServerSecurity,
                                    RevocationCacheService revocationCacheService,
                                    @Lazy @Qualifier("handlerExceptionResolver")
                                    HandlerExceptionResolver handlerExceptionResolver) throws Exception {

        return resourceServerSecurity.configure(http)
                // After the token is verified, because it only has a question to ask
                // about a token that turned out to be ours. Constructed here rather
                // than declared a bean: a Filter bean is also registered with the
                // servlet container, where it would run a second time outside this
                // chain, before anything has been authenticated.
                .addFilterAfter(
                        new RevokedSessionFilter(revocationCacheService, handlerExceptionResolver),
                        BearerTokenAuthenticationFilter.class)
                .build();
    }
}
