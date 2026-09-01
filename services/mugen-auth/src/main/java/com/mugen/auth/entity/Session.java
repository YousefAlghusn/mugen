package com.mugen.auth.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

/**
 * One active login. The refresh token carries only {@code {sessionId, version}};
 * this row decides whether that pair is still valid.
 * <p>
 * No setters at all: every state change goes through a named method that carries
 * the rule with it ({@link #rotate()}, {@link #revoke()}, {@link #touch()}). A
 * generated {@code setTokenVersion} would make it possible to move the version
 * backwards, which would defeat replay detection entirely.
 */
@Entity
@Table(name = "sessions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // Required by JPA.
public class Session extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "token_version", nullable = false)
    private int tokenVersion;

    @Column(name = "user_agent", length = 400)
    private String userAgent;

    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "last_used_at", nullable = false)
    private Instant lastUsedAt = Instant.now();

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    /** Null while live. Never cleared — a revoked session stays revoked. */
    @Column(name = "revoked_at")
    private Instant revokedAt;

    private Session(User user, Instant expiresAt, String userAgent, String ipAddress) {
        this.user = user;
        this.expiresAt = expiresAt;
        this.userAgent = userAgent;
        this.ipAddress = ipAddress;
        this.tokenVersion = 0;
    }

    public static Session open(User user, Instant expiresAt, String userAgent, String ipAddress) {
        return new Session(user, expiresAt, userAgent, ipAddress);
    }

    /**
     * Advances the session to the next token version and returns it. The refresh
     * token issued to the caller must carry the returned value.
     * <p>
     * Because the version only ever moves forward, a refresh token presenting an
     * older version is by definition one that has already been spent — see
     * {@link #isReplayOf(int)}.
     */
    public int rotate() {
        tokenVersion++;
        lastUsedAt = Instant.now();
        return tokenVersion;
    }

    /**
     * @return true when {@code presentedVersion} is not the session's current one:
     *         an older, already-rotated token (a captured refresh token, replayed),
     *         or a version never issued at all
     * <p>
     * The correct response is to revoke the whole session, not merely to reject
     * this request. Once a refresh token has leaked there is no way to tell the
     * attacker's request from the legitimate user's, and the attacker may already
     * hold the newer token — rejecting one call would leave them logged in.
     */
    public boolean isReplayOf(int presentedVersion) {
        return presentedVersion != tokenVersion;
    }

    public void revoke() {
        if (revokedAt == null) {
            revokedAt = Instant.now();
        }
    }

    public boolean isActive(Instant now) {
        return revokedAt == null && now.isBefore(expiresAt);
    }

    public void touch() {
        lastUsedAt = Instant.now();
    }
}
