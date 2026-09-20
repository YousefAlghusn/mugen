package com.mugen.user.storage;

import com.mugen.user.config.MinioProperties;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.errors.ErrorResponseException;
import io.minio.http.Method;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;

/**
 * The four things this service does with object storage, over the MinIO SDK.
 * <p>
 * Files never pass through here: a presigned URL lets the browser PUT and GET straight
 * against MinIO, so a 10 MB upload costs this service one signature. The SDK's checked
 * exceptions are wrapped, because none of them is a case a caller can do anything
 * about except log and answer 500.
 */
@Slf4j
@Component
public class MinioClientWrapper {

    private final MinioClient minio;

    public MinioClientWrapper(MinioProperties minioProperties) {
        this.minio = MinioClient.builder()
                .endpoint(minioProperties.endpoint())
                .credentials(minioProperties.accessKey(), minioProperties.secretKey())
                .build();
    }

    /**
     * A URL the holder can PUT one object to, for {@code ttl}. The content type is part
     * of the signature: a URL issued for a PNG cannot be used to store an executable.
     */
    public String presignedPut(String bucket, String objectKey, String contentType, Duration ttl) {
        return presign(GetPresignedObjectUrlArgs.builder()
                .method(Method.PUT)
                .bucket(bucket)
                .object(objectKey)
                .expiry((int) ttl.toSeconds())
                .extraHeaders(Map.of("Content-Type", contentType))
                .build());
    }

    public String presignedGet(String bucket, String objectKey, Duration ttl) {
        return presign(GetPresignedObjectUrlArgs.builder()
                .method(Method.GET)
                .bucket(bucket)
                .object(objectKey)
                .expiry((int) ttl.toSeconds())
                .build());
    }

    public boolean objectExists(String bucket, String objectKey) {
        try {
            minio.statObject(StatObjectArgs.builder().bucket(bucket).object(objectKey).build());
            return true;
        } catch (ErrorResponseException ex) {
            if ("NoSuchKey".equals(ex.errorResponse().code())) {
                return false;
            }
            throw new StorageException("stat " + objectKey, ex);
        } catch (Exception ex) {
            throw new StorageException("stat " + objectKey, ex);
        }
    }

    /** Idempotent: removing a key that is already gone is not an error to MinIO or to us. */
    public void delete(String bucket, String objectKey) {
        try {
            minio.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(objectKey).build());
        } catch (Exception ex) {
            throw new StorageException("delete " + objectKey, ex);
        }
    }

    private String presign(GetPresignedObjectUrlArgs args) {
        try {
            return minio.getPresignedObjectUrl(args);
        } catch (Exception ex) {
            throw new StorageException("presign " + args.object(), ex);
        }
    }

    /** Unexpected by definition: rendered as a 500 with the cause in the log, never the response. */
    public static class StorageException extends RuntimeException {
        StorageException(String operation, Throwable cause) {
            super("Object storage failed: " + operation, cause);
        }
    }
}
