package com.mugen.storage;

import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Presigned GET URLs, cached in Redis for less than their own lifetime — CLAUDE.md's 50
 * of 60 minutes — so the signature is computed once per object per cache window rather
 * than once per view, and a cached URL can never be handed out after it has expired.
 */
public class PresignedUrls {

    private final ObjectStorage storage;
    private final StringRedisTemplate redis;
    private final MinioProperties minioProperties;
    private final String cachePrefix;

    /** @param cachePrefix the service's own Redis namespace, {@code mugen:<service>:url:} */
    public PresignedUrls(ObjectStorage storage, StringRedisTemplate redis, MinioProperties minioProperties, String cachePrefix) {
        this.storage = storage;
        this.redis = redis;
        this.minioProperties = minioProperties;
        this.cachePrefix = cachePrefix;
    }

    public String get(String bucket, String objectKey) {
        String cacheKey = cachePrefix + bucket + "/" + objectKey;
        String cached = redis.opsForValue().get(cacheKey);
        if (cached != null) {
            return cached;
        }
        String url = storage.presignedGet(bucket, objectKey, minioProperties.presignedUrlTtl());
        redis.opsForValue().set(cacheKey, url, minioProperties.presignedUrlCacheTtl());
        return url;
    }

    /** After the object is replaced or deleted, so the next view signs the new one. */
    public void forget(String bucket, String objectKey) {
        redis.delete(cachePrefix + bucket + "/" + objectKey);
    }
}
