-- API-RD-03 AC3: cancel / refund of a walk-in sale. Additive only; re-runnable (IF NOT EXISTS / OR REPLACE / DROP IF
-- EXISTS) like V17 and V18, because tests and the platform reactivate path delete the history of the newest
-- migrations and migrate again. The tenant is the schema, so nothing here carries a tenant column.
--
-- One append-only row per walk-in order that left COMPLETED: who did it, why, from which status, which Idempotency-Key
-- and request fingerprint (a replay of the same key answers from this row), how much was paid back.
CREATE TABLE IF NOT EXISTS walk_in_order_audit_events
(
    id                  BIGSERIAL PRIMARY KEY,
    walk_in_order_id    BIGINT         NOT NULL,
    event_type          VARCHAR(32)    NOT NULL,
    from_status         VARCHAR(32)    NOT NULL,
    to_status           VARCHAR(32)    NOT NULL,
    actor_id            BIGINT,
    actor_email         VARCHAR(255)   NOT NULL,
    reason              VARCHAR(255)   NOT NULL,
    refund_amount       NUMERIC(19, 2) NOT NULL,
    idempotency_key     VARCHAR(64)    NOT NULL,
    request_fingerprint VARCHAR(64)    NOT NULL,
    created_at          TIMESTAMP      NOT NULL,
    CONSTRAINT fk_walk_in_audit_order FOREIGN KEY (walk_in_order_id) REFERENCES walk_in_orders (id) ON DELETE RESTRICT
);

-- A key is scoped to the order it was used on (tenant = schema); the same key on another order is another request.
CREATE UNIQUE INDEX IF NOT EXISTS uq_walk_in_audit_order_key
    ON walk_in_order_audit_events (walk_in_order_id, idempotency_key);

-- Database-level backstop of "stock restored and money returned exactly once": COMPLETED is the only status that can
-- be left and CANCELLED / REFUNDED are terminal, so an order has at most one such event. A second one (a bug, or a
-- race that slipped past the row lock) fails here and rolls the whole transaction back.
CREATE UNIQUE INDEX IF NOT EXISTS uq_walk_in_audit_one_event_per_order
    ON walk_in_order_audit_events (walk_in_order_id);

CREATE OR REPLACE FUNCTION walk_in_order_audit_events_immutable() RETURNS trigger AS
$$
BEGIN
    RAISE EXCEPTION 'walk_in_order_audit_events is append-only';
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_walk_in_order_audit_events_no_update_delete ON walk_in_order_audit_events;
CREATE TRIGGER trg_walk_in_order_audit_events_no_update_delete
    BEFORE UPDATE OR DELETE ON walk_in_order_audit_events
    FOR EACH ROW EXECUTE FUNCTION walk_in_order_audit_events_immutable();
