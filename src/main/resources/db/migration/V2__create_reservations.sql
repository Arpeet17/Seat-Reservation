-- Lifecycle: CONFIRMED -> CANCELLED (terminal). Money is integer paise, never floating point.
CREATE TABLE reservations (
    id           uuid        PRIMARY KEY,
    show_id      uuid        NOT NULL REFERENCES shows (id),
    user_id      text        NOT NULL,
    status       text        NOT NULL CHECK (status IN ('CONFIRMED', 'CANCELLED')),
    seat_count   int         NOT NULL CHECK (seat_count > 0),
    amount_paise bigint      NOT NULL CHECK (amount_paise >= 0),
    created_at   timestamptz NOT NULL DEFAULT now(),
    cancelled_at timestamptz,
    CHECK ((status = 'CANCELLED') = (cancelled_at IS NOT NULL))
);

CREATE INDEX ix_reservations_show_user ON reservations (show_id, user_id);
