package com.mugen.user.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Binds {@code mugen.minio.*}. The same three names in every MinIO-backed service, which
 * is what lets the shared test container contribute them for all of them.
 *
 * @param endpoint  as seen from where this service runs — localhost on the host, the
 *                  compose hostname in a container. Presigned URLs embed it, so it must
 *                  also be reachable from the browser that will use them
 */
@Validated
@ConfigurationProperties(prefix = "mugen.minio")
public record MinioProperties(@NotBlank String endpoint, @NotBlank String accessKey, @NotBlank String secretKey) {
}
