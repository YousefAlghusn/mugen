package com.mugen.auth.entity;

/**
 * External identity providers this service accepts.
 * <p>
 * An enum here — unlike {@link Role} — because these values never leave the
 * service. They are stored in {@code oauth_links.provider} and matched against a
 * path variable, both of which are closed sets under this service's control.
 */
public enum OAuthProvider {

    /**
     * One value, and a flow is not one of them: the device grant (tasks.md 2.12)
     * authenticates the same Google account and returns the same {@code sub}, so it
     * links here rather than adding a sibling.
     */
    GOOGLE
}
