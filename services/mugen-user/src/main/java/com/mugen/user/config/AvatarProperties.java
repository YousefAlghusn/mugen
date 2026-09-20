package com.mugen.user.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Binds {@code mugen.avatars.*}. Read-URL lifetimes are {@code mugen.minio.*}, shared.
 *
 * @param bucket       the MinIO bucket, created by compose's minio-init
 * @param uploadUrlTtl how long a presigned PUT stays valid — a person choosing a file
 */
@Validated
@ConfigurationProperties(prefix = "mugen.avatars")
public record AvatarProperties(@NotBlank String bucket, @NotNull Duration uploadUrlTtl) {
}
