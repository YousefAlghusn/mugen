package com.mugen.user.integration;

import com.mugen.shared.error.ErrorCode;
import com.mugen.test.IntegrationTest;
import com.mugen.user.support.Profiles;
import com.mugen.user.support.Tokens;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The first service on the shared chain, so this is where "a plain resource server
 * verifies mugen-auth's tokens and refuses everything else" is proved with a real
 * filter chain and a real decoder: both directions, and the document behind them.
 */
@IntegrationTest
class ResourceServerTest {

    @Autowired private MockMvc mvc;
    @Autowired private Profiles profiles;
    @Autowired private Tokens tokens;

    @Test
    void aPublicReadNeedsNoToken() throws Exception {
        Profiles.Person person = profiles.registered();

        mvc.perform(get("/api/v1/users/{id}", person.userId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value(person.username()));
    }

    @Test
    void aWriteWithoutATokenIsRefusedAsAProblemDocument() throws Exception {
        mvc.perform(patch("/api/v1/users/me").contentType(MediaType.APPLICATION_JSON).content("{\"displayName\":\"x\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
                .andExpect(jsonPath("$.code").value(ErrorCode.TOKEN_INVALID.name()))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    void aRefreshTokenIsNotABearerCredentialHereEither() throws Exception {
        mvc.perform(get("/api/v1/users/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.accessToken().asRefreshToken().value()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aTokenSignedByAStrangerIsRefused() throws Exception {
        mvc.perform(get("/api/v1/users/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.accessToken().signedByAStranger().value()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aGoodTokenIdentifiesTheCaller() throws Exception {
        Profiles.Person person = profiles.registered();

        mvc.perform(get("/api/v1/users/me").header(HttpHeaders.AUTHORIZATION, person.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(person.userId().toString()));
    }

    @Test
    void validationFailuresNameTheField() throws Exception {
        Profiles.Person person = profiles.registered();

        mvc.perform(patch("/api/v1/users/me")
                        .header(HttpHeaders.AUTHORIZATION, person.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_FAILED.name()))
                .andExpect(jsonPath("$.errors[0].field").value("displayName"));
    }

    /** Docs behind a 401 still look fine to a logged-in developer; this is the check that they are not. */
    @Test
    void theApiDocumentIsPublicAndCoversBothVersions() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/users/{userId}']").exists())
                .andExpect(jsonPath("$.paths['/api/v2/users/{userId}']").exists());
    }
}
