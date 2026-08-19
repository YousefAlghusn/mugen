package com.mugen.auth.config;

/**
 * Names this service's API document uses, referenced by the endpoints that carry them.
 * <p>
 * Separate from {@link OpenApiConfig} so a controller depends on the vocabulary rather
 * than on the {@code @Configuration} class that happens to build the document.
 */
public final class AuthApiDocs {

    /**
     * The refresh token. Documented as a cookie scheme rather than a parameter because
     * that is what it is — it never appears in a body or a header, and {@code /refresh}
     * and {@code /logout} take no visible input at all without it.
     * <p>
     * Swagger UI cannot drive this one: the cookie is {@code HttpOnly}, so script cannot
     * set it and browsers refuse {@code Cookie} as a fetch header. Declared so the two
     * endpoints do not read as though they need nothing — exercising them means letting
     * the browser replay the cookie a prior {@code /login} set.
     */
    public static final String REFRESH_COOKIE_SCHEME = "refreshCookie";

    private AuthApiDocs() {
    }
}
