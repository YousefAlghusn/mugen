package com.mugen.test.integration;

import com.mugen.test.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.MSSQLServerContainer;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The library's central claim: {@code @IntegrationTest} alone, on a class that declares
 * no container and no property, produces a context connected to a real database.
 * <p>
 * This module's test application has a Postgres driver and no other, so it also pins the
 * half that is easy to get wrong — that the containers a service gets are the ones it
 * has a technology for, not every one this library knows how to start.
 */
@IntegrationTest
class ContainerAutoConfigurationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ApplicationContext context;

    @Test
    @DisplayName("a datasource on the classpath is enough to get a running Postgres")
    void startsThePostgresTheApplicationImplies() {
        assertThat(jdbc.queryForObject("SELECT 1", Integer.class)).isEqualTo(1);

        PostgreSQLContainer<?> postgres = context.getBean(PostgreSQLContainer.class);
        assertThat(postgres.isRunning()).isTrue();
    }

    @Test
    @DisplayName("no container starts for a technology the application does not use")
    void startsNothingElse() {
        // Both Testcontainers modules are on this module's own classpath, so only the
        // driver check keeps them from starting. That is the condition worth pinning:
        // without it every service would boot every database this library supports.
        assertThat(context.getBeanNamesForType(MSSQLServerContainer.class)).isEmpty();
        assertThat(context.getBeanNamesForType(MongoDBContainer.class)).isEmpty();
    }
}
