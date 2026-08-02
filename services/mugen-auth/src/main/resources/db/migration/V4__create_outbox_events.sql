-- The transactional outbox: Kafka messages recorded in the same transaction as the
-- database write they describe.
--
-- Sending to Kafka directly cannot be atomic with a commit here — two systems, no
-- shared transaction, and a crash in between loses the event with no trace. Redis
-- would not help; a second system is a second system. Writing the event to THIS
-- database is the only version of "both or neither" that exists.
--
-- A poller moves rows to Kafka afterwards, and that step may fail freely: the row
-- is durable, so a retry is always available. The price is at-least-once delivery —
-- a send that succeeds but fails to be marked published is re-sent — so consumers
-- deduplicate on the event id. That is what DomainEvent.eventId() is for.

CREATE TABLE outbox_events
(
    -- Also the eventId inside payload_json, so a duplicate seen by a consumer
    -- traces back to exactly one row here.
    id              UNIQUEIDENTIFIER  NOT NULL,

    topic           NVARCHAR(200)     NOT NULL,

    -- For operators reading this table. The wire format carries no type header —
    -- see UserEventPublisher.
    event_type      NVARCHAR(100)     NOT NULL,

    -- Kafka message key: same key, same partition, same order. Keyed by the
    -- aggregate the event is about (userId here).
    message_key     NVARCHAR(100)     NOT NULL,

    -- Serialized once and published byte-for-byte; never re-serialized on the way
    -- out. Named for its encoding because NVARCHAR(MAX) only says "text".
    payload_json    NVARCHAR(MAX)     NOT NULL,

    -- DATETIMEOFFSET, not DATETIME2 — same reasoning as V1: these map to Instant.
    created_at      DATETIMEOFFSET(7) NOT NULL CONSTRAINT df_outbox_events_created_at DEFAULT CAST(SYSUTCDATETIME() AS DATETIMEOFFSET(7)),

    -- NULL = still owed to Kafka. Set once the broker acknowledges. Kept for a
    -- while rather than deleted, so delivery questions can be answered from here.
    published_at    DATETIMEOFFSET(7) NULL,

    -- Retry bookkeeping. Backoff is exponential: a broker that is down stays down
    -- for seconds at least, and a tight retry loop makes the outage worse.
    attempts        INT               NOT NULL CONSTRAINT df_outbox_events_attempts DEFAULT 0,
    next_attempt_at DATETIMEOFFSET(7) NOT NULL CONSTRAINT df_outbox_events_next_attempt_at DEFAULT CAST(SYSUTCDATETIME() AS DATETIMEOFFSET(7)),
    last_error      NVARCHAR(1000)    NULL,

    -- NONCLUSTERED for the same reason as users and sessions: a random UUID key
    -- would scatter every insert across the index.
    CONSTRAINT pk_outbox_events PRIMARY KEY NONCLUSTERED (id),

    -- Decides where a bad payload fails. Without it, an empty or truncated string
    -- commits and breaks later — in the poller, or in another service's consumer
    -- with no way back to the cause. With it the insert fails inside the caller's
    -- transaction and takes the users row down with it.
    --
    -- Syntax only; a well-formed payload with the wrong fields still passes. The
    -- one-argument form runs on SQL Server 2016+. The 2022 form
    -- ISJSON(payload_json, JSON_OBJECT) would also pin the payload to an object,
    -- but pins the migration to 2022 for little in return.
    CONSTRAINT ck_outbox_events_payload_json CHECK (ISJSON(payload_json) = 1)
);

-- Inserts are append-only in time order, so this clusters without page splits.
CREATE CLUSTERED INDEX ix_outbox_events_created_at ON outbox_events (created_at);

-- The poller's only read. Filtered, so the index stays the size of the backlog
-- rather than the table: polling an outbox holding a million delivered events
-- costs what polling an empty one costs.
CREATE NONCLUSTERED INDEX ix_outbox_events_pending
    ON outbox_events (next_attempt_at, created_at)
    WHERE published_at IS NULL;

-- Supports the retention sweep. Also filtered, so it never indexes a pending row.
CREATE NONCLUSTERED INDEX ix_outbox_events_published_at
    ON outbox_events (published_at)
    WHERE published_at IS NOT NULL;