package com.mugen.user.service;

import com.mugen.user.config.AvatarProperties;
import com.mugen.user.dto.AvatarUploadResponse;
import com.mugen.user.entity.UserProfile;
import com.mugen.user.exception.UserExceptions;
import com.mugen.user.repository.UserProfileRepository;
import com.mugen.storage.ObjectStorage;
import com.mugen.storage.PresignedUrls;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Avatars in three steps, none of which moves bytes through this service: a presigned
 * PUT URL, the upload straight to MinIO, then a confirm that checks the object exists
 * and records its key.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AvatarService {

    private static final Map<String, String> EXTENSIONS = Map.of(
            "image/png", "png",
            "image/jpeg", "jpg",
            "image/webp", "webp");

    private final UserProfileRepository profiles;
    private final ObjectStorage storage;
    private final PresignedUrls presignedUrls;
    private final AvatarProperties avatarProperties;

    /**
     * A fresh key under the caller's own prefix, so the confirm step can tell a key it
     * issued from one the caller invented. Nothing is recorded yet: an issued URL that
     * is never used costs nothing.
     */
    public AvatarUploadResponse requestUpload(UUID userId, String contentType) {
        profiles.findById(userId).orElseThrow(() -> new UserExceptions.ProfileNotFound(userId));

        String objectKey = keyFor(userId, EXTENSIONS.get(contentType));
        String url = storage.presignedPut(avatarProperties.bucket(), objectKey, contentType, avatarProperties.uploadUrlTtl());
        log.debug("Issued avatar upload URL userId={} objectKey={}", userId, objectKey);
        return new AvatarUploadResponse(url, objectKey, Instant.now().plus(avatarProperties.uploadUrlTtl()));
    }

    /**
     * Records the uploaded object as the avatar. The old object is deleted after the
     * row is written, outside the bracket's concern: a delete that fails leaves an
     * orphan in the bucket, which is a cost, where a row pointing at a deleted object
     * would be a broken profile.
     */
    @Transactional
    public void confirmUpload(UUID userId, String objectKey) {
        if (!objectKey.startsWith(prefixFor(userId))) {
            throw new UserExceptions.AvatarKeyNotOwned();
        }
        UserProfile profile = profiles.findById(userId).orElseThrow(() -> new UserExceptions.ProfileNotFound(userId));
        if (!storage.objectExists(avatarProperties.bucket(), objectKey)) {
            throw new UserExceptions.AvatarNotUploaded();
        }

        String previous = profile.replaceAvatar(objectKey);
        log.info("Avatar updated userId={}", userId);

        if (previous != null && !previous.equals(objectKey)) {
            presignedUrls.forget(avatarProperties.bucket(), previous);
            storage.delete(avatarProperties.bucket(), previous);
        }
    }

    /** A presigned GET URL for the profile's avatar, cached by the shared module, or null without one. */
    public String urlFor(UserProfile profile) {
        if (!profile.hasAvatar()) {
            return null;
        }
        return presignedUrls.get(avatarProperties.bucket(), profile.getAvatarKey());
    }

    /** {@code <userId>/<random>.<ext>}: the prefix is ownership, the random part makes every upload a new object. */
    static String keyFor(UUID userId, String extension) {
        return prefixFor(userId) + UUID.randomUUID() + "." + extension;
    }

    static String prefixFor(UUID userId) {
        return userId + "/";
    }
}
