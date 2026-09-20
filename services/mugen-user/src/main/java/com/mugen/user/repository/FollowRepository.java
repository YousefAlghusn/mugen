package com.mugen.user.repository;

import com.mugen.user.entity.Follow;
import com.mugen.user.entity.FollowId;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface FollowRepository extends JpaRepository<Follow, FollowId> {

    /**
     * Who follows {@code followeeId}, newest first, from a keyset cursor.
     * <p>
     * The tuple comparison is written out because JPQL has no row-value syntax: a row
     * is "before" the cursor if it is older, or the same age with a smaller id. Both
     * halves are what the {@code (followee_id, created_at DESC, follower_id DESC)} index
     * is ordered by, so this is a range scan with no sort. The first page passes the
     * far future and the max UUID and matches everything.
     */
    @Query("""
            select f from Follow f
            where f.id.followeeId = :followeeId
              and (f.createdAt < :before or (f.createdAt = :before and f.id.followerId < :beforeId))
            order by f.createdAt desc, f.id.followerId desc
            """)
    List<Follow> followersOf(@Param("followeeId") UUID followeeId,
                             @Param("before") Instant before,
                             @Param("beforeId") UUID beforeId,
                             Limit limit);

    /** Who {@code followerId} follows, same keyset, on the primary key's sibling index. */
    @Query("""
            select f from Follow f
            where f.id.followerId = :followerId
              and (f.createdAt < :before or (f.createdAt = :before and f.id.followeeId < :beforeId))
            order by f.createdAt desc, f.id.followeeId desc
            """)
    List<Follow> followingOf(@Param("followerId") UUID followerId,
                             @Param("before") Instant before,
                             @Param("beforeId") UUID beforeId,
                             Limit limit);
}
