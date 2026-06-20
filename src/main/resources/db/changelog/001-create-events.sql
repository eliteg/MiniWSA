--liquibase formatted sql

--changeset miniwsa:001-create-events
CREATE TABLE events (
    event_id      text        PRIMARY KEY,
    timestamp     timestamptz NOT NULL,
    config_id     bigint      NOT NULL,
    client_ip     inet        NOT NULL,
    path          text        NOT NULL,
    category      text        NOT NULL,
    severity      text        NOT NULL,
    action        text        NOT NULL,
    attack_type   text        NOT NULL,
    threat_score  smallint    NOT NULL CHECK (threat_score BETWEEN 0 AND 100),
    received_at   timestamptz NOT NULL,
    policy_id     text,
    hostname      text,
    method        text,
    status_code   int,
    user_agent    text,
    request_size  int,
    response_size int,
    geo_country   text,
    geo_city      text,
    rule_id       text,
    rule_name     text,
    rule_message  text
);

CREATE INDEX idx_events_config_timestamp   ON events (config_id, timestamp);
CREATE INDEX idx_events_category_timestamp ON events (category, timestamp);
CREATE INDEX idx_events_timestamp_desc     ON events (timestamp DESC);
--rollback DROP TABLE events;
