package com.mugen.user.support;

import com.mugen.test.Fixture;
import com.mugen.user.config.AvatarProperties;
import com.mugen.storage.MinioProperties;
import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;

/**
 * Creates the avatars bucket in the test MinIO. In every other environment the bucket
 * is infrastructure — compose's minio-init makes it — and the service assumes it, so
 * the assumption is met here the same way: from outside the service.
 */
@Fixture
public class AvatarBucket {

    public AvatarBucket(MinioProperties minioProperties, AvatarProperties avatarProperties) throws Exception {
        MinioClient minio = MinioClient.builder()
                .endpoint(minioProperties.endpoint())
                .credentials(minioProperties.accessKey(), minioProperties.secretKey())
                .build();
        String bucket = avatarProperties.bucket();
        if (!minio.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
            minio.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
        }
    }
}
