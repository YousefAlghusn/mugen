package com.mugen.auth.entity;

/**
 * External identity providers this service accepts.
 * <p>
 * An enum here — unlike {@link Role} — because these values never leave the
 * service. They are stored in {@code oauth_links.provider} and matched against a
 * path variable, both of which are closed sets under this service's control.
 */
public enum OAuthProvider {

    GOOGLE,
    GITHUB
}
