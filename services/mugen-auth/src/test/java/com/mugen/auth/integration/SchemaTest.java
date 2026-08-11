package com.mugen.auth.integration;

import com.mugen.auth.entity.OAuthLink;
import com.mugen.auth.entity.OAuthProvider;
import com.mugen.auth.entity.Session;
import com.mugen.auth.entity.User;
import com.mugen.auth.repository.OAuthLinkRepository;
import com.mugen.auth.repository.SessionRepository;
import com.mugen.auth.repository.UserRepository;
import com.mugen.auth.support.AuthIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves the Flyway migrations and the JPA mappings actually agree, against a real
 * SQL Server rather than an in-memory substitute.
 * <p>
 * This is worth a container because H2 would not catch any of what can go wrong
 * here: {@code UNIQUEIDENTIFIER} handling, {@code NONCLUSTERED} primary keys, the
 * filtered unique indexes, or {@code DATETIME2} precision. The context also runs
 * with {@code ddl-auto: validate}, so a column this service's entities expect but
 * no migration creates fails the test at startup.
 */
@AuthIntegrationTest
// Rolls back after each test, so one test's rows never leak into the next. Also
// supplies the transaction that @Modifying repository methods need in order to flush.
@Transactional
class SchemaTest {

    @Autowired
    private UserRepository users;

    @Autowired
    private SessionRepository sessions;

    @Autowired
    private OAuthLinkRepository oauthLinks;

    @Test
    @DisplayName("migrations apply and Hibernate validates the resulting schema")
    void contextLoadsAgainstMigratedSchema() {
        // Reaching this point means Flyway ran V1-V3 and ddl-auto=validate agreed
        // with every entity mapping. Both are startup-time guarantees.
        assertThat(users.count()).isZero();
    }

    @Test
    @DisplayName("a password user round-trips with its roles")
    void persistsUserWithRoles() {
        User saved = users.save(User.withPassword("kaneki", "kaneki@mugen.dev", "{bcrypt}hash"));

        Optional<User> found = users.findByEmail("kaneki@mugen.dev");

        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(saved.getId());
        assertThat(found.get().getRoles()).containsExactly("ROLE_USER");
        assertThat(found.get().hasPassword()).isTrue();
        assertThat(found.get().getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("an SSO user persists with a null password hash")
    void persistsSsoUserWithoutPassword() {
        users.save(User.fromSso("touka", "touka@mugen.dev"));

        User found = users.findByEmail("touka@mugen.dev").orElseThrow();

        assertThat(found.getPasswordHash()).isNull();
        assertThat(found.hasPassword()).isFalse();
    }

    @Test
    @DisplayName("the unique email index rejects a duplicate the exists-check would miss")
    void enforcesUniqueEmail() {
        users.save(User.withPassword("first", "dup@mugen.dev", "{bcrypt}hash"));

        // This is the race existsByEmail cannot close: the constraint is the real
        // guarantee, and the service has to handle this exception.
        assertThatThrownBy(() -> users.saveAndFlush(User.withPassword("second", "dup@mugen.dev", "{bcrypt}hash")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("rotation advances token_version and detects a replayed version")
    void sessionRotationDetectsReplay() {
        User user = users.save(User.withPassword("rize", "rize@mugen.dev", "{bcrypt}hash"));
        Session session = sessions.save(Session.open(
                user, Instant.now().plus(30, ChronoUnit.DAYS), "JUnit", "127.0.0.1"));

        assertThat(session.getTokenVersion()).isZero();

        int rotated = session.rotate();
        sessions.saveAndFlush(session);

        assertThat(rotated).isEqualTo(1);
        // The version the caller was originally issued is now stale — replay.
        assertThat(session.isReplayOf(0)).isTrue();
        assertThat(session.isReplayOf(1)).isFalse();
    }

    @Test
    @DisplayName("active-session listing excludes revoked sessions")
    void listsOnlyActiveSessions() {
        User user = users.save(User.withPassword("yomo", "yomo@mugen.dev", "{bcrypt}hash"));
        Instant expiry = Instant.now().plus(30, ChronoUnit.DAYS);

        sessions.save(Session.open(user, expiry, "device-a", "127.0.0.1"));
        Session revoked = sessions.save(Session.open(user, expiry, "device-b", "127.0.0.1"));
        revoked.revoke();
        sessions.saveAndFlush(revoked);

        List<Session> active = sessions.findActiveByUserId(user.getId(), Instant.now());

        assertThat(active).hasSize(1);
        assertThat(active.getFirst().getUserAgent()).isEqualTo("device-a");
    }

    @Test
    @DisplayName("revokeAllForUserExcept spares the current session")
    void revokesAllOtherSessions() {
        User user = users.save(User.withPassword("nishiki", "nishiki@mugen.dev", "{bcrypt}hash"));
        Instant expiry = Instant.now().plus(30, ChronoUnit.DAYS);

        Session current = sessions.save(Session.open(user, expiry, "current", "127.0.0.1"));
        sessions.save(Session.open(user, expiry, "other-1", "127.0.0.1"));
        sessions.saveAndFlush(Session.open(user, expiry, "other-2", "127.0.0.1"));

        int revokedCount = sessions.revokeAllForUserExcept(user.getId(), current.getId(), Instant.now());

        assertThat(revokedCount).isEqualTo(2);
        assertThat(sessions.findActiveByUserId(user.getId(), Instant.now()))
                .extracting(Session::getUserAgent)
                .containsExactly("current");
    }

    @Test
    @DisplayName("an oauth link resolves back to its user by provider account id")
    void resolvesUserByProviderAccount() {
        User user = users.save(User.fromSso("hinami", "hinami@mugen.dev"));
        oauthLinks.saveAndFlush(OAuthLink.link(user, OAuthProvider.GOOGLE, "google-sub-12345"));

        Optional<OAuthLink> found =
                oauthLinks.findByProviderAccount(OAuthProvider.GOOGLE, "google-sub-12345");

        assertThat(found).isPresent();
        assertThat(found.get().getUser().getId()).isEqualTo(user.getId());
        // Stored as STRING, so the row survives a reordering of the enum.
        assertThat(found.get().getProvider()).isEqualTo(OAuthProvider.GOOGLE);
    }

    @Test
    @DisplayName("the same provider cannot be linked to one user twice")
    void enforcesOneLinkPerProviderPerUser() {
        User user = users.save(User.fromSso("ayato", "ayato@mugen.dev"));
        oauthLinks.saveAndFlush(OAuthLink.link(user, OAuthProvider.GOOGLE, "google-sub-1"));

        assertThatThrownBy(() ->
                oauthLinks.saveAndFlush(OAuthLink.link(user, OAuthProvider.GOOGLE, "google-sub-2")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
