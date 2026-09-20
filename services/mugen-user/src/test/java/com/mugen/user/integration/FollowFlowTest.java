package com.mugen.user.integration;

import com.mugen.outbox.OutboxEventRepository;
import com.mugen.shared.error.ErrorCode;
import com.mugen.test.IntegrationTest;
import com.mugen.test.Responses;
import com.mugen.user.service.FollowEventPublisher;
import com.mugen.user.support.Profiles;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A follow is a row, two counters and an event, all or nothing; and the lists behind
 * it page by keyset. Everything here needs the real database: the duplicate is decided
 * by the primary key, the counters by SQL, the pages by an index range.
 */
@IntegrationTest
class FollowFlowTest {

    @Autowired private MockMvc mvc;
    @Autowired private Profiles profiles;
    @Autowired private OutboxEventRepository outbox;

    @Test
    void followingSomeoneCountsOnBothSidesAndQueuesTheEvent() throws Exception {
        Profiles.Person follower = profiles.registered();
        Profiles.Person followee = profiles.registered();

        mvc.perform(post("/api/v1/users/{id}/follow", followee.userId()).header(HttpHeaders.AUTHORIZATION, follower.bearer()))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/v2/users/{id}", followee.userId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.followerCount").value(1))
                .andExpect(jsonPath("$.followingCount").value(0));
        mvc.perform(get("/api/v2/users/{id}", follower.userId()))
                .andExpect(jsonPath("$.followerCount").value(0))
                .andExpect(jsonPath("$.followingCount").value(1));

        assertThat(outbox.findAll())
                .filteredOn(event -> event.getTopic().equals(FollowEventPublisher.USER_FOLLOWED_TOPIC))
                .anySatisfy(event -> {
                    assertThat(event.getMessageKey()).isEqualTo(followee.userId().toString());
                    assertThat(event.getPayloadJson()).contains(follower.userId().toString());
                });
    }

    @Test
    void followingTwiceIsAConflictAndCountsOnce() throws Exception {
        Profiles.Person follower = profiles.registered();
        Profiles.Person followee = profiles.registered();
        mvc.perform(post("/api/v1/users/{id}/follow", followee.userId()).header(HttpHeaders.AUTHORIZATION, follower.bearer()));

        mvc.perform(post("/api/v1/users/{id}/follow", followee.userId()).header(HttpHeaders.AUTHORIZATION, follower.bearer()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(ErrorCode.ALREADY_FOLLOWING.name()));

        mvc.perform(get("/api/v2/users/{id}", followee.userId()))
                .andExpect(jsonPath("$.followerCount").value(1));
    }

    @Test
    void followingYourselfIsRefused() throws Exception {
        Profiles.Person person = profiles.registered();

        mvc.perform(post("/api/v1/users/{id}/follow", person.userId()).header(HttpHeaders.AUTHORIZATION, person.bearer()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value(ErrorCode.BUSINESS_RULE_VIOLATION.name()));
    }

    /** A valid token for an account whose profile has not arrived is a 404, not a foreign-key 409. */
    @Test
    void aCallerWithoutAProfileYetGetsNotFoundNotConflict() throws Exception {
        Profiles.Person early = profiles.unregistered();
        Profiles.Person followee = profiles.registered();

        mvc.perform(post("/api/v1/users/{id}/follow", followee.userId()).header(HttpHeaders.AUTHORIZATION, early.bearer()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ErrorCode.USER_NOT_FOUND.name()));
    }

    @Test
    void unfollowingReversesTheCountsAndASecondTimeIsNotFound() throws Exception {
        Profiles.Person follower = profiles.registered();
        Profiles.Person followee = profiles.registered();
        mvc.perform(post("/api/v1/users/{id}/follow", followee.userId()).header(HttpHeaders.AUTHORIZATION, follower.bearer()));

        mvc.perform(delete("/api/v1/users/{id}/follow", followee.userId()).header(HttpHeaders.AUTHORIZATION, follower.bearer()))
                .andExpect(status().isNoContent());
        mvc.perform(delete("/api/v1/users/{id}/follow", followee.userId()).header(HttpHeaders.AUTHORIZATION, follower.bearer()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ErrorCode.FOLLOW_NOT_FOUND.name()));

        mvc.perform(get("/api/v2/users/{id}", followee.userId()))
                .andExpect(jsonPath("$.followerCount").value(0));
    }

    /**
     * The invariant a keyset cursor exists for: walking every page yields each follower
     * exactly once, newest first, however the pages are cut.
     */
    @Test
    void followersPageWithoutGapsOrRepeats() throws Exception {
        Profiles.Person popular = profiles.registered();
        List<UUID> followers = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            Profiles.Person fan = profiles.registered();
            mvc.perform(post("/api/v1/users/{id}/follow", popular.userId()).header(HttpHeaders.AUTHORIZATION, fan.bearer()))
                    .andExpect(status().isNoContent());
            followers.add(fan.userId());
        }

        List<String> seen = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            MvcResult page = mvc.perform(get("/api/v1/users/{id}/followers", popular.userId())
                            .param("limit", "2")
                            .param("cursor", cursor == null ? "" : cursor))
                    .andExpect(status().isOk())
                    .andReturn();
            List<String> ids = Responses.at(page, "$.items[*].userId");
            seen.addAll(ids);
            cursor = Responses.at(page, "$.nextCursor");
            pages++;
        } while (cursor != null);

        assertThat(pages).isEqualTo(3);
        assertThat(seen).doesNotHaveDuplicates().hasSize(5);
        // Newest first: the last one to follow is the first listed.
        assertThat(seen.getFirst()).isEqualTo(followers.getLast().toString());
    }

    @Test
    void aCursorNobodyIssuedIsRefused() throws Exception {
        Profiles.Person person = profiles.registered();

        mvc.perform(get("/api/v1/users/{id}/followers", person.userId()).param("cursor", "bm90LWEtY3Vyc29y"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value(ErrorCode.BUSINESS_RULE_VIOLATION.name()));
    }

    @Test
    void followingListShowsWhoTheyFollow() throws Exception {
        Profiles.Person follower = profiles.registered();
        Profiles.Person followee = profiles.registered();
        mvc.perform(post("/api/v1/users/{id}/follow", followee.userId()).header(HttpHeaders.AUTHORIZATION, follower.bearer()));

        mvc.perform(get("/api/v1/users/{id}/following", follower.userId()).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].userId").value(followee.userId().toString()))
                .andExpect(jsonPath("$.items[0].username").value(followee.username()))
                .andExpect(jsonPath("$.nextCursor").doesNotExist());
    }
}
