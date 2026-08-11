package com.mugen.test;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Stands in for a mugen service, so this module's tiers can be tested without one.
 * <p>
 * It declares a datasource and nothing else — which is the claim under test: a service
 * that names a technology gets the container for it, having written no container, no
 * property and no annotation of its own.
 */
@SpringBootApplication
public class TestApplication {
}
