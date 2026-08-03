package com.mugen.auth.entity;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * A credential-bearing account — identity and nothing else. Display name, bio and
 * avatar belong to mugen-user, joined by the {@code mugen.user.registered} event and
 * never by a query.
 * <p>
 * No {@code @Data} or {@code @EqualsAndHashCode} on purpose: identity comes from
 * {@link BaseEntity}, and setters are opt-in per field so immutable state has no way
 * to be changed.
 */
@Entity
@Table(name = "users")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // Required by JPA, not for callers.
public class User extends BaseEntity {

    @Column(name = "username", nullable = false, length = 50)
    private String username;

    @Column(name = "email", nullable = false, length = 320)
    private String email;

    /**
     * Null for an account that only ever signed in through SSO. Callers must treat
     * null as "password login is not possible for this account" — never as a value
     * to compare against.
     */
    @Setter
    @Column(name = "password_hash", length = 100)
    private String passwordHash;

    @Setter
    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    /**
     * Lazy, because most reads of a User do not need roles. The login path does,
     * and fetches them in one query via {@code @EntityGraph} on the repository
     * rather than paying an N+1 or making every other read eager.
     */
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "user_roles", joinColumns = @JoinColumn(name = "user_id"))
    @Column(name = "role", nullable = false, length = 50)
    private Set<String> roles = new LinkedHashSet<>();

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    private User(String username, String email, String passwordHash, Set<String> roles) {
        this.username = username;
        this.email = email;
        this.passwordHash = passwordHash;
        this.roles = new LinkedHashSet<>(roles);
    }

    /** An account that signs in with a password. */
    public static User withPassword(String username, String email, String passwordHash) {
        return new User(username, email, passwordHash, Set.of(Role.USER));
    }

    /**
     * An account created from an SSO callback, with no local password. It can only
     * ever sign in through the linked provider unless a password is set later.
     */
    public static User fromSso(String username, String email) {
        return new User(username, email, null, Set.of(Role.USER));
    }

    /** @return whether this account is able to authenticate with a password at all */
    public boolean hasPassword() {
        return passwordHash != null;
    }

    /**
     * Defensive copy — Lombok's generated getter would hand out the live collection
     * and let callers mutate persistent state behind the entity's back. Declaring
     * it explicitly suppresses the generated one.
     */
    public Set<String> getRoles() {
        return Set.copyOf(roles);
    }

    public void grantRole(String role) {
        roles.add(role);
    }
}
