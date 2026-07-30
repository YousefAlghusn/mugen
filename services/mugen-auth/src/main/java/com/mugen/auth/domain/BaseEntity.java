package com.mugen.auth.domain;

import jakarta.persistence.Column;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import lombok.Getter;
import org.hibernate.proxy.HibernateProxy;

import java.util.Objects;
import java.util.UUID;

/**
 * Identity for every entity in this service.
 * <p>
 * The {@code equals}/{@code hashCode} pair here is the reason this class exists at
 * all. Getting it wrong on a JPA entity is subtle and breaks quietly:
 * <ul>
 *   <li>{@code hashCode} is a constant per type, not derived from the id. An entity
 *       added to a {@code HashSet} before being persisted would otherwise change
 *       bucket the moment Hibernate assigns its id, and become unfindable in the
 *       set it is already in.</li>
 *   <li>{@code equals} returns false when the id is null, so two distinct unsaved
 *       instances are never considered the same row.</li>
 *   <li>Both unwrap {@link HibernateProxy}. A lazy association is a generated
 *       subclass, so a naive {@code getClass()} comparison finds {@code User$Proxy}
 *       != {@code User} and reports two references to the same row as unequal.</li>
 * </ul>
 * Both are {@code final}: subclasses overriding them would reintroduce exactly
 * these bugs.
 */
@MappedSuperclass
public abstract class BaseEntity {

    /**
     * Assigned by Hibernate as a random UUID before insert.
     * <p>
     * Random UUIDs scatter across the index, which is why every table declares its
     * primary key {@code NONCLUSTERED} and clusters on {@code created_at} instead —
     * see the migrations. Inserts then append rather than splitting pages.
     */
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    @Getter
    private UUID id;

    @Override
    public final boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (other == null) {
            return false;
        }
        if (!effectiveClassOf(this).equals(effectiveClassOf(other))) {
            return false;
        }
        UUID otherId = ((BaseEntity) other).getId();
        return id != null && id.equals(otherId);
    }

    @Override
    public final int hashCode() {
        return effectiveClassOf(this).hashCode();
    }

    @Override
    public String toString() {
        return "%s{id=%s}".formatted(effectiveClassOf(this).getSimpleName(), id);
    }

    /** The real entity type, seeing through a Hibernate lazy-loading proxy. */
    private static Class<?> effectiveClassOf(Object candidate) {
        return candidate instanceof HibernateProxy proxy
                ? proxy.getHibernateLazyInitializer().getPersistentClass()
                : Objects.requireNonNull(candidate).getClass();
    }
}
