-- V4: swap request integrity
--   * longer reason / review notes (were VARCHAR(255) and would fail on longer text)
--   * optimistic-locking version column
--   * at most ONE pending request per (shift, requester): older duplicates are cancelled first, then a partial unique
--     index enforces it in the database.
ALTER TABLE swap_requests
    ALTER COLUMN reason TYPE VARCHAR(1000),
    ALTER COLUMN review_notes TYPE VARCHAR(1000),
    ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;

UPDATE swap_requests a
   SET status = 'Cancelled',
       review_notes = 'Cancelled automatically: duplicate pending request'
  FROM swap_requests b
 WHERE a.shift_id = b.shift_id AND a.requester_id = b.requester_id AND a.id <> b.id
   AND a.status = 'Pending' AND b.status = 'Pending'
   AND (COALESCE(b.created_at, TIMESTAMP '1970-01-01') > COALESCE(a.created_at, TIMESTAMP '1970-01-01')
        OR (COALESCE(b.created_at, TIMESTAMP '1970-01-01') = COALESCE(a.created_at, TIMESTAMP '1970-01-01')
            AND b.id > a.id));

CREATE UNIQUE INDEX IF NOT EXISTS uk_swap_one_pending ON swap_requests (shift_id, requester_id) WHERE status = 'Pending';
