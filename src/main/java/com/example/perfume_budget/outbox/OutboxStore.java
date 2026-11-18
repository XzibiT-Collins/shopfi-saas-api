package com.example.perfume_budget.outbox;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.hibernate.query.NativeQuery;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * EVT-01b (L2): the tenant's {@code side_effect_outbox} table (V13), through the current tenant's session.
 *
 * <p>Life of a row: {@link #record} writes it in the publishing transaction (PENDING, attempts 0, first due after
 * {@link #FIRST_HAND_OFF_GRACE} so the immediate hand-off normally wins). A worker {@link #claimFresh claims} it
 * (attempts 0 -> 1) or, for a row the drainer {@link #reserveDue reserved}, {@link #claim claims} it with the
 * attempts it was reserved at; a claim pushes the next attempt out by {@link #LEASE} (a worker that dies mid-way
 * leaves the row due again after the lease). Then {@link #complete} (DONE) or {@link #fail} (PENDING with
 * exponential back-off, or FAILED after {@link #MAX_ATTEMPTS} or for an error that is not retryable). Claims are
 * single UPDATEs, so two workers never both own a row. EVT-01c: every write after a claim or reservation (release,
 * complete, fail) is fenced on the {@code attempts} that claim / reservation saw, so a stale worker or drain never
 * changes a row somebody claimed since. Every write after the publishing transaction is its own short transaction (REQUIRES_NEW): no
 * connection is held while a mail is sent.
 */
@Component
public class OutboxStore {

    public static final Duration FIRST_HAND_OFF_GRACE = Duration.ofMinutes(2);
    public static final Duration LEASE = Duration.ofMinutes(10);
    public static final Duration FIRST_BACKOFF = Duration.ofMinutes(1);
    public static final Duration MAX_BACKOFF = Duration.ofHours(1);
    public static final int MAX_ATTEMPTS = 8;
    static final String TABLE = "side_effect_outbox";

    private EntityManager em;

    @PersistenceContext
    public void setEntityManager(EntityManager em) {
        this.em = em;
    }

    /** A row a worker owns (claimed) or the drainer reserved; {@code attempts} as of that moment. */
    public record Row(long id, OutboxKind kind, String reference, int attempts) {
        public String describe() {
            return kind.describe(reference) + " (outbox " + id + ")";
        }
    }

    /**
     * Queues {@code kind} for {@code reference} in the CURRENT transaction (the publishing one), so the row commits or
     * rolls back with the payment / order. Idempotent: a second record of the same (kind, reference) is a no-op.
     * Takes no lock on any existing row; does not flush the session.
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public void record(OutboxKind kind, Object reference) {
        native_("INSERT INTO " + TABLE + " (kind, reference, next_attempt_at) VALUES (:kind, :ref, now() + "
                + seconds(FIRST_HAND_OFF_GRACE) + ") ON CONFLICT ON CONSTRAINT uk_side_effect_outbox_kind_reference DO NOTHING")
                .setParameter("kind", kind.name())
                .setParameter("ref", String.valueOf(reference))
                .executeUpdate();
    }

    /** The immediate hand-off's claim: only a row nobody has attempted yet. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<Row> claimFresh(OutboxKind kind, String reference) {
        return single(native_("UPDATE " + TABLE + " SET attempts = attempts + 1, next_attempt_at = now() + " + seconds(LEASE)
                + " WHERE kind = :kind AND reference = :ref AND status = 'PENDING' AND attempts = 0"
                + " RETURNING id, kind, reference, attempts")
                .setParameter("kind", kind.name()).setParameter("ref", reference));
    }

    /** Claims a row the drainer reserved, only if nobody attempted it since ({@code attempts} unchanged). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<Row> claim(Row reserved) {
        return single(native_("UPDATE " + TABLE + " SET attempts = attempts + 1, next_attempt_at = now() + " + seconds(LEASE)
                + " WHERE id = :id AND status = 'PENDING' AND attempts = :attempts RETURNING id, kind, reference, attempts")
                .setParameter("id", reserved.id()).setParameter("attempts", reserved.attempts()));
    }

    /**
     * Reserves up to {@code limit} due rows (oldest first) for one drain: their next attempt moves out by the lease so
     * the next drain does not hand them off again while they wait in the executor. Rows another drain is reserving
     * right now are skipped.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<Row> reserveDue(int limit) {
        return rows(native_("UPDATE " + TABLE + " SET next_attempt_at = now() + " + seconds(LEASE) + " WHERE id IN (SELECT id FROM "
                + TABLE + " WHERE status = 'PENDING' AND next_attempt_at <= now() ORDER BY next_attempt_at, id LIMIT :limit"
                + " FOR UPDATE SKIP LOCKED) RETURNING id, kind, reference, attempts")
                .setParameter("limit", limit).getResultList());
    }

    /**
     * Makes reserved rows due again at once (their hand-off was refused: executor full). EVT-01c (O1): fenced on the
     * attempts each row was reserved at, like {@link #claim}: a row somebody claimed since (a late immediate hand-off,
     * or a newer drain after this reservation's lease ran out) is being worked on, and making it due again would let
     * the next drain claim it and send it a second time while that worker is still sending. One statement for all.
     *
     * @return how many rows were made due again
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int release(Collection<Row> reserved) {
        if (reserved.isEmpty()) {
            return 0;
        }
        StringBuilder pairs = new StringBuilder();
        for (int i = 0; i < reserved.size(); i++) {
            pairs.append(i == 0 ? "" : ", ").append("(:id").append(i).append(", :at").append(i).append(')');
        }
        NativeQuery<?> q = native_("UPDATE " + TABLE + " SET next_attempt_at = now() WHERE status = 'PENDING'"
                + " AND (id, attempts) IN (" + pairs + ")");
        int i = 0;
        for (Row r : reserved) {
            q.setParameter("id" + i, r.id()).setParameter("at" + i, r.attempts());
            i++;
        }
        return q.executeUpdate();
    }

    /**
     * Marks the row DONE. EVT-01c (O2): only while it is still the claim this worker holds ({@code attempts} as
     * claimed, PENDING): a worker whose lease ran out and whose row somebody re-claimed must not settle the other
     * worker's attempt.
     *
     * @return false if the row was re-claimed (or settled) by somebody else meanwhile; nothing changed
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean complete(Row claimed) {
        return native_("UPDATE " + TABLE + " SET status = 'DONE', completed_at = now(), last_error = NULL"
                + " WHERE id = :id AND status = 'PENDING' AND attempts = :attempts")
                .setParameter("id", claimed.id()).setParameter("attempts", claimed.attempts()).executeUpdate() == 1;
    }

    /** What {@link #fail} did with the row. */
    public enum FailOutcome {
        /** PENDING again, due after the back-off. */
        RETRY,
        /** FAILED for good (attempts used up, or the error is not retryable): kept for an operator. */
        FAILED,
        /** Nothing: somebody re-claimed (or settled) the row since this worker claimed it (EVT-01c O2). */
        STALE
    }

    /**
     * Records a failed attempt: due again after {@code FIRST_BACKOFF * 2^(attempts-1)} (capped at
     * {@link #MAX_BACKOFF}), or FAILED (poison, kept for an operator) once {@link #MAX_ATTEMPTS} attempts failed, or at
     * once when {@code retryable} is false (EVT-01c: a bug or bad data fails the same way on every attempt). Fenced
     * like {@link #complete}: only the claim this worker still holds; a row already DONE / FAILED is never put back.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public FailOutcome fail(Row claimed, String error, boolean retryable) {
        List<?> status = native_("UPDATE " + TABLE + " SET status = CASE WHEN attempts >= :max OR NOT :retryable"
                + " THEN 'FAILED' ELSE 'PENDING' END,"
                + " next_attempt_at = now() + " + seconds(backoff(claimed.attempts())) + ", last_error = :error"
                + " WHERE id = :id AND status = 'PENDING' AND attempts = :attempts RETURNING status")
                .setParameter("max", MAX_ATTEMPTS).setParameter("retryable", retryable)
                .setParameter("error", truncate(error)).setParameter("id", claimed.id())
                .setParameter("attempts", claimed.attempts())
                .getResultList();
        if (status.size() != 1) {
            return FailOutcome.STALE;
        }
        return "FAILED".equals(status.getFirst()) ? FailOutcome.FAILED : FailOutcome.RETRY;
    }

    /** Deletes DONE rows completed more than {@code retention} ago (FAILED rows stay for an operator). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int purgeDone(Duration retention) {
        return native_("DELETE FROM " + TABLE + " WHERE status = 'DONE' AND completed_at < now() - " + seconds(retention))
                .executeUpdate();
    }

    static Duration backoff(int attempts) {
        Duration d = FIRST_BACKOFF.multipliedBy(1L << Math.min(Math.max(attempts - 1, 0), 20));
        return d.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : d;
    }

    private static String seconds(Duration d) {
        return "interval '" + d.toSeconds() + " seconds'"; // a constant, never user input
    }

    private static String truncate(String s) {
        return s == null || s.length() <= 200 ? s : s.substring(0, 200);
    }

    @SuppressWarnings("rawtypes")
    private NativeQuery native_(String sql) {
        // only this table: a native statement must not flush the publishing transaction's pending entity changes
        // early (the flush order, and so the API-05 lock order, stays exactly what the commit would do)
        return em.createNativeQuery(sql).unwrap(NativeQuery.class).addSynchronizedQuerySpace(TABLE);
    }

    @SuppressWarnings("rawtypes")
    private static Optional<Row> single(NativeQuery q) {
        List<Row> rows = rows(q.getResultList());
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    private static List<Row> rows(List<?> result) {
        List<Row> out = new ArrayList<>(result.size());
        for (Object o : result) {
            Object[] r = (Object[]) o;
            out.add(new Row(((Number) r[0]).longValue(), OutboxKind.valueOf((String) r[1]), (String) r[2],
                    ((Number) r[3]).intValue()));
        }
        return out;
    }
}
