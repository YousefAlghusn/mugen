package com.mugen.outbox.integration;

import com.mugen.outbox.Outbox;
import com.mugen.outbox.OutboxAutoConfiguration;
import com.mugen.outbox.OutboxEventRepository;
import com.mugen.outbox.OutboxPoller;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * The poller is conditional on a {@code KafkaTemplate} bean, and a condition on a bean
 * is only as good as the order the auto-configurations run in. The first real run of
 * mugen-user had every follow queued and nothing ever sent: the outbox filled, the
 * poller had silently never been created. Here rather than in a service, because it is
 * this module's ordering that decides it — and no service test enables the poller.
 */
class OutboxAutoConfigurationTest {

    private final ApplicationContextRunner contexts = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    JacksonAutoConfiguration.class, KafkaAutoConfiguration.class, OutboxAutoConfiguration.class))
            .withBean(OutboxEventRepository.class, () -> mock(OutboxEventRepository.class))
            .withPropertyValues(
                    "mugen.outbox.batch-size=10", "mugen.outbox.send-timeout=1s",
                    "mugen.outbox.initial-backoff=1s", "mugen.outbox.max-backoff=1m",
                    "mugen.outbox.alert-after-attempts=3", "mugen.outbox.retention=1d",
                    "mugen.outbox.poll-interval=1s", "mugen.outbox.purge-interval=1h");

    @Test
    void thePollerExistsWheneverKafkaIsConfigured() {
        contexts.run(context -> {
            assertThat(context).hasSingleBean(OutboxPoller.class);
            assertThat(context).hasSingleBean(Outbox.class);
        });
    }

    @Test
    void thePollerCanBeSwitchedOffWithoutLosingTheWriteApi() {
        contexts.withPropertyValues("mugen.outbox.enabled=false").run(context -> {
            assertThat(context).doesNotHaveBean(OutboxPoller.class);
            assertThat(context).hasSingleBean(Outbox.class);
        });
    }
}
