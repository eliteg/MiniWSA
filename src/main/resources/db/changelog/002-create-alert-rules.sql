--liquibase formatted sql

--changeset miniwsa:002-create-alert-rules
-- Alert rules (Bonus #1): "more than {threshold} events of {category} within {window_minutes}".
CREATE TABLE alert_rules (
    id             bigserial   PRIMARY KEY,
    category       text        NOT NULL,
    threshold      int         NOT NULL,
    window_minutes int         NOT NULL,
    created_at     timestamptz NOT NULL DEFAULT now()
);
--rollback DROP TABLE alert_rules;
