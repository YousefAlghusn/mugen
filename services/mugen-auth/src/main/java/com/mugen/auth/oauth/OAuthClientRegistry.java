package com.mugen.auth.oauth;

import com.mugen.auth.entity.OAuthProvider;
import com.mugen.auth.exception.AuthExceptions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Resolves an {@link OAuthProvider} to its Spring Security {@link ClientRegistration},
 * and to the mapper that understands its user-info response.
 * <p>
 * The registration repository comes through an {@link ObjectProvider} because Boot only
 * creates one under the {@code sso} profile, and mugen-auth must still start without it.
 * Paid once here so nothing downstream has to think about it.
 */
@Component
public class OAuthClientRegistry {

    private final ObjectProvider<ClientRegistrationRepository> clientRegistrations;
    private final Map<OAuthProvider, OAuthProfileMapper> mappers = new EnumMap<>(OAuthProvider.class);

    public OAuthClientRegistry(ObjectProvider<ClientRegistrationRepository> clientRegistrations,
                               List<OAuthProfileMapper> profileMappers) {
        this.clientRegistrations = clientRegistrations;
        profileMappers.forEach(mapper -> this.mappers.put(mapper.provider(), mapper));
    }

    /**
     * @throws AuthExceptions.SsoProviderNotConfigured when SSO is switched off
     *         entirely, or this one provider has no credentials. Both are answered
     *         as 404 rather than 500: from a client's point of view the provider is
     *         simply not on offer here.
     */
    public ClientRegistration registrationFor(OAuthProvider provider) {
        ClientRegistrationRepository repository = clientRegistrations.getIfAvailable();
        if (repository == null) {
            throw new AuthExceptions.SsoProviderNotConfigured(provider.name());
        }

        // The registration id is the lowercased enum name, which is also what the
        // callback URL carries — /api/v1/auth/sso/google/callback.
        ClientRegistration registration = repository.findByRegistrationId(registrationIdOf(provider));
        if (registration == null) {
            throw new AuthExceptions.SsoProviderNotConfigured(provider.name());
        }
        return registration;
    }

    public OAuthProfileMapper mapperFor(OAuthProvider provider) {
        OAuthProfileMapper mapper = mappers.get(provider);
        if (mapper == null) {
            // Unreachable unless a provider is added to the enum without a mapper.
            // Failing loudly beats silently signing someone in off an unmapped
            // response.
            throw new IllegalStateException("No OAuthProfileMapper for provider " + provider);
        }
        return mapper;
    }

    public static String registrationIdOf(OAuthProvider provider) {
        return provider.name().toLowerCase(Locale.ROOT);
    }
}
