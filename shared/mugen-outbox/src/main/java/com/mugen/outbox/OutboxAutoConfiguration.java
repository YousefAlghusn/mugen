package com.mugen.outbox;

import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurationPackages;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import tools.jackson.databind.json.JsonMapper;

/**
 * Gives a JPA service the transactional outbox by adding one dependency: the entity,
 * its repository, the write API and the poller. The service still owns the table — a
 * Flyway migration in its own schema, see {@code db/outbox/} for the DDL per database.
 * <p>
 * The entity and repository live in this module's package, which no service scans.
 * Registering it as an <em>auto-configuration package</em> — the mechanism behind
 * {@code @SpringBootApplication} — adds it to what JPA and Spring Data scan without
 * replacing the service's own. An {@code @EntityScan} here would have done the opposite:
 * the first one anywhere switches the default off, and the service's entities vanish.
 */
// After Kafka's as well as JPA's: @ConditionalOnBean on the poller is evaluated when this
// class is processed, and without the ordering the KafkaTemplate does not exist yet —
// the poller is then silently never created and the outbox fills with rows nobody sends.
@AutoConfiguration(
        after = {HibernateJpaAutoConfiguration.class, KafkaAutoConfiguration.class},
        before = DataJpaRepositoriesAutoConfiguration.class)
@ConditionalOnClass({KafkaTemplate.class, jakarta.persistence.EntityManager.class})
@EnableConfigurationProperties(OutboxProperties.class)
@EnableScheduling
@Import(OutboxAutoConfiguration.OutboxPackageRegistrar.class)
public class OutboxAutoConfiguration {

    @Bean
    Outbox outbox(OutboxEventRepository outboxEvents, JsonMapper json) {
        return new Outbox(outboxEvents, json);
    }

    /** Off only for tests that start the context without a broker. */
    @Bean
    @ConditionalOnBean(KafkaTemplate.class)
    @ConditionalOnProperty(name = "mugen.outbox.enabled", havingValue = "true", matchIfMissing = true)
    OutboxPoller outboxPoller(OutboxEventRepository outboxEvents,
                              KafkaTemplate<String, String> kafka,
                              OutboxProperties outboxProperties) {
        return new OutboxPoller(outboxEvents, kafka, outboxProperties);
    }

    static class OutboxPackageRegistrar implements ImportBeanDefinitionRegistrar {

        @Override
        public void registerBeanDefinitions(AnnotationMetadata metadata, BeanDefinitionRegistry registry) {
            AutoConfigurationPackages.register(registry, OutboxEvent.class.getPackageName());
        }
    }
}
