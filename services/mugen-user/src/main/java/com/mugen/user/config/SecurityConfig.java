package com.mugen.user.config;

import com.mugen.web.security.ResourceServerSecurity;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;

/** The standard mugen chain and nothing else: this service adds no filter of its own. */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, ResourceServerSecurity resourceServerSecurity) throws Exception {
        return resourceServerSecurity.configure(http).build();
    }
}
