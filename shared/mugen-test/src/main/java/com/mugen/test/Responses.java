package com.mugen.test;

import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.PathNotFoundException;
import jakarta.servlet.http.Cookie;
import org.springframework.test.web.servlet.MvcResult;

import java.io.UnsupportedEncodingException;

/**
 * Reading things back out of a {@code MockMvc} response, which is otherwise four lines
 * of checked exceptions and a mapper every time.
 * <p>
 * Deliberately small. Building a <em>request</em> is not here: a JSON text block reads
 * better than any builder, and the endpoints and payload shapes are the service's own
 * knowledge, not this library's.
 */
public final class Responses {

    private Responses() {
    }

    /** The raw body, with the checked exception unwrapped. */
    public static String body(MvcResult result) {
        try {
            return result.getResponse().getContentAsString();
        } catch (UnsupportedEncodingException ex) {
            throw new IllegalStateException("Response body was not readable as text", ex);
        }
    }

    /**
     * A value by JSON path — {@code "$.accessToken"} — or null when the body has no such
     * field. Null rather than an exception so a test that got an error document instead
     * asserts on that document, rather than failing here with an unrelated message.
     */
    public static <T> T at(MvcResult result, String jsonPath) {
        String body = body(result);
        if (body.isBlank()) {
            return null;
        }
        try {
            return JsonPath.read(body, jsonPath);
        } catch (PathNotFoundException absent) {
            return null;
        }
    }

    /**
     * {@link #at} fixed to the common case. Worth its own method because {@code at}'s
     * type variable is inferred from the assertion, and AssertJ has enough overloads
     * that the inference is sometimes ambiguous.
     */
    public static String string(MvcResult result, String jsonPath) {
        return at(result, jsonPath);
    }

    /** Null when the response set no such cookie. */
    public static Cookie cookie(MvcResult result, String name) {
        return result.getResponse().getCookie(name);
    }

    /** The value of a cookie, or null if it was not set — for the common case of not caring about attributes. */
    public static String cookieValue(MvcResult result, String name) {
        Cookie cookie = cookie(result, name);
        return cookie == null ? null : cookie.getValue();
    }
}
