-- V4: swap request integrity
--   * longer reason / review notes (were VARCHAR(255) and would fail on longer text)
--   * optimistic-locking version column
--   * at most ONE pending request per (shift, requester): older duplicates are cancelled first, then a
--     unique key over a generated column (NULL unless the request is Pending) enforces it in the database.
-- Requires MySQL 5.7.6+ / MariaDB 10.2+ (stored generated columns).

ALTER TABLE swap_requests
    MODIFY reason VARCHAR(1000) NULL,
    MODIFY review_notes VARCHAR(1000) NULL,
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

UPDATE swap_requests a
JOIN swap_requests b
  ON a.shift_id = b.shift_id AND a.requester_id = b.requester_id AND a.id <> b.id
 AND a.status = 'Pending' AND b.status = 'Pending'
 AND (COALESCE(b.created_at, '1970-01-01') > COALESCE(a.created_at, '1970-01-01')
      OR (COALESCE(b.created_at, '1970-01-01') = COALESCE(a.created_at, '1970-01-01') AND b.id > a.id))
SET a.status = 'Cancelled',
    a.review_notes = 'Cancelled automatically: duplicate pending request';

ALTER TABLE swap_requests
    ADD COLUMN pending_key VARCHAR(520) GENERATED ALWAYS AS
        (IF(status = 'Pending', CONCAT(shift_id, ':', requester_id), NULL)) STORED,
    ADD CONSTRAINT uk_swap_one_pending UNIQUE (pending_key);
