-- EVT-01b (L2): transactional outbox for the after-commit side effects of a settled payment and a new order
-- (customer receipt, admin payment e-mail, admin in-app payment notification, admin new-order e-mail).
-- A row is written INSIDE the publishing transaction (so it exists exactly when the payment / order committed),
-- handed to the mail / notification executor right after the commit, and re-driven by OutboxDrainer when that
-- hand-off was refused (executor full or shut down), the worker crashed, or the send failed (bounded retries).
-- One row per (kind, reference): a replayed or duplicated event never queues the side effect twice.
-- reference holds a database id only (payment / order id), never personal data. No foreign keys: the insert
-- takes no lock on the payment / order / user rows (API-05 lock order is unchanged).
CREATE TABLE side_effect_outbox (
    id              BIGSERIAL PRIMARY KEY,
    kind            VARCHAR(40)  NOT NULL,
    reference       VARCHAR(64)  NOT NULL,
    status          VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    attempts        INT          NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ  NOT NULL,
    last_error      VARCHAR(200),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    completed_at    TIMESTAMPTZ,
    CONSTRAINT uk_side_effect_outbox_kind_reference UNIQUE (kind, reference),
    CONSTRAINT ck_side_effect_outbox_status CHECK (status IN ('PENDING', 'DONE', 'FAILED'))
);

CREATE INDEX idx_side_effect_outbox_due ON side_effect_outbox (next_attempt_at) WHERE status = 'PENDING';
CREATE INDEX idx_side_effect_outbox_done ON side_effect_outbox (completed_at) WHERE status = 'DONE';
