package com.mugen.auth.repository;

import com.mugen.auth.entity.OAuthLink;
import com.mugen.auth.entity.OAuthProvider;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface OAuthLinkRepository extends JpaRepository<OAuthLink, UUID> {

    /**
     * The SSO callback lookup: given the provider's account id, which local user is
     * this? Fetches the user <em>and its roles</em> in the same query, because the
     * caller mints a token from both and the entity is detached by the time it does
     * — the transaction around the lookup has committed. Backed by
     * {@code uq_oauth_links_provider_account}.
     */
    @Query("""
            select l from OAuthLink l
            join fetch l.user u
            left join fetch u.roles
            where l.provider = :provider
              and l.providerUserId = :providerUserId
            """)
    Optional<OAuthLink> findByProviderAccount(@Param("provider") OAuthProvider provider,
                                              @Param("providerUserId") String providerUserId);

    List<OAuthLink> findByUserId(UUID userId);

    boolean existsByUserIdAndProvider(UUID userId, OAuthProvider provider);
}
