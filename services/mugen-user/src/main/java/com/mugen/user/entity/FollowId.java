package com.mugen.user.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.util.UUID;

/** The follows table's composite key. A record, so equality is the pair's — which JPA requires of an id. */
@Embeddable
public record FollowId(
        @Column(name = "follower_id", nullable = false, updatable = false) UUID followerId,
        @Column(name = "followee_id", nullable = false, updatable = false) UUID followeeId
) {
}
