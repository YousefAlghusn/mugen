-- Profiles owned by mugen-user. The id is the account id mugen-auth assigned: the
-- two services never share a database, so the link is the mugen.user.registered
-- event that creates this row, not a foreign key. Credentials never come here.

CREATE TABLE user_profiles
(
    id           UUID         NOT NULL PRIMARY KEY,

    -- Copied from the registration event. mugen-auth owns uniqueness; this index
    -- only keeps a replayed event from creating a second profile under the name.
    username     VARCHAR(50)  NOT NULL,
    display_name VARCHAR(80)  NOT NULL,
    bio          VARCHAR(500) NULL,

    -- The object key in the avatars bucket, never a URL: URLs are presigned per
    -- request and expire. NULL until the first confirmed upload.
    avatar_key   VARCHAR(200) NULL,

    -- TIMESTAMPTZ: these map to java.time.Instant, an absolute point in time, and
    -- a plain TIMESTAMP is a wall-clock reading with no zone.
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uq_user_profiles_username ON user_profiles (username);
