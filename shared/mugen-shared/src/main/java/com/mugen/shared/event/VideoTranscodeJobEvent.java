package com.mugen.shared.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Topic: mugen.video.transcode.jobs
 * Producer: mugen-video
 * Consumer: mugen-transcode
 */
public record VideoTranscodeJobEvent(
        UUID eventId,
        UUID videoId,
        UUID uploaderId,
        String rawObjectKey,
        Instant occurredAt
) {
}
