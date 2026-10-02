-- seats.status + seats.reservation_id are the authoritative ownership record.
-- HELD is reserved for a future hold-then-pay flow; nothing writes it today.
CREATE TABLE seats (
    id             bigserial   PRIMARY KEY,
    show_id        uuid        NOT NULL REFERENCES shows (id),
    label          text        NOT NULL,
    status         text        NOT NULL DEFAULT 'AVAILABLE'
                               CHECK (status IN ('AVAILABLE', 'HELD', 'CONFIRMED')),
    reservation_id uuid        REFERENCES reservations (id),
    updated_at     timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_seats_show_label UNIQUE (show_id, label),
    -- A seat has an owner if and only if it is not available.
    CONSTRAINT ck_seats_owner_iff_taken CHECK ((status = 'AVAILABLE') = (reservation_id IS NULL))
);

CREATE INDEX ix_seats_reservation ON seats (reservation_id) WHERE reservation_id IS NOT NULL;

-- History of which seats a reservation held. `active` flips to false on cancellation.
CREATE TABLE reservation_seats (
    reservation_id uuid    NOT NULL REFERENCES reservations (id),
    seat_id        bigint  NOT NULL REFERENCES seats (id),
    active         boolean NOT NULL DEFAULT true,
    PRIMARY KEY (reservation_id, seat_id)
);

-- Defence in depth: independent of any locking logic, the database physically refuses a second
-- active owner for the same seat.
CREATE UNIQUE INDEX ux_reservation_seats_one_active_owner ON reservation_seats (seat_id) WHERE active;
