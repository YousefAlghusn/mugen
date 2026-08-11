package com.mugen.auth.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MSSQLServerContainer;

/**
 * The infrastructure every mugen-auth integration test runs against.
 * <p>
 * {@code @ServiceConnection} replaces the {@code DynamicPropertyRegistrar} block each
 * test used to carry: Boot reads the started container and contributes
 * {@code spring.datasource.*} and {@code spring.data.redis.*} itself. Worth knowing
 * that the factories doing that are split per technology in Boot 4 — the JDBC one is in
 * {@code spring-boot-jdbc} and the Redis one in {@code spring-boot-data-redis}, not in
 * {@code spring-boot-testcontainers} — so a service gets them only because it already
 * depends on the technology.
 * <p>
 * Both containers start once for the whole run rather than once per test class, and
 * that follows entirely from every test using {@code @AuthIntegrationTest} unmodified:
 * identical configuration means one cached context, and the containers are beans in it.
 */
@TestConfiguration(proxyBeanMethods = false)
public class AuthContainers {

    @Bean
    @ServiceConnection
    @SuppressWarnings("resource") // The context owns the lifecycle.
    MSSQLServerContainer<?> sqlServer() {
        return new MSSQLServerContainer<>("mcr.microsoft.com/mssql/server:2022-latest").acceptLicense();
    }

    /**
     * Named explicitly because a {@code GenericContainer} carries no type for Boot to
     * match on — the name is what selects the Redis connection details factory.
     */
    @Bean
    @ServiceConnection(name = "redis")
    @SuppressWarnings("resource")
    GenericContainer<?> redis() {
        return new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
    }
}
