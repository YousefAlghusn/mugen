package com.mugen.auth.dto;

/**
 * Where a login came from, recorded on the session so a user can recognise their
 * own devices in the session list — and spot one they do not recognise.
 */
public record RequestContext(String userAgent, String ipAddress) {

    public static RequestContext unknown() {
        return new RequestContext(null, null);
    }
}
