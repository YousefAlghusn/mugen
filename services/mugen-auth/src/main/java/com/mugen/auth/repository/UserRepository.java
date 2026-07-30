package com.mugen.auth.repository;

import com.mugen.auth.domain.User;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserRepository extends JpaRepository<User, UUID> {

    /**
     * The login lookup. The {@code roles} entity graph pulls the collection in the
     * same query, because the caller is about to put those roles in a JWT — without
     * it this is a guaranteed second round trip on the hottest path in the service.
     */
    @EntityGraph(attributePaths = "roles")
    Optional<User> findByEmail(String email);

    @EntityGraph(attributePaths = "roles")
    Optional<User> findByUsername(String username);

    @EntityGraph(attributePaths = "roles")
    Optional<User> findWithRolesById(UUID id);

    /**
     * Used to fail registration early with a clear message. This is a UX check, not
     * the integrity guarantee — two concurrent registrations can both pass it, and
     * the unique indexes from V1 are what actually prevent the duplicate. The
     * service must therefore still handle the constraint violation.
     */
    boolean existsByEmail(String email);

    boolean existsByUsername(String username);
}
