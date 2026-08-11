package com.mugen.test.support;

import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MSSQLServerContainer;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Starts the containers a service's tests need, chosen from what is on its classpath.
 * <p>
 * A service opts into SQL Server by depending on {@code org.testcontainers:mssqlserver}
 * — the dependency it would have to declare anyway — and never writes a container, an
 * image name or a {@code DynamicPropertyRegistrar} again. {@code @ServiceConnection}
 * contributes {@code spring.datasource.*} and friends from the running container.
 * <p>
 * <b>Imported by {@link com.mugen.test.IntegrationTest}, and it has to be an import
 * rather than an auto-configuration.</b> That was tried and it silently does not work:
 * auto-configurations are registered after ordinary configuration, so
 * {@code DataSourceAutoConfiguration} has already resolved {@code spring.datasource.url}
 * to the service's real {@code localhost} address by the time the connection details
 * appear, and Flyway then fails against a database nobody started. Container beans are
 * user configuration.
 * <p>
 * Both halves of each condition matter. Requiring the driver as well as the
 * Testcontainers module keeps a service from booting a database it merely has a jar for
 * — including this module's own tests, which carry every optional module in the pom.
 * <p>
 * Each container can be switched off with
 * {@code mugen.test.containers.<technology>.enabled: false} in a service's
 * {@code application-test.yml}, which is the escape hatch for a service that needs to
 * declare its own — a second database, say, or a container with a custom command. The
 * image is overridable the same way, though it should normally match compose.yml so the
 * suite and a local run exercise the same versions.
 */
@TestConfiguration(proxyBeanMethods = false)
public class MugenContainers {

    private static final String ENABLED = "mugen.test.containers.%s.enabled";

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(value = MSSQLServerContainer.class, name = "com.microsoft.sqlserver.jdbc.SQLServerDriver")
    @ConditionalOnProperty(name = "mugen.test.containers.sqlserver.enabled", matchIfMissing = true)
    static class SqlServer {

        @Bean
        @ServiceConnection
        @SuppressWarnings("resource") // The context owns the lifecycle.
        MSSQLServerContainer<?> sqlServerContainer(Environment environment) {
            return new MSSQLServerContainer<>(image(environment,
                    "sqlserver", "mcr.microsoft.com/mssql/server:2022-latest"))
                    .acceptLicense();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(value = PostgreSQLContainer.class, name = "org.postgresql.Driver")
    @ConditionalOnProperty(name = "mugen.test.containers.postgres.enabled", matchIfMissing = true)
    static class Postgres {

        @Bean
        @ServiceConnection
        @SuppressWarnings("resource")
        PostgreSQLContainer<?> postgresContainer(Environment environment) {
            return new PostgreSQLContainer<>(image(environment, "postgres", "postgres:16-alpine"));
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(value = MongoDBContainer.class, name = "org.springframework.data.mongodb.core.MongoTemplate")
    @ConditionalOnProperty(name = "mugen.test.containers.mongo.enabled", matchIfMissing = true)
    static class Mongo {

        @Bean
        @ServiceConnection
        @SuppressWarnings("resource")
        MongoDBContainer mongoContainer(Environment environment) {
            return new MongoDBContainer(image(environment, "mongo", "mongo:7"));
        }
    }

    /**
     * Keyed off Spring Data Redis rather than a Testcontainers module, because Redis runs
     * in a plain {@code GenericContainer} that is always on the classpath — so the
     * container's presence cannot be the signal, and the service's use of Redis is.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springframework.data.redis.connection.RedisConnectionFactory")
    @ConditionalOnProperty(name = "mugen.test.containers.redis.enabled", matchIfMissing = true)
    static class Redis {

        /** {@code name} selects the connection details factory; a GenericContainer carries no type to match on. */
        @Bean
        @ServiceConnection(name = "redis")
        @SuppressWarnings("resource")
        GenericContainer<?> redisContainer(Environment environment) {
            return new GenericContainer<>(image(environment, "redis", "redis:7-alpine"))
                    .withExposedPorts(6379);
        }
    }

    private static String image(Environment environment, String technology, String fallback) {
        return environment.getProperty("mugen.test.containers." + technology + ".image", fallback);
    }
}
