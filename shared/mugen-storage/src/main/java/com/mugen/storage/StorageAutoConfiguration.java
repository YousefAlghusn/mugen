package com.mugen.storage;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Object storage for any service that depends on this module: the client wrapper, and
 * the URL cache when the service also has Redis. Not conditional on the endpoint
 * property being set — under test it arrives from a container after the conditions
 * have been evaluated — so a service that has the module without configuring it fails
 * validation at startup, which is the right failure. The buckets themselves
 * are infrastructure — created by compose's minio-init, never by a service.
 */
@AutoConfiguration(after = DataRedisAutoConfiguration.class) // The cache's @ConditionalOnBean must see the template.
@ConditionalOnClass(io.minio.MinioClient.class)
@EnableConfigurationProperties(MinioProperties.class)
public class StorageAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    ObjectStorage objectStorage(MinioProperties minioProperties) {
        return new ObjectStorage(minioProperties);
    }

    /** Namespaced by application name, so two services caching the same bucket never read each other's entries. */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(StringRedisTemplate.class)
    PresignedUrls presignedUrls(ObjectStorage objectStorage, StringRedisTemplate redis,
                                MinioProperties minioProperties, Environment environment) {
        String service = environment.getProperty("spring.application.name", "mugen");
        return new PresignedUrls(objectStorage, redis, minioProperties, service + ":url:");
    }
}
