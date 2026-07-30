package com.mugen.auth.repository;

import com.mugen.auth.domain.Session;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface SessionRepository extends JpaRepository<Session, UUID> {

    /**
     * Loads a session for refresh. Locking is deliberate: two refresh calls racing
     * on the same session would otherwise both read the same {@code token_version},
     * both rotate to the same next value, and issue two valid refresh tokens —
     * turning a legitimate double-submit into an undetectable fork of the session.
     * The pessimistic write lock serialises them so the second sees the rotated
     * version and is correctly identified as a replay.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Session s join fetch s.user where s.id = :id")
    Optional<Session> findForRotation(@Param("id") UUID id);

    /** Live sessions for the "your devices" listing, newest first. */
    @Query("""
            select s from Session s
            where s.user.id = :userId
              and s.revokedAt is null
              and s.expiresAt > :now
            order by s.createdAt desc
            """)
    List<Session> findActiveByUserId(@Param("userId") UUID userId, @Param("now") Instant now);

    /**
     * Bulk revoke, used by "log out everywhere" and by replay detection. Written as
     * a single UPDATE rather than a load-and-mutate loop so that revoking a user
     * with many sessions is one statement, not N.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Session s
            set s.revokedAt = :now
            where s.user.id = :userId
              and s.revokedAt is null
              and s.id <> :exceptSessionId
            """)
    int revokeAllForUserExcept(@Param("userId") UUID userId,
                               @Param("exceptSessionId") UUID exceptSessionId,
                               @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Session s
            set s.revokedAt = :now
            where s.user.id = :userId
              and s.revokedAt is null
            """)
    int revokeAllForUser(@Param("userId") UUID userId, @Param("now") Instant now);

    /** Housekeeping: expired rows are no longer needed as audit history. */
    @Modifying
    @Query("delete from Session s where s.expiresAt < :before")
    int deleteExpiredBefore(@Param("before") Instant before);
}
