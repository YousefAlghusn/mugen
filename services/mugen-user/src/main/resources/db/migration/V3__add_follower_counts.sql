-- Denormalised counts, maintained in the same transaction as the follows row.
-- COUNT(*) over follows is exact but is paid on every profile view, and a profile
-- with a million followers is exactly the one viewed most. Two writes per follow
-- instead: the row, and the two counters, all or nothing.

ALTER TABLE user_profiles
    ADD COLUMN follower_count  INT NOT NULL DEFAULT 0,
    ADD COLUMN following_count INT NOT NULL DEFAULT 0;
