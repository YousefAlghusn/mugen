package com.mugen.web.management;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

/**
 * Refuses to start a service whose actuator would share its public port.
 * <p>
 * Every service leaves {@code /actuator/**} unauthenticated — health for the probes,
 * prometheus for the scrape, refresh for config-server — on the strength of one
 * assumption: it is served on {@code management.server.port}, which a deployment never
 * publishes. Drop that property and every one of those endpoints is on the internet,
 * with nothing else failing. This is the check that makes it fail.
 */
@AutoConfiguration
@ConditionalOnWebApplication
// Only where there is an actuator to expose; a headless worker or a test app has none.
@ConditionalOnClass(name = "org.springframework.boot.actuate.endpoint.web.WebEndpointsSupplier")
public class ManagementPortAutoConfiguration {

    static final String SERVER_PORT = "server.port";
    static final String MANAGEMENT_PORT = "management.server.port";

    @Bean
    public ManagementPortGuard managementPortGuard(Environment environment) {
        String serverPort = environment.getProperty(SERVER_PORT, "8080");
        String managementPort = environment.getProperty(MANAGEMENT_PORT);
        if (managementPort == null || managementPort.equals(serverPort)) {
            throw new IllegalStateException(
                    "%s must be set and differ from %s (%s): the actuator is unauthenticated because it is never on the public port"
                            .formatted(MANAGEMENT_PORT, SERVER_PORT, serverPort));
        }
        return new ManagementPortGuard(managementPort);
    }

    /** Exists so the check has a bean to live in; carries the port it approved. */
    public record ManagementPortGuard(String managementPort) {
    }
}
