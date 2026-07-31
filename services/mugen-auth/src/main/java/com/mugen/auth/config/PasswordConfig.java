package com.mugen.auth.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration(proxyBeanMethods = false)
public class PasswordConfig {

    /**
     * A delegating encoder, not a bare {@code BCryptPasswordEncoder}.
     * <p>
     * It stores the algorithm inline as a prefix — {@code {bcrypt}$2a$10$...} — and
     * picks the verifier from that prefix on read. The consequence is that moving
     * to a different algorithm later is a one-line change: new passwords are hashed
     * with the new default while every existing {@code {bcrypt}} hash keeps
     * verifying. A bare encoder hardcodes the choice into every stored row and
     * makes migration a mass password reset.
     * <p>
     * This is why {@code users.password_hash} is 100 characters rather than the 60
     * a raw bcrypt hash needs — the prefix has to fit, with headroom for a longer
     * algorithm name later.
     */
    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }
}
