package com.mugen.user.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.domain.Persistable;

import java.time.Instant;
import java.util.UUID;

/**
 * What the platform knows about a person beyond their credentials. The id is the
 * account id mugen-auth assigned, arriving in {@code mugen.user.registered}.
 */
@Entity
@Table(name = "user_profiles")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // Required by JPA.
public class UserProfile implements Persistable<UUID> {

    public static final int DISPLAY_NAME_MAX = 80;
    public static final int BIO_MAX = 500;

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "username", nullable = false, length = 50, updatable = false)
    private String username;

    @Column(name = "display_name", nullable = false, length = DISPLAY_NAME_MAX)
    private String displayName;

    @Column(name = "bio", length = BIO_MAX)
    private String bio;

    @Column(name = "avatar_key", length = 200)
    private String avatarKey;

    @Column(name = "follower_count", nullable = false)
    private int followerCount;

    @Column(name = "following_count", nullable = false)
    private int followingCount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * The id is assigned by mugen-auth, never null, so Spring Data would otherwise take
     * the merge branch on save and pay a SELECT that misses. See {@link #markNotNew()}.
     */
    @Transient
    private boolean isNew = true;

    private UserProfile(UUID id, String username) {
        this.id = id;
        this.username = username;
        // Until the person chooses one, the display name is the username.
        this.displayName = username;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    /** From a registration event; nothing here is trusted beyond what mugen-auth said. */
    public static UserProfile fromRegistration(UUID userId, String username) {
        return new UserProfile(userId, username);
    }

    public void update(String displayName, String bio) {
        this.displayName = displayName;
        this.bio = bio;
    }

    /** @return the key being replaced, for the caller to delete, or null */
    public String replaceAvatar(String newAvatarKey) {
        String previous = this.avatarKey;
        this.avatarKey = newAvatarKey;
        return previous;
    }

    public boolean hasAvatar() {
        return avatarKey != null;
    }

    @PreUpdate
    void touch() {
        this.updatedAt = Instant.now();
    }

    @Override
    public UUID getId() {
        return id;
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
