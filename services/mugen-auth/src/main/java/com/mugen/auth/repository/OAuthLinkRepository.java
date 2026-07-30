package com.mugen.auth.repository;

import com.mugen.auth.domain.OAuthLink;
import com.mugen.auth.domain.OAuthProvider;
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
     * this? Fetches the user in the same query since the caller always needs it to
     * mint a token. Backed by {@code uq_oauth_links_provider_account}.
     */
    @Query("""
            select l from OAuthLink l
            join fetch l.user
            where l.provider = :provider
              and l.providerUserId = :providerUserId
            """)
    Optional<OAuthLink> findByProviderAccount(@Param("provider") OAuthProvider provider,
                                              @Param("providerUserId") String providerUserId);

    List<OAuthLink> findByUserId(UUID userId);

    boolean existsByUserIdAndProvider(UUID userId, OAuthProvider provider);
}
