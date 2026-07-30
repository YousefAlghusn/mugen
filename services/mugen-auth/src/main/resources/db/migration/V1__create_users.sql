-- Users owned by mugen-auth. This table holds credentials and identity only:
-- display names, bios and avatars live in mugen-user's Postgres database.
-- Services never share a database (see CLAUDE.md architecture rules), so the
-- link between the two is the mugen.user.registered event, not a foreign key.

CREATE TABLE users
(
    id            UNIQUEIDENTIFIER NOT NULL,
    username      NVARCHAR(50)     NOT NULL,
    email         NVARCHAR(320)    NOT NULL,

    -- Nullable on purpose: an account created through Google or GitHub SSO has
    -- no local password. A NULL here means "this account cannot log in with a
    -- password", which the login path must treat as invalid credentials rather
    -- than as a match against an empty hash.
    password_hash NVARCHAR(100)    NULL,

    enabled       BIT              NOT NULL CONSTRAINT df_users_enabled DEFAULT 1,

    -- DATETIMEOFFSET, not DATETIME2. These map to java.time.Instant, which is an
    -- absolute point in time; Hibernate 7 types it as TIMESTAMP_UTC and expects a
    -- column that carries an offset. DATETIME2 stores a wall-clock reading with no
    -- indication of which zone produced it, so it cannot represent an Instant
    -- unambiguously. The CAST in the default yields UTC (+00:00) rather than the
    -- server's local offset, which is what SYSDATETIMEOFFSET() would give.
    created_at    DATETIMEOFFSET(7) NOT NULL CONSTRAINT df_users_created_at DEFAULT CAST(SYSUTCDATETIME() AS DATETIMEOFFSET(7)),
    updated_at    DATETIMEOFFSET(7) NOT NULL CONSTRAINT df_users_updated_at DEFAULT CAST(SYSUTCDATETIME() AS DATETIMEOFFSET(7)),

    -- NONCLUSTERED deliberately. Application-generated UUIDs are random, and a
    -- random clustered key makes every insert land on an arbitrary page, causing
    -- page splits and fragmentation. The clustered index goes on created_at
    -- below, which is monotonic, so inserts append instead.
    CONSTRAINT pk_users PRIMARY KEY NONCLUSTERED (id)
);

CREATE CLUSTERED INDEX ix_users_created_at ON users (created_at);

CREATE UNIQUE NONCLUSTERED INDEX uq_users_username ON users (username);
CREATE UNIQUE NONCLUSTERED INDEX uq_users_email ON users (email);

-- Roles as a child table rather than a comma-joined column, so a role can be
-- indexed and queried. Small and fixed per user, mapped as an @ElementCollection.
CREATE TABLE user_roles
(
    user_id UNIQUEIDENTIFIER NOT NULL,
    role    NVARCHAR(50)     NOT NULL,

    CONSTRAINT pk_user_roles PRIMARY KEY (user_id, role),
    CONSTRAINT fk_user_roles_user FOREIGN KEY (user_id)
        REFERENCES users (id) ON DELETE CASCADE
);
