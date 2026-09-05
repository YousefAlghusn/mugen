package com.mugen.auth.unit;

import com.mugen.auth.entity.OAuthProvider;
import com.mugen.auth.oauth.GoogleProfileMapper;
import com.mugen.auth.oauth.OAuthUserProfile;
import com.mugen.test.UnitTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The one decision in this class that matters: whether Google says it has verified the
 * address. Everything downstream of {@code emailVerified} treats a verified address as
 * proof of ownership — it is what lets a sign-in attach to an account that already
 * exists — so reading the claim wrongly is an account takeover, not a bug in a mapper.
 */
@UnitTest
class GoogleProfileMapperTest {

    private final GoogleProfileMapper mapper = new GoogleProfileMapper();

    private static OAuth2User user(Map<String, Object> attributes) {
        // "sub" is the user-name-attribute of the registration, which is what makes
        // getName() return the subject rather than a display name.
        return new DefaultOAuth2User(List.of(), attributes, "sub");
    }

    private static Map<String, Object> googleResponse(Object emailVerified) {
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("sub", "104283749238746293847");
        attributes.put("email", "kaneki@gmail.com");
        attributes.put("given_name", "Ken");
        attributes.put("email_verified", emailVerified);
        return attributes;
    }

    @Test
    @DisplayName("identity comes from sub, never from the email")
    void identityIsTheSubjectClaim() {
        OAuthUserProfile profile = mapper.map(user(googleResponse(true)), null);

        // sub is stable for the life of the Google account and never reissued, which is
        // what oauth_links needs: an address can move between accounts, a sub cannot.
        assertThat(profile.providerUserId()).isEqualTo("104283749238746293847");
        assertThat(profile.email()).isEqualTo("kaneki@gmail.com");
        assertThat(profile.emailVerified()).isTrue();
        assertThat(profile.suggestedUsername()).isEqualTo("kaneki");
        assertThat(mapper.provider()).isEqualTo(OAuthProvider.GOOGLE);
    }

    /** OIDC permits the string, and providers do send it; a real boolean is the common case. */
    @Test
    @DisplayName("a verified flag sent as the string \"true\" counts as verified")
    void acceptsTheStringForm() {
        assertThat(mapper.map(user(googleResponse("true")), null).emailVerified()).isTrue();
    }

    /**
     * Anything unrecognised is not verified. The default has to fall this way: an
     * unverified address is only a claim, and treating an unreadable one as verified
     * would let anyone put a stranger's address on a throwaway account and take over the
     * mugen account it belongs to.
     */
    @ParameterizedTest(name = "email_verified = {0}")
    @NullSource
    @ValueSource(strings = {"false", "yes", "1", ""})
    @DisplayName("anything that is not a clear yes is treated as unverified")
    void anythingElseIsUnverified(String claim) {
        assertThat(mapper.map(user(googleResponse(claim)), null).emailVerified()).isFalse();
    }

    @Test
    @DisplayName("a missing email_verified claim is unverified, not an error")
    void absentClaimIsUnverified() {
        Map<String, Object> attributes = googleResponse(true);
        attributes.remove("email_verified");

        assertThat(mapper.map(user(attributes), null).emailVerified()).isFalse();
    }

    /**
     * A display name is attacker-controlled text — spaces, emoji, right-to-left
     * overrides, or something shaped like another user's handle. The suggestion is what
     * an account is created with, so it is reduced to a known alphabet before it gets
     * anywhere near the database.
     */
    @Test
    @DisplayName("a username is suggested even when the provider shares nothing usable")
    void suggestsAUsableUsername() {
        Map<String, Object> attributes = googleResponse(true);
        attributes.put("email", "白銀@gmail.com");
        attributes.put("given_name", "Ken 🦴 Kaneki");

        assertThat(mapper.map(user(attributes), null).suggestedUsername()).isEqualTo("kenkaneki");
    }
}
