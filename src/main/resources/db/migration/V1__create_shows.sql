-- Shows are immutable after creation: price_paise and per_user_limit never change, which is what
-- lets the per-user limit check treat :limit as a constant inside the reservation transaction.
CREATE TABLE shows (
    id             uuid        PRIMARY KEY,
    name           text        NOT NULL CHECK (length(name) BETWEEN 1 AND 200),
    price_paise    bigint      NOT NULL CHECK (price_paise >= 0),
    per_user_limit int         NOT NULL DEFAULT 4 CHECK (per_user_limit > 0),
    total_seats    int         NOT NULL CHECK (total_seats > 0),
    created_at     timestamptz NOT NULL DEFAULT now()
);
