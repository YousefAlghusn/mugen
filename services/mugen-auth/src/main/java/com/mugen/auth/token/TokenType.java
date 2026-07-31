package com.mugen.auth.token;

/**
 * Value of the {@code type} claim, present on every token this service mints.
 * <p>
 * Without it the two token types are indistinguishable to a verifier: both are
 * RS256, signed by the same key, carrying the same issuer. A refresh token could
 * then be sent as a {@code Bearer} credential and would verify successfully —
 * authenticating the caller off a long-lived token that was only ever meant to be
 * exchanged at {@code /refresh}, and bypassing the 15-minute access window
 * entirely.
 */
public final class TokenType {

    public static final String CLAIM = "type";

    public static final String ACCESS = "access";
    public static final String REFRESH = "refresh";

    private TokenType() {
    }
}
