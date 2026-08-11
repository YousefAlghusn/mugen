package com.mugen.test.unit;

import com.mugen.test.Responses;
import com.mugen.test.UnitTest;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

@UnitTest
class ResponsesTest {

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new Endpoint()).build();

    @RestController
    static class Endpoint {

        @GetMapping(value = "/token", produces = MediaType.APPLICATION_JSON_VALUE)
        String token(jakarta.servlet.http.HttpServletResponse response) {
            response.addCookie(new Cookie("mugen_refresh", "the-refresh-token"));
            return """
                    {"accessToken":"the-access-token","roles":["ROLE_USER"]}
                    """;
        }

        @GetMapping("/empty")
        void empty() {
        }
    }

    private MvcResult token() throws Exception {
        return mvc.perform(get("/token")).andReturn();
    }

    @Test
    @DisplayName("reads a value by json path")
    void readsAField() throws Exception {
        assertThat(Responses.string(token(), "$.accessToken")).isEqualTo("the-access-token");
        assertThat(Responses.<java.util.List<String>>at(token(), "$.roles")).containsExactly("ROLE_USER");
    }

    /**
     * Null rather than an exception, so a test whose request failed asserts on the error
     * document it actually got instead of dying here with an unrelated message.
     */
    @Test
    @DisplayName("an absent field is null, not an exception")
    void absentFieldIsNull() throws Exception {
        assertThat(Responses.string(token(), "$.refreshToken")).isNull();
        assertThat(Responses.string(mvc.perform(get("/empty")).andReturn(), "$.anything")).isNull();
    }

    @Test
    @DisplayName("reads a cookie, and its absence")
    void readsCookies() throws Exception {
        assertThat(Responses.cookieValue(token(), "mugen_refresh")).isEqualTo("the-refresh-token");
        assertThat(Responses.cookie(token(), "not_set")).isNull();
    }
}
