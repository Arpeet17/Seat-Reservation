-- Materialised per-(show, user) count of currently held seats. Replaces a racy
-- SELECT COUNT(*) ... then INSERT with a single row that is atomically incremented under a row lock
-- by INSERT ... ON CONFLICT DO UPDATE ... WHERE seats_reserved + n <= limit.
CREATE TABLE user_show_quota (
    show_id        uuid NOT NULL REFERENCES shows (id),
    user_id        text NOT NULL,
    seats_reserved int  NOT NULL CHECK (seats_reserved >= 0),
    PRIMARY KEY (show_id, user_id)
);
