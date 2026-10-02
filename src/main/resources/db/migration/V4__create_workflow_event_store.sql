-- Orchestration module: event-sourced workflow runs (OR-10, OR-15).
--
-- workflow_event is the source of truth: append-only, one gap-free sequence per run.
-- workflow_run is a read model (run list, recovery query), updated in the same transaction
-- as every append, so it can never disagree with the log.
--
-- Production hardening (not expressible portably here): grant the application role only
-- INSERT/SELECT on workflow_event, so even a bug cannot UPDATE or DELETE audit history.

CREATE TABLE workflow_run (
    run_id            VARCHAR(36)              PRIMARY KEY,
    workflow          VARCHAR(100)             NOT NULL,
    workflow_version  INT                      NOT NULL,
    title             VARCHAR(500)             NOT NULL,
    initiator         VARCHAR(100)             NOT NULL,
    status            VARCHAR(20)              NOT NULL,
    started_at        TIMESTAMP WITH TIME ZONE NOT NULL,
    finished_at       TIMESTAMP WITH TIME ZONE,
    -- Optimistic concurrency: an append for seq N must find last_seq = N-1.
    last_seq          BIGINT                   NOT NULL
);

CREATE INDEX ix_workflow_run_status ON workflow_run (status);

CREATE TABLE workflow_event (
    run_id          VARCHAR(36)              NOT NULL,
    seq             BIGINT                   NOT NULL,
    event_type      VARCHAR(64)              NOT NULL,
    -- Version of the payload shape for this event type, so old events can be upcast later.
    schema_version  INT                      NOT NULL,
    occurred_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    payload         TEXT                     NOT NULL,   -- TEXT: valid on both H2 and PostgreSQL (CLOB is not)
    CONSTRAINT pk_workflow_event PRIMARY KEY (run_id, seq),
    CONSTRAINT fk_workflow_event_run FOREIGN KEY (run_id) REFERENCES workflow_run (run_id)
);
