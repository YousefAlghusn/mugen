package com.mugen.user.integration;

import com.mugen.shared.error.ErrorCode;
import com.mugen.test.IntegrationTest;
import com.mugen.test.Responses;
import com.mugen.user.config.AvatarProperties;
import com.mugen.user.storage.MinioClientWrapper;
import com.mugen.user.support.Profiles;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The upload goes straight to MinIO on a URL this service signed, so the flow can only
 * be seen against a real MinIO: that the signature lets exactly that PUT through, that
 * confirm checks the object is there, and that the profile then serves a readable URL.
 */
@IntegrationTest
class AvatarFlowTest {

    private static final byte[] PNG_HEADER = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};

    @Autowired private MockMvc mvc;
    @Autowired private Profiles profiles;
    @Autowired private MinioClientWrapper storage;
    @Autowired private AvatarProperties avatarProperties;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void uploadConfirmAndReadBack() throws Exception {
        Profiles.Person person = profiles.registered();

        MvcResult issued = mvc.perform(post("/api/v1/users/me/avatar")
                        .header(HttpHeaders.AUTHORIZATION, person.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"contentType\":\"image/png\"}"))
                .andExpect(status().isOk())
                .andReturn();
        String uploadUrl = Responses.string(issued, "$.uploadUrl");
        String objectKey = Responses.string(issued, "$.objectKey");

        assertThat(put(uploadUrl, "image/png")).isEqualTo(200);

        mvc.perform(post("/api/v1/users/me/avatar/confirm")
                        .header(HttpHeaders.AUTHORIZATION, person.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"objectKey\":\"" + objectKey + "\"}"))
                .andExpect(status().isNoContent());

        MvcResult profile = mvc.perform(get("/api/v1/users/{id}", person.userId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.avatarUrl").isNotEmpty())
                .andReturn();
        HttpResponse<byte[]> image = http.send(
                HttpRequest.newBuilder(URI.create(Responses.string(profile, "$.avatarUrl"))).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertThat(image.statusCode()).isEqualTo(200);
        assertThat(image.body()).startsWith(PNG_HEADER);
    }

    /** The signature is for one content type; a URL issued for a PNG does not store anything else. */
    @Test
    void anUploadUrlIsBoundToItsContentType() throws Exception {
        Profiles.Person person = profiles.registered();
        MvcResult issued = mvc.perform(post("/api/v1/users/me/avatar")
                        .header(HttpHeaders.AUTHORIZATION, person.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"contentType\":\"image/png\"}"))
                .andReturn();

        assertThat(put(Responses.string(issued, "$.uploadUrl"), "application/x-msdownload")).isEqualTo(403);
    }

    @Test
    void confirmingWithoutUploadingIsRefused() throws Exception {
        Profiles.Person person = profiles.registered();
        MvcResult issued = mvc.perform(post("/api/v1/users/me/avatar")
                        .header(HttpHeaders.AUTHORIZATION, person.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"contentType\":\"image/png\"}"))
                .andReturn();

        mvc.perform(post("/api/v1/users/me/avatar/confirm")
                        .header(HttpHeaders.AUTHORIZATION, person.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"objectKey\":\"" + Responses.string(issued, "$.objectKey") + "\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value(ErrorCode.UPLOAD_NOT_FOUND.name()));
    }

    /** A key under somebody else's prefix is refused before storage is even asked. */
    @Test
    void confirmingSomeoneElsesKeyIsForbidden() throws Exception {
        Profiles.Person owner = profiles.registered();
        Profiles.Person intruder = profiles.registered();
        MvcResult issued = mvc.perform(post("/api/v1/users/me/avatar")
                        .header(HttpHeaders.AUTHORIZATION, owner.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"contentType\":\"image/png\"}"))
                .andReturn();
        String ownersKey = Responses.string(issued, "$.objectKey");
        put(Responses.string(issued, "$.uploadUrl"), "image/png");

        mvc.perform(post("/api/v1/users/me/avatar/confirm")
                        .header(HttpHeaders.AUTHORIZATION, intruder.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"objectKey\":\"" + ownersKey + "\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ErrorCode.FORBIDDEN.name()));
    }

    @Test
    void replacingAnAvatarDeletesTheOldObject() throws Exception {
        Profiles.Person person = profiles.registered();
        String first = uploadAndConfirm(person);

        uploadAndConfirm(person);

        assertThat(storage.objectExists(avatarProperties.bucket(), first)).isFalse();
    }

    private String uploadAndConfirm(Profiles.Person person) throws Exception {
        MvcResult issued = mvc.perform(post("/api/v1/users/me/avatar")
                        .header(HttpHeaders.AUTHORIZATION, person.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"contentType\":\"image/png\"}"))
                .andReturn();
        String objectKey = Responses.string(issued, "$.objectKey");
        put(Responses.string(issued, "$.uploadUrl"), "image/png");
        mvc.perform(post("/api/v1/users/me/avatar/confirm")
                        .header(HttpHeaders.AUTHORIZATION, person.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"objectKey\":\"" + objectKey + "\"}"))
                .andExpect(status().isNoContent());
        return objectKey;
    }

    /** The browser's half of the flow: a plain PUT to the presigned URL, bypassing this service entirely. */
    private int put(String presignedUrl, String contentType) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(presignedUrl))
                .header("Content-Type", contentType)
                .PUT(HttpRequest.BodyPublishers.ofByteArray(PNG_HEADER))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
    }
}
