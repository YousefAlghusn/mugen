package com.mugen.auth.repository;

import com.mugen.auth.entity.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    /**
     * Claims the next batch of unpublished events for the calling transaction.
     * <p>
     * Native because the method is really its three table hints, which JPQL cannot
     * express:
     * <ul>
     *   <li>{@code UPDLOCK} — lock at read time, not write time. Otherwise two
     *       pollers read the same row and both send it.</li>
     *   <li>{@code READPAST} — skip rows another poller holds rather than block on
     *       them. This is what lets a second instance make progress instead of
     *       waiting out the first one's Kafka round trip.</li>
     *   <li>{@code ROWLOCK} — no escalation to page or table locks under a backlog,
     *       which would block registrations inserting into this same table.</li>
     * </ul>
     * Postgres spells all three {@code FOR UPDATE SKIP LOCKED}, for whoever writes
     * mugen-payment's version.
     * <p>
     * Due time comes from the database clock rather than a parameter, so pollers
     * agree on "due" regardless of host clock skew.
     * <p>
     * Locks are held until the calling transaction ends — so the caller must be
     * transactional and brief. See {@link com.mugen.auth.service.OutboxPoller}.
     */
    @Query(value = """
            SELECT TOP (:batchSize) *
            FROM outbox_events WITH (UPDLOCK, READPAST, ROWLOCK)
            WHERE published_at IS NULL
              AND next_attempt_at <= CAST(SYSUTCDATETIME() AS DATETIMEOFFSET(7))
            ORDER BY created_at
            """, nativeQuery = true)
    List<OutboxEvent> claimBatch(@Param("batchSize") int batchSize);

    /**
     * Retention sweep, published rows only. An event still owed to Kafka is never
     * deleted however old or however often it has failed — that would lose it in
     * exactly the way this table exists to prevent.
     * <p>
     * A bulk statement rather than the derived {@code deleteByPublishedAtBefore},
     * which loads every match and removes them one at a time to fire lifecycle
     * callbacks. Same reasoning as {@code SessionRepository.revokeAllForUserExcept}.
     * <p>
     * The null check reads as redundant — {@code publishedAt < :before} already
     * excludes nulls — but SQL Server matches filtered indexes on the predicate, and
     * stating it is what lets this use {@code ix_outbox_events_published_at}.
     */
    // clearAutomatically, because a bulk delete goes round the persistence context:
    // without it a row deleted here is still served from the first-level cache to
    // anything that asks for it later in the same transaction.
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from OutboxEvent e where e.publishedAt is not null and e.publishedAt < :before")
    int deletePublishedBefore(@Param("before") Instant before);

    /**
     * Backlog depth. Healthy is near zero; a number that climbs means the poller is
     * losing to the write rate, or failing silently.
     */
    long countByPublishedAtIsNull();
}
