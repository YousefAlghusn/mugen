package com.mugen.outbox;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
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
     * The method is really its lock: {@code PESSIMISTIC_WRITE} so two pollers cannot
     * both send a row, and a lock timeout of {@code -2} — Hibernate's spelling of
     * <em>skip locked</em> — so a poller steps over rows another one holds instead of
     * blocking on its Kafka round trip. The dialect renders it: {@code FOR UPDATE SKIP
     * LOCKED} on Postgres, {@code WITH (UPDLOCK, ROWLOCK, READPAST)} on SQL Server. One
     * query, every database mugen uses, which is why this is JPQL and not the native
     * hints it replaced.
     * <p>
     * Due time comes from the caller's clock. Host skew moves a retry by the skew,
     * which against backoffs measured in seconds is nothing. Locks are held until the
     * caller's transaction ends — it must be transactional and brief. See
     * {@link OutboxPoller}.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("""
            select e from OutboxEvent e
            where e.publishedAt is null
              and e.nextAttemptAt <= :now
            order by e.createdAt
            """)
    List<OutboxEvent> claimBatch(@Param("now") Instant now, Limit limit);

    /**
     * Retention sweep, published rows only — an event still owed to Kafka is never
     * deleted, however old, which is the point of the table.
     * <p>
     * Bulk rather than the derived {@code deleteByPublishedAtBefore}, which would load
     * every match to fire lifecycle callbacks. The null check reads as redundant but
     * is what lets a filtered index on {@code published_at} be used.
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
