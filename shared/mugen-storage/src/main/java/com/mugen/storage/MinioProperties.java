package com.mugen.storage;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Binds {@code mugen.minio.*}. The same names in every MinIO-backed service, which is
 * what lets the shared test container contribute them for all of them.
 *
 * @param endpoint            as seen from where this service runs — localhost on the
 *                            host, the compose hostname in a container. Presigned URLs
 *                            embed it, so it must also be reachable from the browser
 *                            that will use them
 * @param presignedUrlTtl     how long a presigned GET stays valid
 * @param presignedUrlCacheTtl how long a GET URL is cached; must be shorter than the
 *                            TTL by more than any page load, or a cached URL is handed
 *                            out dead
 */
@Validated
@ConfigurationProperties(prefix = "mugen.minio")
public record MinioProperties(
        @NotBlank String endpoint,
        @NotBlank String accessKey,
        @NotBlank String secretKey,
        @NotNull Duration presignedUrlTtl,
        @NotNull Duration presignedUrlCacheTtl
) {

    public MinioProperties {
        if (presignedUrlTtl != null && presignedUrlCacheTtl != null
                && presignedUrlCacheTtl.compareTo(presignedUrlTtl) >= 0) {
            throw new IllegalArgumentException(
                    "mugen.minio.presigned-url-cache-ttl (%s) must be shorter than presigned-url-ttl (%s), or a cached URL outlives itself"
                            .formatted(presignedUrlCacheTtl, presignedUrlTtl));
        }
    }
}
