-- EVT-01c (O5): one admin "payment received" notification per order, enforced by the database.
-- NotificationService used to check-then-insert (existsByTypeAndReferenceId, a scan of notifications), so two
-- concurrent runs of the same outbox row could both pass the check and write the notification twice. The partial
-- unique index below makes the INSERT ... ON CONFLICT DO NOTHING race-free and turns the check into an index probe.
-- Partial: other types keep their own rules (SHOP_FLOOR_RESTOCK legitimately has many rows per product over time).
--
-- Existing duplicates (written before this fix) are merged into the OLDEST notification of each order:
--   1. a recipient that read a duplicate marks the kept notification read for that user (read state is never lost);
--   2. recipients of the duplicates that the kept notification lacks are moved to it (one per user, read preferred);
--   3. the remaining recipients of the duplicates (same user already on the kept row) and the duplicates are deleted.
-- The steps are plain DML on this tenant's schema; on a schema without duplicates they change nothing.

CREATE TEMPORARY TABLE evt01c_dup_notifications ON COMMIT DROP AS
SELECT n.id, k.keep_id
FROM notifications n
JOIN (SELECT reference_id, min(id) AS keep_id
      FROM notifications
      WHERE type = 'PAYMENT_SUCCESS' AND reference_id IS NOT NULL
      GROUP BY reference_id
      HAVING count(*) > 1) k ON k.reference_id = n.reference_id
WHERE n.type = 'PAYMENT_SUCCESS' AND n.id <> k.keep_id;

-- 1. read state of a duplicate carries over to the kept notification's recipient row of the same user
UPDATE notification_recipients keep
SET is_read = TRUE,
    read_at = COALESCE(keep.read_at, dup.read_at)
FROM notification_recipients dup
JOIN evt01c_dup_notifications d ON d.id = dup.notification_id
WHERE keep.notification_id = d.keep_id
  AND keep.user_id = dup.user_id
  AND dup.is_read
  AND NOT keep.is_read;

-- 2. users that only a duplicate addressed move to the kept notification (one row per user: a read one if any)
UPDATE notification_recipients r
SET notification_id = m.keep_id
FROM (SELECT DISTINCT ON (d.keep_id, dup.user_id) dup.id, d.keep_id
      FROM notification_recipients dup
      JOIN evt01c_dup_notifications d ON d.id = dup.notification_id
      WHERE NOT EXISTS (SELECT 1 FROM notification_recipients k
                        WHERE k.notification_id = d.keep_id AND k.user_id = dup.user_id)
      ORDER BY d.keep_id, dup.user_id, dup.is_read DESC, dup.id) m
WHERE r.id = m.id;

-- 3. what is left of the duplicates
DELETE FROM notification_recipients WHERE notification_id IN (SELECT id FROM evt01c_dup_notifications);
DELETE FROM notifications WHERE id IN (SELECT id FROM evt01c_dup_notifications);

CREATE UNIQUE INDEX uk_notifications_payment_success_reference
    ON notifications (type, reference_id)
    WHERE type = 'PAYMENT_SUCCESS';
