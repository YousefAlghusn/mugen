package com.mugen.user.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Binds {@code mugen.avatars.*}.
 *
 * @param bucket         the MinIO bucket, created by compose's minio-init
 * @param uploadUrlTtl   how long a presigned PUT stays valid — a person choosing a file
 * @param urlTtl         how long a presigned GET stays valid
 * @param urlCacheTtl    how long a GET URL is cached; must be shorter than {@code urlTtl}
 *                       by more than any page load, or a cached URL is handed out dead
 */
@Validated
@ConfigurationProperties(prefix = "mugen.avatars")
public record AvatarProperties(
        @NotBlank String bucket,
        @NotNull Duration uploadUrlTtl,
        @NotNull Duration urlTtl,
        @NotNull Duration urlCacheTtl
) {

    public AvatarProperties {
        if (urlTtl != null && urlCacheTtl != null && urlCacheTtl.compareTo(urlTtl) >= 0) {
            throw new IllegalArgumentException(
                    "mugen.avatars.url-cache-ttl (%s) must be shorter than url-ttl (%s), or a cached URL outlives itself"
                            .formatted(urlCacheTtl, urlTtl));
        }
    }
}
