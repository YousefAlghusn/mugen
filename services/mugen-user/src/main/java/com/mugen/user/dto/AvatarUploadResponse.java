package com.mugen.user.dto;

import java.time.Instant;

/**
 * Where to PUT the file, and what to send back once it is there.
 *
 * @param uploadUrl a presigned PUT URL for MinIO; the file goes straight there, never
 *                  through this service
 * @param objectKey what to confirm with afterwards
 * @param expiresAt when the URL stops working
 */
public record AvatarUploadResponse(String uploadUrl, String objectKey, Instant expiresAt) {
}
