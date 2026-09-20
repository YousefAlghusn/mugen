package com.mugen.web.unit;

import com.mugen.web.management.ManagementPortAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

/**
 * The actuator is unauthenticated on one condition: that it is never on the public port.
 * A service that violates that must not start, because nothing else would notice.
 */
class ManagementPortGuardTest {

    private final ManagementPortAutoConfiguration configuration = new ManagementPortAutoConfiguration();

    @Test
    void aSeparateManagementPortIsApproved() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("server.port", "8081")
                .withProperty("management.server.port", "9081");

        assertThat(configuration.managementPortGuard(environment).managementPort()).isEqualTo("9081");
    }

    @Test
    void aSharedPortRefusesToStart() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("server.port", "8081")
                .withProperty("management.server.port", "8081");

        assertThatIllegalStateException().isThrownBy(() -> configuration.managementPortGuard(environment));
    }

    /** Unset means "same as the server port", which is the same exposure by another route. */
    @Test
    void anUnsetManagementPortRefusesToStart() {
        MockEnvironment environment = new MockEnvironment().withProperty("server.port", "8081");

        assertThatIllegalStateException().isThrownBy(() -> configuration.managementPortGuard(environment));
    }
}
