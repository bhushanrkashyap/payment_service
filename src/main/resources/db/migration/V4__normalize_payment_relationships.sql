-- Keep cross-table relationships on immutable internal keys. Public payment_id
-- values remain stable API identifiers, but are not used as foreign keys.
ALTER TABLE payment_windows RENAME COLUMN payment_id TO legacy_payment_id;
ALTER TABLE payment_windows ADD COLUMN payment_id BIGINT;

UPDATE payment_windows w
SET payment_id = (
    SELECT p.id
    FROM payments p
    WHERE p.payment_id = w.legacy_payment_id
)
WHERE w.legacy_payment_id IS NOT NULL;

ALTER TABLE payment_windows DROP CONSTRAINT IF EXISTS payment_windows_payment_id_key;
ALTER TABLE payment_windows DROP CONSTRAINT IF EXISTS payment_windows_payment_id_fkey;
ALTER TABLE payment_windows ADD CONSTRAINT fk_payment_window_payment
    FOREIGN KEY (payment_id) REFERENCES payments(id);
CREATE UNIQUE INDEX uk_payment_window_payment ON payment_windows(payment_id);
ALTER TABLE payment_windows DROP COLUMN legacy_payment_id;

-- booking_id was duplicated in payment_requests and is already authoritative
-- on the referenced payment row.
ALTER TABLE payment_requests DROP COLUMN booking_id;
