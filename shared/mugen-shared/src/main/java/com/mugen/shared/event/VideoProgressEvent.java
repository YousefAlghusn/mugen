package com.mugen.shared.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.UUID;

/**
 * Topic: mugen.video.progress.events
 * Producer: mugen-transcode
 * Consumers: mugen-video, mugen-notification
 * <p>
 * status mirrors mugen-video's VideoStatus lifecycle (QUEUED, PROCESSING, READY, FAILED)
 * as a plain string so this contract doesn't depend on mugen-video's domain enum.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record VideoProgressEvent(
        UUID eventId,
        UUID videoId,
        UUID uploaderId,
        String status,
        int progressPercent,
        String hlsKey,
        Instant occurredAt
) implements DomainEvent {
}
