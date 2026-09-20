package com.mugen.user.support;

import com.mugen.test.Fixture;
import com.mugen.test.TokenSigner;
import com.mugen.user.service.UserService;

import java.util.UUID;

/**
 * A person with a profile, in one line. The profile is created the way production
 * creates it — through the registration path — and the token is one mugen-auth
 * would have issued for that account.
 */
@Fixture
public class Profiles {

    private final UserService userService;
    private final Tokens tokens;

    public Profiles(UserService userService, Tokens tokens) {
        this.userService = userService;
        this.tokens = tokens;
    }

    /**
     * @param userId   the account id, also the profile id
     * @param username as registered
     * @param token    an access token for this account
     */
    public record Person(UUID userId, String username, TokenSigner.Token token) {

        /** For {@code .header(HttpHeaders.AUTHORIZATION, person.bearer())}. */
        public String bearer() {
            return "Bearer " + token.value();
        }
    }

    public Person registered() {
        return registered("user-" + UUID.randomUUID().toString().substring(0, 8));
    }

    public Person registered(String username) {
        TokenSigner.Token token = tokens.accessToken();
        userService.createFromRegistration(token.userId(), username);
        return new Person(token.userId(), username, token);
    }

    /** An account whose registration event has not arrived: a valid token, no profile. */
    public Person unregistered() {
        TokenSigner.Token token = tokens.accessToken();
        return new Person(token.userId(), null, token);
    }
}
