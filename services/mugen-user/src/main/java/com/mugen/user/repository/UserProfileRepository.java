package com.mugen.user.repository;

import com.mugen.user.entity.UserProfile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserProfileRepository extends JpaRepository<UserProfile, UUID> {

    Optional<UserProfile> findByUsername(String username);

    /**
     * Counters are adjusted in SQL, never read-modify-written through the entity: two
     * follows landing at once would each load the same count and one increment would
     * be lost. {@code clearAutomatically} because the bulk update bypasses the
     * persistence context, which would otherwise keep serving the stale count.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update UserProfile p set p.followerCount = p.followerCount + :delta where p.id = :id")
    int adjustFollowerCount(@Param("id") UUID id, @Param("delta") int delta);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update UserProfile p set p.followingCount = p.followingCount + :delta where p.id = :id")
    int adjustFollowingCount(@Param("id") UUID id, @Param("delta") int delta);
}
