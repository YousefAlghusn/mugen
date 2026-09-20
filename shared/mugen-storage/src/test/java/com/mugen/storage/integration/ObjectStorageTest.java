package com.mugen.storage.integration;

import com.mugen.storage.MinioProperties;
import com.mugen.storage.ObjectStorage;
import com.mugen.test.IntegrationTest;
import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The SDK calls, against a real MinIO: what a presigned PUT admits and refuses, and
 * that exists/delete agree with it. A mocked client would agree with whatever the
 * wrapper assumed.
 */
@IntegrationTest
class ObjectStorageTest {

    private static final String BUCKET = "storage-test";

    @Autowired private ObjectStorage storage;
    @Autowired private MinioProperties minioProperties;

    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeAll
    static void bucket(@Autowired MinioProperties minioProperties) throws Exception {
        MinioClient minio = MinioClient.builder()
                .endpoint(minioProperties.endpoint())
                .credentials(minioProperties.accessKey(), minioProperties.secretKey())
                .build();
        if (!minio.bucketExists(BucketExistsArgs.builder().bucket(BUCKET).build())) {
            minio.makeBucket(MakeBucketArgs.builder().bucket(BUCKET).build());
        }
    }

    @Test
    void aPresignedPutStoresTheObjectAndOnlyWithTheSignedContentType() throws Exception {
        String key = "k/" + UUID.randomUUID() + ".png";
        String url = storage.presignedPut(BUCKET, key, "image/png", Duration.ofMinutes(1));

        assertThat(put(url, "text/plain")).isEqualTo(403);
        assertThat(storage.objectExists(BUCKET, key)).isFalse();

        assertThat(put(url, "image/png")).isEqualTo(200);
        assertThat(storage.objectExists(BUCKET, key)).isTrue();
    }

    @Test
    void aPresignedGetReadsItBackAndDeleteIsIdempotent() throws Exception {
        String key = "k/" + UUID.randomUUID() + ".png";
        put(storage.presignedPut(BUCKET, key, "image/png", Duration.ofMinutes(1)), "image/png");

        HttpResponse<String> read = http.send(
                HttpRequest.newBuilder(URI.create(storage.presignedGet(BUCKET, key, Duration.ofMinutes(1)))).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(read.statusCode()).isEqualTo(200);
        assertThat(read.body()).isEqualTo("png-bytes");

        storage.delete(BUCKET, key);
        storage.delete(BUCKET, key);
        assertThat(storage.objectExists(BUCKET, key)).isFalse();
    }

    private int put(String url, String contentType) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(url))
                        .header("Content-Type", contentType)
                        .PUT(HttpRequest.BodyPublishers.ofString("png-bytes"))
                        .build(),
                HttpResponse.BodyHandlers.discarding()).statusCode();
    }
}
