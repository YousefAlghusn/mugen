package com.mugen.shared.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.UUID;

/**
 * Topic: mugen.video.transcode.jobs
 * Producer: mugen-video
 * Consumer: mugen-transcode
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record VideoTranscodeJobEvent(
        UUID eventId,
        UUID videoId,
        UUID uploaderId,
        String rawObjectKey,
        Instant occurredAt
) implements DomainEvent {
}
