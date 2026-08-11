package com.mugen.test.support;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder;
import org.springframework.web.context.WebApplicationContext;

/**
 * A {@link MockMvc} with the real filter chain installed, so an integration test can
 * {@code @Autowired} one instead of building it in {@code @BeforeEach}.
 */
@TestConfiguration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class MockMvcConfiguration {

    /** Spring Security registers the chain under this name; there is no typed lookup for it. */
    private static final String FILTER_CHAIN = "springSecurityFilterChain";

    /**
     * Built by hand rather than with {@code @AutoConfigureMockMvc} because Boot 4 split
     * test auto-configuration per technology and no longer ships the MockMvc variant in
     * spring-boot-starter-test.
     * <p>
     * {@code springSecurity()} is what installs the filter chain, and leaving it off is
     * the failure worth knowing about: every endpoint then answers 200 and every
     * assertion about authentication passes for the wrong reason. It is applied whenever
     * the chain exists, which for a mugen service is always — the check is here so this
     * class stays usable in a context that has no security at all.
     */
    @Bean
    MockMvc mockMvc(WebApplicationContext context) {
        DefaultMockMvcBuilder builder = MockMvcBuilders.webAppContextSetup(context);
        if (context.containsBean(FILTER_CHAIN)) {
            builder.apply(SecurityMockMvcConfigurers.springSecurity());
        }
        return builder.build();
    }
}
