package com.mugen.user.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.domain.Persistable;

import java.time.Instant;
import java.util.UUID;

/** One person following another. The pair is the identity; there is no surrogate id to invent. */
@Entity
@Table(name = "follows")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // Required by JPA.
public class Follow implements Persistable<FollowId> {

    @EmbeddedId
    private FollowId id;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** Same reason as in {@link UserProfile}: the id is never null, so save() must be told this is an insert. */
    @Transient
    private boolean isNew = true;

    private Follow(FollowId id) {
        this.id = id;
        this.createdAt = Instant.now();
    }

    public static Follow of(UUID followerId, UUID followeeId) {
        return new Follow(new FollowId(followerId, followeeId));
    }

    public UUID getFollowerId() {
        return id.followerId();
    }

    public UUID getFolloweeId() {
        return id.followeeId();
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostPersist
    @PostLoad
    void markNotNew() {
        this.isNew = false;
    }
}
