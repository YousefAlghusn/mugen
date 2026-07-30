-- One row per active login. The refresh token carries {sessionId, version} and
-- nothing else; this table is the authority on whether that pair is still valid.
--
-- Rotation: every refresh increments token_version and issues a refresh token
-- carrying the new value. A refresh token presented with a version lower than
-- the stored one is therefore a token that has already been spent — which means
-- it was captured and replayed. The response to that is to revoke the entire
-- session, not just reject the request, because the attacker and the legitimate
-- user cannot be told apart at that point.
--
-- Storing an integer rather than a token hash is what makes this cheap: the
-- comparison is a single integer read, and no secret material is at rest here.

CREATE TABLE sessions
(
    id            UNIQUEIDENTIFIER NOT NULL,
    user_id       UNIQUEIDENTIFIER NOT NULL,

    token_version INT              NOT NULL CONSTRAINT df_sessions_token_version DEFAULT 0,

    -- Context shown in the "your active sessions" UI (tasks.md 2.7).
    user_agent    NVARCHAR(400)    NULL,
    ip_address    NVARCHAR(45)     NULL, -- 45 = longest IPv6 form incl. IPv4 mapping

    -- DATETIMEOFFSET because these map to Instant — see the note in V1.
    created_at    DATETIMEOFFSET(7) NOT NULL CONSTRAINT df_sessions_created_at DEFAULT CAST(SYSUTCDATETIME() AS DATETIMEOFFSET(7)),
    last_used_at  DATETIMEOFFSET(7) NOT NULL CONSTRAINT df_sessions_last_used_at DEFAULT CAST(SYSUTCDATETIME() AS DATETIMEOFFSET(7)),
    expires_at    DATETIMEOFFSET(7) NOT NULL,

    -- NULL = live. Set on logout, on explicit revoke, and on replay detection.
    -- Kept rather than deleted so the security event survives for auditing.
    revoked_at    DATETIMEOFFSET(7) NULL,

    CONSTRAINT pk_sessions PRIMARY KEY NONCLUSTERED (id),
    CONSTRAINT fk_sessions_user FOREIGN KEY (user_id)
        REFERENCES users (id) ON DELETE CASCADE
);

CREATE CLUSTERED INDEX ix_sessions_created_at ON sessions (created_at);

-- Listing a user's sessions is the hot query. Filtered to live sessions only,
-- since the revoked ones are audit history and are never listed.
CREATE NONCLUSTERED INDEX ix_sessions_user_active
    ON sessions (user_id, created_at DESC)
    WHERE revoked_at IS NULL;

-- Supports the expired-session sweep.
CREATE NONCLUSTERED INDEX ix_sessions_expires_at ON sessions (expires_at);
