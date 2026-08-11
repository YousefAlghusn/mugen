package com.mugen.test;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * A {@link MockMvc} with the real filter chain installed, so integration tests can
 * {@code @Autowired} one instead of building it in {@code @BeforeEach}.
 */
@TestConfiguration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class MockMvcConfiguration {

    /**
     * Built by hand rather than with {@code @AutoConfigureMockMvc} because Boot 4 split
     * test auto-configuration per technology and no longer ships the MockMvc variant in
     * spring-boot-starter-test.
     * <p>
     * {@code springSecurity()} is what installs the filter chain, and leaving it off is
     * the failure worth knowing about: every endpoint then answers 200 and every
     * assertion about authentication passes for the wrong reason.
     */
    @Bean
    MockMvc mockMvc(WebApplicationContext context) {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();
    }
}
