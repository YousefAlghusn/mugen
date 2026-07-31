package com.mugen.auth.oauth;

import org.springframework.util.StringUtils;

import java.util.Locale;

/**
 * Derives a first-guess username from whatever a provider happened to share.
 * <p>
 * Only ever a suggestion: the value is not unique, and
 * {@link com.mugen.auth.service.OAuthService} is responsible for resolving
 * collisions before it reaches the database.
 */
public final class UsernameSuggestions {

    /** Matches {@code users.username} in V1 — a longer value would fail to insert. */
    private static final int MAX_LENGTH = 50;

    private static final String FALLBACK = "user";

    private UsernameSuggestions() {
    }

    /**
     * Prefers the email's local part, since it is the closest thing to a handle the
     * person already recognises, and falls back to a display name.
     */
    public static String fromEmailOrName(String email, String displayName) {
        if (StringUtils.hasText(email)) {
            int at = email.indexOf('@');
            String localPart = at > 0 ? email.substring(0, at) : email;
            String sanitised = sanitise(localPart);
            if (!sanitised.isEmpty()) {
                return sanitised;
            }
        }

        String sanitised = sanitise(displayName);
        return sanitised.isEmpty() ? FALLBACK : sanitised;
    }

    /**
     * Strips anything outside {@code [a-z0-9._-]}.
     * <p>
     * A provider's display name is attacker-controlled text — it can hold spaces,
     * emoji, right-to-left overrides or something shaped like another user's handle.
     * Reducing it to a known-safe alphabet here means nothing downstream has to
     * wonder what a username might contain.
     */
    private static String sanitise(String raw) {
        if (!StringUtils.hasText(raw)) {
            return "";
        }

        String cleaned = raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "");
        return cleaned.length() <= MAX_LENGTH ? cleaned : cleaned.substring(0, MAX_LENGTH);
    }

    /**
     * Appends a numeric suffix without exceeding the column width, for when the
     * suggestion is already taken.
     */
    public static String withSuffix(String base, int suffix) {
        String tail = String.valueOf(suffix);
        String head = base.length() + tail.length() <= MAX_LENGTH
                ? base
                : base.substring(0, MAX_LENGTH - tail.length());
        return head + tail;
    }
}
