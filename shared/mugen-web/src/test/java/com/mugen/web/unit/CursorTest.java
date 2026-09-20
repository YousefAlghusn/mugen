package com.mugen.web.unit;

import com.mugen.web.pagination.Cursor;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A cursor that loses precision skips rows; one that accepts anything turns a typo
 * into a 500. Both are decisions the page query never sees.
 */
class CursorTest {

    @Test
    void roundTripsAtMicrosecondPrecision() {
        Cursor cursor = new Cursor(Instant.parse("2026-09-20T10:15:30.123456Z"), UUID.randomUUID());

        assertThat(Cursor.decode(cursor.encode())).contains(cursor);
    }

    @Test
    void absentMeansTheFirstPage() {
        assertThat(Cursor.decode(null)).isEmpty();
        assertThat(Cursor.decode("")).isEmpty();
    }

    @Test
    void somethingThatIsNotOursIsRefusedNotCrashedOn() {
        String wellFormedButWrong = Base64.getUrlEncoder().withoutPadding().encodeToString("page=3".getBytes());

        assertThatThrownBy(() -> Cursor.decode("not base64 at all!"))
                .isInstanceOf(Cursor.InvalidCursor.class);
        assertThatThrownBy(() -> Cursor.decode(wellFormedButWrong))
                .isInstanceOf(Cursor.InvalidCursor.class);
    }

    @Test
    void isOpaqueToClients() {
        Cursor cursor = new Cursor(Instant.now(), UUID.randomUUID());

        assertThat(cursor.encode()).doesNotContain("|", ":", "-");
    }
}
