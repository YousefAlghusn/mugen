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
     * express: {@code UPDLOCK} locks at read time so two pollers cannot both send a
     * row, {@code READPAST} skips rows another poller holds instead of blocking on
     * its Kafka round trip, and {@code ROWLOCK} stops escalation from blocking
     * registrations inserting here. Postgres spells all three
     * {@code FOR UPDATE SKIP LOCKED}.
     * <p>
     * Due time comes from the database clock, so pollers agree despite host skew.
     * Locks are held until the caller's transaction ends — it must be transactional
     * and brief. See {@link com.mugen.auth.service.OutboxPoller}.
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
     * Retention sweep, published rows only — an event still owed to Kafka is never
     * deleted, however old, which is the point of the table.
     * <p>
     * Bulk rather than the derived {@code deleteByPublishedAtBefore}, which would load
     * every match to fire lifecycle callbacks. The null check reads as redundant but
     * is what lets SQL Server match the filtered index
     * {@code ix_outbox_events_published_at}.
     */
    // clearAutomatically: a bulk delete bypasses the persistence context, which would
    // otherwise keep serving the deleted row for the rest of the transaction.
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from OutboxEvent e where e.publishedAt is not null and e.publishedAt < :before")
    int deletePublishedBefore(@Param("before") Instant before);

    /**
     * Backlog depth. Healthy is near zero; a number that climbs means the poller is
     * losing to the write rate, or failing silently.
     */
    long countByPublishedAtIsNull();
}
