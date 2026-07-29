package com.mugen.shared.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Topic: mugen.payment.completed
 * Producer: mugen-payment
 * Consumers: mugen-notification
 */
public record PaymentCompletedEvent(
        UUID eventId,
        UUID paymentId,
        UUID userId,
        BigDecimal amount,
        String currency,
        Instant occurredAt
) {
}
