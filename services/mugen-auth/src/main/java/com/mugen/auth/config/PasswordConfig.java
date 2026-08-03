package com.mugen.auth.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration(proxyBeanMethods = false)
public class PasswordConfig {

    /**
     * Delegating, not a bare {@code BCryptPasswordEncoder}: storing the algorithm as a
     * {@code {bcrypt}} prefix makes changing it a one-line change rather than a mass
     * password reset. It is also why {@code users.password_hash} is 100 characters
     * rather than bcrypt's 60 — the prefix has to fit.
     */
    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }
}
