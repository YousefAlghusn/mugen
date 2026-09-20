package com.mugen.shared.response;

import java.util.List;

/**
 * One page of a cursor-paginated list, the shape every mugen list endpoint answers with.
 *
 * @param items      this page, in the order the endpoint documents
 * @param nextCursor opaque; send it back as {@code ?cursor=} for the page after this
 *                   one, and stop when it is null. Never an offset: an offset re-counts
 *                   from the top and skips or repeats rows as the list changes underneath
 */
public record CursorPage<T>(List<T> items, String nextCursor) {

    public static <T> CursorPage<T> of(List<T> items, String nextCursor) {
        return new CursorPage<>(List.copyOf(items), nextCursor);
    }
}
