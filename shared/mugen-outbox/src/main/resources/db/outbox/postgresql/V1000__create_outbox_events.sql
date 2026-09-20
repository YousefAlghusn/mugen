-- The transactional outbox for a Postgres service. Add
--   classpath:db/outbox/postgresql
-- to spring.flyway.locations and the table arrives with the module that owns the
-- entity mapped to it, so the two cannot drift. Version 1000 so it sorts after any
-- migration a service writes for itself.
--
-- Why an outbox at all, in one paragraph: sending to Kafka cannot be atomic with a
-- commit here — two systems, no shared transaction, and a crash in between loses
-- the event with no trace. Writing the event to THIS database is the only version
-- of "both or neither" that exists. A poller moves rows to Kafka afterwards and may
-- fail freely, because the row is durable. The price is at-least-once delivery, so
-- consumers deduplicate on the event id — DomainEvent.eventId().

CREATE TABLE outbox_events
(
    -- Also the eventId inside payload_json, so a duplicate seen by a consumer
    -- traces back to exactly one row here.
    id              UUID         NOT NULL PRIMARY KEY,

    topic           VARCHAR(200) NOT NULL,

    -- For operators reading this table. The wire format carries no type header.
    event_type      VARCHAR(100) NOT NULL,

    -- Kafka message key: same key, same partition, same order. Keyed by the
    -- aggregate the event is about.
    message_key     VARCHAR(100) NOT NULL,

    -- Serialized once and published byte-for-byte; never re-serialized on the way
    -- out. TEXT rather than JSONB so Hibernate maps it as the String it is; the
    -- constraint below still refuses anything that is not JSON, inside the writer's
    -- transaction, which takes the row it describes down with it.
    payload_json    TEXT         NOT NULL CONSTRAINT ck_outbox_events_payload_json CHECK (payload_json IS JSON),

    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),

    -- NULL = still owed to Kafka. Set once the broker acknowledges. Kept for a
    -- while rather than deleted, so delivery questions can be answered from here.
    published_at    TIMESTAMPTZ  NULL,

    -- Retry bookkeeping. Backoff is exponential: a broker that is down stays down
    -- for seconds at least, and a tight retry loop makes the outage worse.
    attempts        INT          NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_error      VARCHAR(1000) NULL
);

-- The poller's only read. Partial, so the index stays the size of the backlog
-- rather than the table: polling an outbox holding a million delivered events
-- costs what polling an empty one costs.
CREATE INDEX ix_outbox_events_pending
    ON outbox_events (next_attempt_at, created_at)
    WHERE published_at IS NULL;

-- Supports the retention sweep. Also partial, so it never indexes a pending row.
CREATE INDEX ix_outbox_events_published_at
    ON outbox_events (published_at)
    WHERE published_at IS NOT NULL;
