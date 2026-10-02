package com.agentic.orchestration.event;

import com.agentic.orchestration.model.RunStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Durable event store (OR-15) on plain JDBC. The orchestrator's persistence is a log, not an
 * object graph, so an ORM would add nothing but mapping overhead.
 *
 * <p>Each append is one transaction: optimistic check-and-bump of {@code workflow_run.last_seq},
 * then the event insert. A writer that is not continuing the sequence updates zero rows and is
 * rejected; the {@code (run_id, seq)} primary key is a second, independent guard.
 */
public class JdbcRunEventStore implements RunEventStore {

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final RunEventCodec codec;

    public JdbcRunEventStore(JdbcClient jdbc, TransactionTemplate tx, RunEventCodec codec) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.codec = codec;
    }

    @Override
    public void append(RunEvent event) {
        try {
            tx.executeWithoutResult(status -> {
                if (event instanceof RunEvent.RunStarted started) {
                    insertRun(started);
                }
                advanceSequence(event);
                jdbc.sql("""
                                INSERT INTO workflow_event (run_id, seq, event_type, schema_version, occurred_at, payload)
                                VALUES (:runId, :seq, :type, :version, :at, :payload)""")
                        .param("runId", event.runId())
                        .param("seq", event.seq())
                        .param("type", RunEventCodec.typeOf(event))
                        .param("version", RunEventCodec.SCHEMA_VERSION)
                        .param("at", utc(event.at()))
                        .param("payload", codec.encode(event))
                        .update();
            });
        } catch (DuplicateKeyException e) {
            throw new ConcurrentRunModificationException(event.runId(), event.seq());
        }
    }

    private void insertRun(RunEvent.RunStarted e) {
        jdbc.sql("""
                        INSERT INTO workflow_run (run_id, workflow, workflow_version, title, initiator, status, started_at, last_seq)
                        VALUES (:runId, :workflow, :version, :title, :initiator, :status, :at, 0)""")
                .param("runId", e.runId())
                .param("workflow", e.workflow())
                .param("version", e.workflowVersion())
                .param("title", truncate(e.requirement().title(), 500))
                .param("initiator", e.initiator())
                .param("status", RunStatus.RUNNING.name())
                .param("at", utc(e.at()))
                .update();
    }

    private void advanceSequence(RunEvent event) {
        // Two explicit statements rather than CASE WHEN on bind parameters: untyped NULL
        // parameters inside CASE are rejected by PostgreSQL.
        var update = event instanceof RunEvent.RunCompleted done
                ? jdbc.sql("""
                                UPDATE workflow_run SET last_seq = :seq, status = :status, finished_at = :at
                                 WHERE run_id = :runId AND last_seq = :previous""")
                        .param("status", done.status().name())
                        .param("at", utc(done.at()))
                : jdbc.sql("""
                                UPDATE workflow_run SET last_seq = :seq
                                 WHERE run_id = :runId AND last_seq = :previous""");
        int updated = update
                .param("seq", event.seq())
                .param("runId", event.runId())
                .param("previous", event.seq() - 1)
                .update();
        if (updated != 1) {
            throw new ConcurrentRunModificationException(event.runId(), event.seq());
        }
    }

    @Override
    public List<RunEvent> load(String runId) {
        return jdbc.sql("""
                        SELECT event_type, schema_version, payload FROM workflow_event
                         WHERE run_id = :runId ORDER BY seq""")
                .param("runId", runId)
                .query((rs, n) -> codec.decode(rs.getString("event_type"), rs.getInt("schema_version"), rs.getString("payload")))
                .list();
    }

    @Override
    public List<RunSummary> runs() {
        return jdbc.sql("SELECT * FROM workflow_run ORDER BY started_at DESC, run_id")
                .query(JdbcRunEventStore::summary)
                .list();
    }

    @Override
    public List<String> runIdsWithStatus(RunStatus status) {
        return jdbc.sql("SELECT run_id FROM workflow_run WHERE status = :status ORDER BY started_at")
                .param("status", status.name())
                .query(String.class)
                .list();
    }

    private static RunSummary summary(ResultSet rs, int rowNum) throws SQLException {
        OffsetDateTime finished = rs.getObject("finished_at", OffsetDateTime.class);
        return new RunSummary(
                rs.getString("run_id"),
                rs.getString("workflow"),
                rs.getInt("workflow_version"),
                rs.getString("title"),
                rs.getString("initiator"),
                RunStatus.valueOf(rs.getString("status")),
                rs.getObject("started_at", OffsetDateTime.class).toInstant(),
                finished == null ? null : finished.toInstant(),
                rs.getLong("last_seq"));
    }

    /** JDBC 4.2 guarantees OffsetDateTime support; Instant binding is driver-specific. */
    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
