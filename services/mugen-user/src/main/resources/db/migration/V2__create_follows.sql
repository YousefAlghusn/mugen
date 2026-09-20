-- One row per follow. The primary key is the pair, so following twice is a
-- constraint violation the service turns into a 409 rather than a second row.

CREATE TABLE follows
(
    follower_id UUID        NOT NULL,
    followee_id UUID        NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT pk_follows PRIMARY KEY (follower_id, followee_id),
    CONSTRAINT fk_follows_follower FOREIGN KEY (follower_id) REFERENCES user_profiles (id) ON DELETE CASCADE,
    CONSTRAINT fk_follows_followee FOREIGN KEY (followee_id) REFERENCES user_profiles (id) ON DELETE CASCADE,
    -- A self-follow is refused by the service first; the check is for a row that
    -- arrives by any other route.
    CONSTRAINT ck_follows_not_self CHECK (follower_id <> followee_id)
);

-- The two lists, each paginated by (created_at, id) descending. The primary key
-- serves "who does X follow"; this serves "who follows X". Both carry created_at so
-- the cursor query is an index range scan and never a sort.
CREATE INDEX ix_follows_followee_created ON follows (followee_id, created_at DESC, follower_id DESC);
CREATE INDEX ix_follows_follower_created ON follows (follower_id, created_at DESC, followee_id DESC);
