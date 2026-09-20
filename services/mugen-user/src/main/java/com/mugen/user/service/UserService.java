package com.mugen.user.service;

import com.mugen.user.entity.UserProfile;
import com.mugen.user.exception.UserExceptions;
import com.mugen.user.repository.UserProfileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** Profiles: created from the registration event, read by anyone, edited by their owner. */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

    private final UserProfileRepository profiles;

    @Transactional(readOnly = true)
    public UserProfile getProfile(UUID userId) {
        return profiles.findById(userId).orElseThrow(() -> new UserExceptions.ProfileNotFound(userId));
    }

    @Transactional(readOnly = true)
    public UserProfile getProfile(String username) {
        return profiles.findByUsername(username).orElseThrow(() -> new UserExceptions.ProfileNotFound(username));
    }

    /** The caller edits only their own profile — the id comes from the token, never the request. */
    @Transactional
    public UserProfile updateProfile(UUID userId, String displayName, String bio) {
        UserProfile profile = getProfile(userId);
        profile.update(displayName.strip(), bio == null || bio.isBlank() ? null : bio.strip());
        log.info("Profile updated userId={}", userId);
        return profile;
    }

    /**
     * Creates the profile a registration event announces. Idempotent under at-least-once
     * delivery: the primary key is the user id, so a replayed event finds the row. Two
     * consumers racing both pass the check and one loses to the insert — that one's
     * {@code DataIntegrityViolationException} escapes here, rolling this transaction
     * back, and the consumer treats it as the duplicate it is. Not caught here: after
     * a failed flush the persistence context cannot be committed, only abandoned.
     *
     * @return whether this call created it
     */
    @Transactional
    public boolean createFromRegistration(UUID userId, String username) {
        if (profiles.existsById(userId)) {
            return false;
        }
        profiles.saveAndFlush(UserProfile.fromRegistration(userId, username));
        log.info("Profile created userId={}", userId);
        return true;
    }
}
