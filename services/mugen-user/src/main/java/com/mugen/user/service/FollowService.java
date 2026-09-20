package com.mugen.user.service;

import com.mugen.shared.response.CursorPage;
import com.mugen.user.dto.FollowSummary;
import com.mugen.user.entity.Follow;
import com.mugen.user.entity.FollowId;
import com.mugen.user.entity.UserProfile;
import com.mugen.user.exception.UserExceptions;
import com.mugen.web.pagination.Cursor;
import com.mugen.user.repository.FollowRepository;
import com.mugen.user.repository.UserProfileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Follows: the row, the two counters and the event, in one transaction; and the two lists behind a cursor. */
@Slf4j
@Service
@RequiredArgsConstructor
public class FollowService {

    /** The cap on {@code ?limit=}; a client asking for more gets this many. */
    public static final int MAX_PAGE_SIZE = 100;
    public static final int DEFAULT_PAGE_SIZE = 20;

    /** The sentinel a first page passes: later than any row, larger than any id. */
    private static final Instant FAR_FUTURE = Instant.parse("9999-12-31T23:59:59Z");
    private static final UUID MAX_UUID = new UUID(-1L, -1L);

    private final FollowRepository follows;
    private final UserProfileRepository profiles;
    private final FollowEventPublisher events;

    /**
     * The duplicate is decided by the primary key, not by a read first: two requests
     * for the same pair would both pass the read and both insert. The flush inside the
     * try turns the second one into the 409 it is, inside this transaction, where the
     * rollback also undoes nothing — the counters are only touched after it.
     */
    @Transactional
    public void follow(UUID followerId, UUID followeeId) {
        if (followerId.equals(followeeId)) {
            throw new UserExceptions.CannotFollowSelf();
        }
        // Both must exist, and the follower is checked first: a token whose profile has
        // not arrived yet would otherwise fail the foreign key and be reported as a 409.
        requireProfile(followerId);
        UserProfile followee = profiles.findById(followeeId)
                .orElseThrow(() -> new UserExceptions.ProfileNotFound(followeeId));

        try {
            follows.saveAndFlush(Follow.of(followerId, followee.getId()));
        } catch (DataIntegrityViolationException duplicate) {
            throw new UserExceptions.AlreadyFollowing(followeeId);
        }

        profiles.adjustFollowerCount(followeeId, +1);
        profiles.adjustFollowingCount(followerId, +1);
        events.userFollowed(followerId, followeeId);
        log.info("Follow created followerId={} followeeId={}", followerId, followeeId);
    }

    @Transactional
    public void unfollow(UUID followerId, UUID followeeId) {
        FollowId id = new FollowId(followerId, followeeId);
        if (!follows.existsById(id)) {
            throw new UserExceptions.FollowNotFound(followeeId);
        }
        follows.deleteById(id);
        profiles.adjustFollowerCount(followeeId, -1);
        profiles.adjustFollowingCount(followerId, -1);
        log.info("Follow removed followerId={} followeeId={}", followerId, followeeId);
    }

    @Transactional(readOnly = true)
    public CursorPage<FollowSummary> followers(UUID userId, String cursor, Integer limit) {
        requireProfile(userId);
        Cursor from = Cursor.decode(cursor).orElse(new Cursor(FAR_FUTURE, MAX_UUID));
        int size = pageSize(limit);
        // One more than the page: its presence is what says there is a next page, and
        // it is never returned — the cursor points at the last row that is.
        List<Follow> rows = follows.followersOf(userId, from.createdAt(), from.id(), Limit.of(size + 1));
        return page(rows, size, Follow::getFollowerId);
    }

    @Transactional(readOnly = true)
    public CursorPage<FollowSummary> following(UUID userId, String cursor, Integer limit) {
        requireProfile(userId);
        Cursor from = Cursor.decode(cursor).orElse(new Cursor(FAR_FUTURE, MAX_UUID));
        int size = pageSize(limit);
        List<Follow> rows = follows.followingOf(userId, from.createdAt(), from.id(), Limit.of(size + 1));
        return page(rows, size, Follow::getFolloweeId);
    }

    private void requireProfile(UUID userId) {
        if (!profiles.existsById(userId)) {
            throw new UserExceptions.ProfileNotFound(userId);
        }
    }

    static int pageSize(Integer requested) {
        if (requested == null || requested < 1) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(requested, MAX_PAGE_SIZE);
    }

    private CursorPage<FollowSummary> page(List<Follow> rows, int size, Function<Follow, UUID> otherSide) {
        boolean hasMore = rows.size() > size;
        List<Follow> page = hasMore ? rows.subList(0, size) : rows;

        // One query for every profile on the page, never one per row.
        Map<UUID, UserProfile> byId = profiles.findAllById(page.stream().map(otherSide).toList()).stream()
                .collect(Collectors.toMap(UserProfile::getId, Function.identity()));

        List<FollowSummary> items = page.stream()
                .map(follow -> {
                    UserProfile other = byId.get(otherSide.apply(follow));
                    return new FollowSummary(other.getId(), other.getUsername(), other.getDisplayName(), follow.getCreatedAt());
                })
                .toList();

        String next = hasMore
                ? new Cursor(page.getLast().getCreatedAt(), otherSide.apply(page.getLast())).encode()
                : null;
        return CursorPage.of(items, next);
    }
}
