package com.mugen.auth.domain;

/**
 * Role names as stored in {@code user_roles.role} and carried in the access
 * token's {@code roles} claim.
 * <p>
 * Constants on a final class rather than an enum: these values travel to other
 * services inside a JWT and are compared as strings by the gateway, so the wire
 * format is the contract. An enum would invite {@code valueOf} on inbound tokens,
 * which throws on any role added by a newer auth deployment.
 */
public final class Role {

    public static final String USER = "ROLE_USER";
    public static final String MODERATOR = "ROLE_MODERATOR";
    public static final String ADMIN = "ROLE_ADMIN";

    private Role() {
    }
}
