-- Logical payment record, written in the same transaction as the reservation. UNIQUE(reservation_id)
-- means one charge per reservation; one idempotency key yields at most one reservation; therefore a
-- retried request can never produce a second charge.
CREATE TABLE payments (
    id                uuid        PRIMARY KEY,
    reservation_id    uuid        NOT NULL UNIQUE REFERENCES reservations (id),
    amount_paise      bigint      NOT NULL CHECK (amount_paise >= 0),
    status            text        NOT NULL CHECK (status IN ('CAPTURED', 'REFUNDED')),
    provider_idem_key text        NOT NULL UNIQUE,
    created_at        timestamptz NOT NULL DEFAULT now(),
    refunded_at       timestamptz,
    CHECK ((status = 'REFUNDED') = (refunded_at IS NOT NULL))
);
