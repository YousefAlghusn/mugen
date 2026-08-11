package com.mugen.test;

import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.springframework.test.context.junit.jupiter.SpringExtension;

/**
 * Empties the database before each integration test class, restoring the clean start
 * that a container per class used to give for free.
 * <p>
 * Per class rather than per test on purpose. Per test would run inside whatever
 * transaction {@code @Transactional} has already opened, and a class that commits is
 * usually committing something it then asserts on across several tests. Per class is
 * also the boundary that actually leaked: within one class the tests were already
 * written to tolerate each other.
 */
public class CleanDatabaseExtension implements BeforeAllCallback {

    @Override
    public void beforeAll(ExtensionContext context) {
        SpringExtension.getApplicationContext(context).getBean(DatabaseCleaner.class).clean();
    }
}
