-- Links a local user to an external identity provider account.
--
-- Modelled as its own table rather than columns on users because the relation
-- is one-to-many: the same person may sign in with Google today and link GitHub
-- later, and both must resolve to the same mugen user id.

CREATE TABLE oauth_links
(
    id               UNIQUEIDENTIFIER NOT NULL,
    user_id          UNIQUEIDENTIFIER NOT NULL,

    provider         NVARCHAR(20)     NOT NULL, -- GOOGLE | GITHUB

    -- The provider's own stable identifier for the account ("sub" for Google,
    -- the numeric id for GitHub). Never the email: emails get reassigned at some
    -- providers and are user-changeable at all of them, so keying on email would
    -- let one person inherit another's account.
    provider_user_id NVARCHAR(200)    NOT NULL,

    -- DATETIMEOFFSET because this maps to Instant — see the note in V1.
    created_at       DATETIMEOFFSET(7) NOT NULL CONSTRAINT df_oauth_links_created_at DEFAULT CAST(SYSUTCDATETIME() AS DATETIMEOFFSET(7)),

    CONSTRAINT pk_oauth_links PRIMARY KEY NONCLUSTERED (id),
    CONSTRAINT fk_oauth_links_user FOREIGN KEY (user_id)
        REFERENCES users (id) ON DELETE CASCADE
);

CREATE CLUSTERED INDEX ix_oauth_links_created_at ON oauth_links (created_at);

-- The callback lookup: "which user owns this provider account?"
CREATE UNIQUE NONCLUSTERED INDEX uq_oauth_links_provider_account
    ON oauth_links (provider, provider_user_id);

-- A user may link each provider at most once.
CREATE UNIQUE NONCLUSTERED INDEX uq_oauth_links_user_provider
    ON oauth_links (user_id, provider);
