package com.mugen.web.pagination;

import com.mugen.web.error.BusinessRuleException;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

/**
 * Where a page ends, as a keyset: the last row's {@code (createdAt, id)}. The next page
 * is every row strictly before it in that order — an index range scan that costs the
 * same on page one and page ten thousand, unlike an offset, which counts from the top
 * every time and skips or repeats rows as the list changes underneath.
 * <p>
 * Opaque to clients by being base64url: the shape is this service's to change.
 *
 * @param createdAt the last row's timestamp
 * @param id        the tiebreaker, because two follows can share a timestamp
 */
public record Cursor(Instant createdAt, UUID id) {

    private static final String SEPARATOR = "|";

    public String encode() {
        // Full precision, never epoch millis: the column holds microseconds, and a cursor
        // rounded down would skip every row in the same millisecond as the one it names.
        String raw = createdAt + SEPARATOR + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /** Empty for a missing cursor — the first page. A present but unreadable one is the caller's mistake. */
    public static Optional<Cursor> decode(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return Optional.empty();
        }
        try {
            String raw = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
            int separator = raw.indexOf(SEPARATOR);
            if (separator < 0) {
                throw new IllegalArgumentException("no separator");
            }
            return Optional.of(new Cursor(
                    Instant.parse(raw.substring(0, separator)),
                    UUID.fromString(raw.substring(separator + 1))));
        } catch (IllegalArgumentException | DateTimeParseException ex) {
            throw new InvalidCursor();
        }
    }

    /** 422 rather than 400: the request is well formed, the value in it is not one this service issued. */
    public static class InvalidCursor extends BusinessRuleException {
        public InvalidCursor() {
            super("The cursor is not one this endpoint issued.");
        }
    }
}
