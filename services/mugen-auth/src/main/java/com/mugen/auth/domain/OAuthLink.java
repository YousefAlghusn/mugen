package com.mugen.auth.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
 * Binds an external provider account to a local {@link User}.
 * <p>
 * Immutable once created — a link is either present or deleted, never edited, so
 * no setters are generated.
 */
@Entity
@Table(name = "oauth_links")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // Required by JPA.
public class OAuthLink extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /**
     * STRING, not ORDINAL. An ordinal encodes the enum's declaration order into
     * the database, so inserting a provider into the middle of the enum later
     * would silently re-point every existing row at a different provider.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false, length = 20)
    private OAuthProvider provider;

    /**
     * The provider's stable id for the account — Google's {@code sub}, GitHub's
     * numeric id. Never the email address: emails are user-changeable and get
     * reassigned, so keying on one would let a person inherit another's account.
     */
    @Column(name = "provider_user_id", nullable = false, length = 200)
    private String providerUserId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    private OAuthLink(User user, OAuthProvider provider, String providerUserId) {
        this.user = user;
        this.provider = provider;
        this.providerUserId = providerUserId;
    }

    public static OAuthLink link(User user, OAuthProvider provider, String providerUserId) {
        return new OAuthLink(user, provider, providerUserId);
    }
}
