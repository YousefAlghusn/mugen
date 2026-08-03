package com.mugen.auth.entity;

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
 * Identity for every entity in this service. The {@code equals}/{@code hashCode} pair
 * is the reason it exists — each line of it fixes a quiet JPA bug: a constant
 * {@code hashCode} so an entity does not change bucket when Hibernate assigns its id,
 * false-on-null-id so two unsaved instances are never the same row, and unwrapping
 * {@link HibernateProxy} so {@code User$Proxy} is not judged unequal to {@code User}.
 * Both are {@code final} because an override reintroduces all three.
 */
@MappedSuperclass
public abstract class BaseEntity {

    /**
     * A random UUID assigned before insert. They scatter across the index, which is
     * why every table declares its primary key {@code NONCLUSTERED} and clusters on
     * {@code created_at} so inserts append rather than split pages.
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
